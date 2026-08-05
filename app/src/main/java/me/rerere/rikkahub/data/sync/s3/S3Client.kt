package me.rerere.rikkahub.data.sync.s3

import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readAvailable
import io.ktor.util.cio.readChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.utils.logSafeFailure
import me.rerere.rikkahub.utils.logSafeStarted
import me.rerere.rikkahub.utils.logSafeSuccess
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant

private const val TAG = "S3Client"

class S3Client(
    private val config: S3Config,
    private val httpClient: HttpClient,
) {
    suspend fun putObjectConditional(
        key: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
        ifNoneMatch: Boolean = false,
        ifMatch: String? = null,
    ): Result<S3ConditionalPutResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(!(ifNoneMatch && ifMatch != null))
            val conditions = buildMap {
                if (ifNoneMatch) put("if-none-match", "*")
                ifMatch?.let { put("if-match", it) }
            }
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "PUT",
                path = path,
                headers = conditions,
                payload = data,
                contentType = contentType,
            )
            val response = httpClient.request(signed.url) {
                method = HttpMethod.Put
                headers { signed.headers.forEach { (name, value) -> append(name, value) } }
                setBody(data)
            }
            if (response.status == HttpStatusCode.PreconditionFailed) {
                return@runCatching S3ConditionalPutResult.PreconditionFailed(
                    response.headers["etag"],
                )
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                throw S3Exception("Failed conditional put: ${response.status}", body)
            }
            S3ConditionalPutResult.Written(
                etag = response.headers["etag"]
                    ?: throw S3Exception("Conditional PUT response has no ETag", ""),
            )
        }
    }

    suspend fun putObject(
        key: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "PUT",
                path = path,
                payload = data,
                contentType = contentType,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Put
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
                setBody(data)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "s3_put_object", httpStatus = response.status.value)
                throw S3Exception("Failed to put object: ${response.status}", errorBody)
            }

            logSafeSuccess(TAG, "sync", "s3_put_object")
            Unit
        }
    }

    suspend fun putObject(
        key: String,
        file: File,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val fileSha256 = file.sha256Hex()
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "PUT",
                path = path,
                payloadHash = fileSha256,
                contentLength = file.length(),
                contentType = contentType,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Put
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
                // Stream large files to avoid loading backup archives into heap.
                setBody(file.readChannel())
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "s3_put_object_file", httpStatus = response.status.value)
                throw S3Exception("Failed to put object: ${response.status}", errorBody)
            }

            logSafeSuccess(
                TAG,
                "sync",
                "s3_put_object_file",
                itemCount = file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
            Unit
        }
    }

    suspend fun getObject(key: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "GET",
                path = path,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Get
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "s3_get_object", httpStatus = response.status.value)
                throw S3Exception("Failed to get object: ${response.status}", errorBody)
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().readBytes()
        }
    }

    suspend fun getObjectStream(key: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "GET",
                path = path,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Get
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "s3_get_object_stream", httpStatus = response.status.value)
                throw S3Exception("Failed to get object stream: ${response.status}", errorBody)
            }

            response.bodyAsChannel().toInputStream()
        }
    }

    suspend fun downloadObjectToFile(key: String, targetFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "GET",
                path = path,
            )

            logSafeStarted(TAG, "sync", "s3_download_object")

            httpClient.prepareRequest(signed.url) {
                method = HttpMethod.Get
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    logSafeFailure(TAG, "sync", "s3_download_object", httpStatus = response.status.value)
                    throw S3Exception("Failed to download object: ${response.status}", errorBody)
                }

                response.bodyAsChannel().toInputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                logSafeSuccess(
                    TAG,
                    "sync",
                    "s3_download_object",
                    itemCount = targetFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
            Unit
        }
    }

    suspend fun deleteObject(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "DELETE",
                path = path,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Delete
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "s3_delete_object", httpStatus = response.status.value)
                throw S3Exception("Failed to delete object: ${response.status}", errorBody)
            }

            logSafeSuccess(TAG, "sync", "s3_delete_object")
            Unit
        }
    }

    suspend fun headObject(key: String): Result<S3ObjectMetadata> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(
                config = config,
                method = "HEAD",
                path = path,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Head
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                throw S3Exception("Object not found: ${response.status}", "")
            }

            S3ObjectMetadata(
                key = key,
                size = response.headers["content-length"]?.toLongOrNull() ?: 0,
                contentType = response.headers["content-type"] ?: "application/octet-stream",
                etag = response.headers["etag"],
                lastModified = response.headers["last-modified"],
            )
        }
    }

    suspend fun listObjects(
        prefix: String = "",
        delimiter: String = "",
        maxKeys: Int = 1000,
        continuationToken: String? = null,
    ): Result<S3ListResult> = withContext(Dispatchers.IO) {
        runCatching {
            val queryParams = mutableMapOf(
                "list-type" to "2",
                "max-keys" to maxKeys.toString(),
            )
            if (prefix.isNotEmpty()) queryParams["prefix"] = prefix
            if (delimiter.isNotEmpty()) queryParams["delimiter"] = delimiter
            continuationToken?.let { queryParams["continuation-token"] = it }

            val signed = AwsSignatureV4.sign(
                config = config,
                method = "GET",
                path = "/",
                queryParams = queryParams,
            )

            val response: HttpResponse = httpClient.request(signed.url) {
                method = HttpMethod.Get
                headers {
                    signed.headers.forEach { (k, v) -> append(k, v) }
                }
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "s3_list_objects", httpStatus = response.status.value)
                throw S3Exception("Failed to list objects: ${response.status}", errorBody)
            }

            val xmlBody = response.bodyAsText()
            parseListObjectsResponse(xmlBody)
        }
    }

    suspend fun objectExists(key: String): Boolean {
        return headObject(key).isSuccess
    }

    fun getPublicUrl(key: String): String {
        val path = "/${key.trimStart('/')}"
        return if (config.pathStyle) {
            "${config.endpoint.trimEnd('/')}/${config.bucket}$path"
        } else {
            val scheme = if (config.isHttps) "https://" else "http://"
            "$scheme${config.bucket}.${config.host}$path"
        }
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun getObjectVersioned(key: String): Result<S3VersionedObject?> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "/${key.trimStart('/')}"
            val signed = AwsSignatureV4.sign(config = config, method = "GET", path = path)
            val response = httpClient.request(signed.url) {
                method = HttpMethod.Get
                headers { signed.headers.forEach { (name, value) -> append(name, value) } }
            }
            if (response.status == HttpStatusCode.NotFound) {
                return@runCatching null
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                throw S3Exception("Failed to get versioned object: ${response.status}", body)
            }
            S3VersionedObject(
                body = response.bodyAsChannel().toInputStream().readBytes(),
                etag = response.headers["etag"]
                    ?: throw S3Exception("Versioned object response has no ETag", ""),
            )
        }
    }

    private fun parseListObjectsResponse(xml: String): S3ListResult {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val objects = mutableListOf<S3Object>()
        val commonPrefixes = mutableListOf<String>()
        var isTruncated = false
        var nextContinuationToken: String? = null
        var keyCount = 0

        var currentKey: String? = null
        var currentSize: Long = 0
        var currentEtag: String? = null
        var currentLastModified: Instant? = null
        var currentStorageClass: String? = null
        var currentPrefix: String? = null

        var inContents = false
        var inCommonPrefixes = false
        var currentTag: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (parser.name) {
                        "Contents" -> {
                            inContents = true
                            currentKey = null
                            currentSize = 0
                            currentEtag = null
                            currentLastModified = null
                            currentStorageClass = null
                        }

                        "CommonPrefixes" -> {
                            inCommonPrefixes = true
                            currentPrefix = null
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when {
                            inContents -> {
                                when (currentTag) {
                                    "Key" -> currentKey = text
                                    "Size" -> currentSize = text.toLongOrNull() ?: 0
                                    "ETag" -> currentEtag = text
                                    "LastModified" -> currentLastModified =
                                        runCatching { Instant.parse(text) }.getOrNull()

                                    "StorageClass" -> currentStorageClass = text
                                }
                            }

                            inCommonPrefixes -> {
                                if (currentTag == "Prefix") currentPrefix = text
                            }

                            else -> {
                                when (currentTag) {
                                    "IsTruncated" -> isTruncated = text.toBoolean()
                                    "NextContinuationToken" -> nextContinuationToken = text
                                    "KeyCount" -> keyCount = text.toIntOrNull() ?: 0
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "Contents" -> {
                            currentKey?.let { key ->
                                objects.add(
                                    S3Object(
                                        key = key,
                                        size = currentSize,
                                        etag = currentEtag,
                                        lastModified = currentLastModified,
                                        storageClass = currentStorageClass,
                                    )
                                )
                            }
                            inContents = false
                        }

                        "CommonPrefixes" -> {
                            currentPrefix?.let { commonPrefixes.add(it) }
                            inCommonPrefixes = false
                        }
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return S3ListResult(
            objects = objects,
            commonPrefixes = commonPrefixes,
            isTruncated = isTruncated,
            nextContinuationToken = nextContinuationToken,
            keyCount = if (keyCount > 0) keyCount else objects.size,
        )
    }
}

sealed interface S3ConditionalPutResult {
    data class Written(val etag: String) : S3ConditionalPutResult
    data class PreconditionFailed(val currentEtag: String?) : S3ConditionalPutResult
}

data class S3VersionedObject(val body: ByteArray, val etag: String)

data class S3Object(
    val key: String,
    val size: Long,
    val etag: String?,
    val lastModified: Instant?,
    val storageClass: String?,
)

data class S3ObjectMetadata(
    val key: String,
    val size: Long,
    val contentType: String,
    val etag: String?,
    val lastModified: String?,
)

data class S3ListResult(
    val objects: List<S3Object>,
    val commonPrefixes: List<String>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
    val keyCount: Int,
)

class S3Exception(
    message: String,
    val responseBody: String,
) : Exception(message)
