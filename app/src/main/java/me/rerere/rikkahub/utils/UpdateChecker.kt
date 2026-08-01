package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.net.URI

private const val APK_MIME = "application/vnd.android.package-archive"
private const val UPDATE_PREFERENCES = "app_update"
private const val PREF_IGNORED_VERSION = "ignored_version"
private const val PREF_DOWNLOAD_ID = "download_id"
private const val PREF_DOWNLOAD_CONTEXT = "download_context"
private const val PREF_LAST_KNOWN_GOOD_FEED = "last_known_good_feed"
private const val PREF_LAST_RESOLVED_DOWNLOAD_URL = "last_resolved_download_url"
private const val PREF_LAST_SUCCESSFUL_CHECK_AT = "last_successful_check_at"
private const val NO_DOWNLOAD = -1L

class UpdateChecker(
    private val context: Context,
    private val client: OkHttpClient,
    private val appScope: AppScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val preferences = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Checking)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()
    private var downloadObserver: Job? = null
    private val checkGate = UpdateCheckSingleFlight()

    init {
        appScope.launch {
            val restoredId = preferences.getLong(PREF_DOWNLOAD_ID, NO_DOWNLOAD)
            if (restoredId != NO_DOWNLOAD) {
                val restoredContext = preferences.getString(PREF_DOWNLOAD_CONTEXT, null)
                    ?.let { runCatching { json.decodeFromString<DownloadContext>(it) }.getOrNull() }
                when {
                    restoredContext == null -> {
                        downloadManager.remove(restoredId)
                        clearDownload()
                        checkUpdate(silent = true)
                    }
                    !isNewer(restoredContext.info) -> {
                        downloadManager.remove(restoredId)
                        clearDownload()
                        checkUpdate(silent = true)
                    }
                    else -> observeDownload(restoredId, restoredContext)
                }
            } else if (PlayStoreUtil.isInstalledFromPlayStore(context)) {
                _state.value = AppUpdateState.UpToDate
            } else {
                checkUpdate(silent = true)
            }
        }
    }

    fun checkUpdate(silent: Boolean = false) {
        if (!checkGate.tryEnter()) return
        if (!silent) _state.value = AppUpdateState.Checking
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                runCatching { fetchUpdateResult() }
                .onSuccess { result ->
                    val info = result.info
                    val ignored = preferences.getString(PREF_IGNORED_VERSION, null)
                    _state.value = when {
                        result.stale -> AppUpdateState.Stale(
                            info = info.takeIf { isNewer(it) && ignored != it.version },
                            lastSuccessfulCheckAt = result.lastSuccessfulCheckAt,
                            message = result.warning ?: "Update check used cached data",
                        )
                        isNewer(info) && ignored != info.version -> AppUpdateState.Available(info)
                        else -> AppUpdateState.UpToDate
                    }
                }
                .onFailure { error ->
                    val lastSuccess = preferences.getLong(PREF_LAST_SUCCESSFUL_CHECK_AT, 0L).takeIf { it > 0 }
                    _state.value = if (lastSuccess != null) {
                        AppUpdateState.Stale(
                            info = null,
                            lastSuccessfulCheckAt = lastSuccess,
                            message = error.message ?: "Update check failed",
                        )
                    } else {
                        AppUpdateState.Failed(error.message ?: "Update check failed")
                    }
                }
            } finally {
                checkGate.leave()
            }
        }
    }

    fun ignoreVersion(version: String) {
        preferences.edit().putString(PREF_IGNORED_VERSION, version).apply()
        _state.value = AppUpdateState.UpToDate
    }

    fun startDownload(info: UpdateInfo) {
        val download = selectDownload(info) ?: run {
            _state.value = AppUpdateState.Failed("No compatible APK found", info)
            return
        }
        _state.value = AppUpdateState.Verifying(info, download)
        appScope.launch(Dispatchers.IO) {
            runCatching { resolveDownload(download) }
                .onSuccess { resolved -> enqueueDownload(info, resolved) }
                .onFailure { error ->
                    _state.value = AppUpdateState.Failed(
                        error.message ?: "Unable to validate update download location",
                        info,
                    )
                }
        }
    }

    private fun enqueueDownload(info: UpdateInfo, download: UpdateDownload) {
        downloadedApkFile(download)?.takeIf { it.isFile }?.delete()
        val request = DownloadManager.Request(download.url.toUri()).apply {
            setTitle(download.name)
            setDescription("RikkaHub ${info.version}")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/${download.name}")
            setMimeType(APK_MIME)
        }
        runCatching { downloadManager.enqueue(request) }
            .onSuccess { id ->
                val downloadContext = DownloadContext(info, download)
                preferences.edit()
                    .putLong(PREF_DOWNLOAD_ID, id)
                    .putString(PREF_DOWNLOAD_CONTEXT, json.encodeToString(downloadContext))
                    .apply()
                observeDownload(id, downloadContext)
            }
            .onFailure { _state.value = AppUpdateState.Failed(it.message ?: "Download failed", info) }
    }

    fun cancelDownload() {
        val id = preferences.getLong(PREF_DOWNLOAD_ID, NO_DOWNLOAD)
        if (id != NO_DOWNLOAD) downloadManager.remove(id)
        clearDownload()
        checkUpdate(silent = true)
    }

    fun installDownloadedApk() {
        val id = preferences.getLong(PREF_DOWNLOAD_ID, NO_DOWNLOAD)
        if (id == NO_DOWNLOAD) return
        if (!canInstallUpdates()) return
        val downloadContext = preferences.getString(PREF_DOWNLOAD_CONTEXT, null)
            ?.let { runCatching { json.decodeFromString<DownloadContext>(it) }.getOrNull() }
            ?: return
        val apkFile = downloadedApkFile(downloadContext.download) ?: return
        runCatching { verifyDownloadedApk(apkFile, downloadContext.info) }
            .onFailure {
                _state.value = AppUpdateState.Failed(it.message ?: "Downloaded APK verification failed", downloadContext.info)
                return
            }
        val uri = downloadManager.getUriForDownloadedFile(id) ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure { _state.value = AppUpdateState.Failed(it.message ?: "Unable to start APK installer") }
    }

    fun canInstallUpdates(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        _state.value = AppUpdateState.Failed(it.message ?: "Unable to open install settings")
        false
    }

    internal suspend fun fetchUpdateInfo(): UpdateInfo = fetchUpdateResult().info

    private suspend fun fetchUpdateResult(): UpdateFetchResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(
                Request.Builder()
                    .url(BuildConfig.UPDATE_FEED_URL)
                    .get()
                    .addHeader("User-Agent", "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
                    .build()
            ).await().use { response ->
                check(response.isSuccessful) { "Update server returned HTTP ${response.code}" }
                UpdatePolicy.validateResolvedUrl(
                    requestedUrl = BuildConfig.UPDATE_FEED_URL,
                    resolvedUrl = response.request.url.toString(),
                    trustedRootUrl = BuildConfig.UPDATE_FEED_URL,
                )
                val envelope = response.body.string()
                val info = verifyFeed(envelope).also {
                    check(preferences.edit().putString(PREF_LAST_KNOWN_GOOD_FEED, envelope).commit()) {
                        "Verified update feed could not be persisted"
                    }
                }
                val checkedAt = System.currentTimeMillis()
                check(preferences.edit().putLong(PREF_LAST_SUCCESSFUL_CHECK_AT, checkedAt).commit()) {
                    "Update check timestamp could not be persisted"
                }
                UpdateFetchResult(info, stale = false, lastSuccessfulCheckAt = checkedAt)
            }
        } catch (error: IOException) {
            val cached = preferences.getString(PREF_LAST_KNOWN_GOOD_FEED, null) ?: throw error
            UpdateFetchResult(
                info = verifyFeed(cached),
                stale = true,
                lastSuccessfulCheckAt = preferences.getLong(PREF_LAST_SUCCESSFUL_CHECK_AT, 0L).takeIf { it > 0 },
                warning = error.message ?: "Update server is unavailable",
            )
        }
    }

    private fun verifyFeed(envelope: String): UpdateInfo =
        UpdateFeedVerifier.verifyAndDecode(
            envelopeJson = envelope,
            expectedKeyId = BuildConfig.UPDATE_FEED_KEY_ID,
            publicKeyDerBase64 = BuildConfig.UPDATE_FEED_PUBLIC_KEY,
            json = json,
        ).also { info -> UpdatePolicy.validate(info, BuildConfig.UPDATE_SOURCE, BuildConfig.UPDATE_FEED_URL) }

    private suspend fun resolveDownload(download: UpdateDownload): UpdateDownload {
        return client.newCall(Request.Builder().url(download.url).head().build()).await().use { response ->
            check(response.isSuccessful) { "Update download server returned HTTP ${response.code}" }
            val resolvedUrl = response.request.url.toString()
            UpdatePolicy.validateResolvedUrl(download.url, resolvedUrl, BuildConfig.UPDATE_FEED_URL)
            preferences.edit().putString(PREF_LAST_RESOLVED_DOWNLOAD_URL, resolvedUrl).apply()
            download.copy(url = resolvedUrl)
        }
    }

    internal fun isNewer(info: UpdateInfo): Boolean {
        return UpdatePolicy.isNewer(
            info = info,
            currentVersion = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE.toIntOrNull(),
        )
    }

    internal fun selectDownload(info: UpdateInfo): UpdateDownload? {
        return UpdatePolicy.selectDownload(info, Build.SUPPORTED_ABIS.toList())
    }

    private fun observeDownload(id: Long, contextInfo: DownloadContext?) {
        downloadObserver?.cancel()
        downloadObserver = appScope.launch(Dispatchers.IO) {
            var info = contextInfo
            while (true) {
                val snapshot = queryDownload(id) ?: run {
                    downloadManager.remove(id)
                    clearDownload()
                    _state.value = AppUpdateState.Failed(
                        message = "Downloaded update is no longer available",
                        info = info?.info,
                    )
                    return@launch
                }
                when (snapshot.status) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_RUNNING -> {
                        _state.value = AppUpdateState.Downloading(info?.info, info?.download, snapshot.progress)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        _state.value = AppUpdateState.Verifying(info?.info, info?.download)
                        val uri = downloadManager.getUriForDownloadedFile(id)
                        val expected = info?.download?.sha256
                        val verification = runCatching {
                            check(expected != null && uri != null) { "Downloaded APK metadata is unavailable" }
                            check(verifySha256(uri, expected)) { "APK integrity check failed" }
                            val current = checkNotNull(info) { "Downloaded APK context is unavailable" }
                            val apkFile = checkNotNull(downloadedApkFile(current.download)) {
                                "Downloaded APK file is unavailable"
                            }
                            verifyDownloadedApk(apkFile, current.info)
                        }
                        if (verification.isFailure) {
                            downloadManager.remove(id)
                            clearDownload()
                            _state.value = AppUpdateState.Failed(
                                verification.exceptionOrNull()?.message ?: "Downloaded APK verification failed",
                                info?.info,
                            )
                        } else {
                            _state.value = AppUpdateState.ReadyToInstall(info?.info, info?.download)
                        }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        downloadManager.remove(id)
                        clearDownload()
                        _state.value = AppUpdateState.Failed(
                            message = "Download failed (${snapshot.reason})",
                            info = info?.info,
                        )
                        return@launch
                    }
                }
                delay(750)
            }
        }
    }

    private fun queryDownload(id: Long): DownloadSnapshot? =
        downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            DownloadSnapshot(status, if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0, reason)
        }

    private fun verifySha256(uri: Uri, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return false
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected.trim(), ignoreCase = true)
    }

    private fun downloadedApkFile(download: UpdateDownload): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("updates/${download.name}")

    @Suppress("DEPRECATION")
    private fun verifyDownloadedApk(apkFile: File, info: UpdateInfo) {
        check(apkFile.isFile) { "Downloaded APK file is unavailable" }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = checkNotNull(context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)) {
            "Downloaded file is not a readable APK"
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty().toList()
        } else {
            packageInfo.signatures.orEmpty().toList()
        }
        val signerDigests = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        UpdateArtifactPolicy.validateMetadata(
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName,
            versionCode = versionCode,
            signerSha256 = signerDigests,
            info = info,
            expectedPackageName = BuildConfig.UPDATE_PACKAGE_NAME,
            expectedSignerSha256 = BuildConfig.UPDATE_APK_SIGNER_SHA256,
        )
    }

    private fun clearDownload() {
        downloadObserver?.cancel()
        downloadObserver = null
        preferences.edit()
            .remove(PREF_DOWNLOAD_ID)
            .remove(PREF_DOWNLOAD_CONTEXT)
            .apply()
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String = "",
    val sizeBytes: Long? = null,
    val abi: String? = null,
    val sha256: String? = null,
)

