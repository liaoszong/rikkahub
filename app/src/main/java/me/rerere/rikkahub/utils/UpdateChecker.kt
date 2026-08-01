package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.security.MessageDigest

private const val APK_MIME = "application/vnd.android.package-archive"
private const val UPDATE_PREFERENCES = "app_update"
private const val PREF_IGNORED_VERSION = "ignored_version"
private const val PREF_DOWNLOAD_ID = "download_id"
private const val PREF_DOWNLOAD_CONTEXT = "download_context"
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
        if (!silent) _state.value = AppUpdateState.Checking
        appScope.launch {
            runCatching { fetchUpdateInfo() }
                .onSuccess { info ->
                    val ignored = preferences.getString(PREF_IGNORED_VERSION, null)
                    _state.value = if (isNewer(info) && ignored != info.version) {
                        AppUpdateState.Available(info)
                    } else {
                        AppUpdateState.UpToDate
                    }
                }
                .onFailure { error ->
                    if (!silent) _state.value = AppUpdateState.Failed(error.message ?: "Update check failed")
                    else _state.value = AppUpdateState.UpToDate
                }
        }
    }

    fun ignoreVersion(version: String) {
        preferences.edit().putString(PREF_IGNORED_VERSION, version).apply()
        _state.value = AppUpdateState.UpToDate
    }

    fun startDownload(info: UpdateInfo) {
        val download = selectDownload(info) ?: run {
            _state.value = AppUpdateState.Failed("No compatible APK found")
            return
        }
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("updates/${download.name}")
            ?.takeIf { it.isFile }
            ?.delete()
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
            .onFailure { _state.value = AppUpdateState.Failed(it.message ?: "Download failed") }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { _state.value = AppUpdateState.Failed(it.message ?: "Unable to open install settings") }
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

    internal suspend fun fetchUpdateInfo(): UpdateInfo = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(BuildConfig.UPDATE_FEED_URL)
                .get()
                .addHeader("User-Agent", "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
                .build()
        ).await().use { response ->
            check(response.isSuccessful) { "Update server returned HTTP ${response.code}" }
            json.decodeFromString<UpdateInfo>(response.body.string()).also { info ->
                UpdatePolicy.validate(info, BuildConfig.UPDATE_SOURCE)
            }
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
                    clearDownload()
                    _state.value = AppUpdateState.Failed("Downloaded update is no longer available")
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
                        if (expected != null && uri != null && !verifySha256(uri, expected)) {
                            downloadManager.remove(id)
                            clearDownload()
                            _state.value = AppUpdateState.Failed("APK integrity check failed")
                        } else {
                            _state.value = AppUpdateState.ReadyToInstall(info?.info, info?.download)
                        }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        clearDownload()
                        _state.value = AppUpdateState.Failed("Download failed (${snapshot.reason})")
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
    data class Failed(val message: String) : AppUpdateState
}

@Serializable
private data class DownloadContext(val info: UpdateInfo, val download: UpdateDownload)
private data class DownloadSnapshot(val status: Int, val progress: Int, val reason: Int)

internal object UpdatePolicy {
    fun validate(info: UpdateInfo, expectedSource: String) {
        check(info.source == expectedSource) { "Unexpected update source" }
        check(info.channel == "stable") { "Unexpected update channel" }
        check(info.downloads.all { it.url.startsWith("https://") }) { "Insecure download URL" }
        check(info.downloads.all { it.name.matches(Regex("^[A-Za-z0-9._-]+\\.apk$")) }) {
            "Invalid APK filename"
        }
        check(info.downloads.all { it.sha256?.matches(Regex("^[a-fA-F0-9]{64}$")) == true }) {
            "Missing or invalid APK checksum"
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