@Serializable
data class UpdateInfo(
    val schemaVersion: Int = 1,
    val source: String,
    val channel: String = "stable",
    val version: String,
    val versionCode: Int? = null,
    val publishedAt: String,
    val changelog: String,
    val releaseUrl: String? = null,
    val downloads: List<UpdateDownload>,
)

sealed interface AppUpdateState {
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val info: UpdateInfo) : AppUpdateState
    data class Downloading(val info: UpdateInfo?, val download: UpdateDownload?, val progress: Int) : AppUpdateState
    data class Verifying(val info: UpdateInfo?, val download: UpdateDownload?) : AppUpdateState
    data class ReadyToInstall(val info: UpdateInfo?, val download: UpdateDownload?) : AppUpdateState
    data class Stale(
        val info: UpdateInfo?,
        val lastSuccessfulCheckAt: Long?,
        val message: String,
    ) : AppUpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : AppUpdateState
}

@Serializable
private data class DownloadContext(val info: UpdateInfo, val download: UpdateDownload)
private data class DownloadSnapshot(val status: Int, val progress: Int, val reason: Int)
private data class UpdateFetchResult(
    val info: UpdateInfo,
    val stale: Boolean,
    val lastSuccessfulCheckAt: Long?,
    val warning: String? = null,
)

internal object UpdatePolicy {
    fun validate(info: UpdateInfo, expectedSource: String, trustedRootUrl: String? = null) {
        check(info.source == expectedSource) { "Unexpected update source" }
        check(info.channel == "stable") { "Unexpected update channel" }
        if (info.schemaVersion >= 2) {
            check(info.versionCode != null) { "Signed update feed is missing version code" }
        }
        check(info.downloads.all { it.url.startsWith("https://") }) { "Insecure download URL" }
        check(info.downloads.all { it.name.matches(Regex("^[A-Za-z0-9._-]+\\.apk$")) }) {
            "Invalid APK filename"
        }
        check(info.downloads.all { it.sha256?.matches(Regex("^[a-fA-F0-9]{64}$")) == true }) {
            "Missing or invalid APK checksum"
        }
        if (trustedRootUrl != null) {
            info.downloads.forEach { validateResolvedUrl(it.url, it.url, trustedRootUrl) }
        }
    }

    fun validateResolvedUrl(requestedUrl: String, resolvedUrl: String, trustedRootUrl: String) {
        val trusted = URI(trustedRootUrl)
        val requested = URI(requestedUrl)
        val resolved = URI(resolvedUrl)
        listOf(requested, resolved).forEach { candidate ->
            check(candidate.scheme.equals("https", ignoreCase = true)) { "Insecure update URL" }
            check(candidate.host.equals(trusted.host, ignoreCase = true)) {
                "Update redirect left the trusted host"
            }
            check(candidate.userInfo == null && candidate.fragment == null) { "Invalid update URL" }
            check(candidate.port == -1 || candidate.port == 443) { "Unexpected update URL port" }
        }
    }

    fun isNewer(info: UpdateInfo, currentVersion: String, currentVersionCode: Int?): Boolean {
        return if (info.versionCode != null && currentVersionCode != null) {
            info.versionCode > currentVersionCode
        } else {
            Version(info.version) > Version(currentVersion)
        }
    }

    fun selectDownload(info: UpdateInfo, supportedAbis: List<String>): UpdateDownload? {
        return info.downloads.firstOrNull { candidate ->
            val abi = candidate.abi
                ?: supportedAbis.firstOrNull { candidate.name.contains(it, ignoreCase = true) }
            abi != null && abi in supportedAbis
        } ?: info.downloads.firstOrNull {
            it.abi.equals("universal", ignoreCase = true) || it.name.contains("universal", ignoreCase = true)
        } ?: info.downloads.singleOrNull()
    }
}

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
