package me.rerere.rikkahub.data.sync.webdav

import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
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
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.utils.logSafeError
import me.rerere.rikkahub.utils.logSafeFailure
import me.rerere.rikkahub.utils.logSafeStarted
import me.rerere.rikkahub.utils.logSafeSuccess
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "WebDavClient"

class WebDavClient(
    private val config: WebDavConfig,
    private val httpClient: HttpClient,
) {
    private fun WebDavConfig.buildUrl(vararg segments: String): String {
        val base = url.trimEnd('/')
        val pathSegments = listOfNotNull(
            path.takeIf { it.isNotBlank() }?.trim('/'),
            *segments.map { it.trim('/') }.toTypedArray()
        ).filter { it.isNotEmpty() }

        return if (pathSegments.isEmpty()) {
            base
        } else {
            "$base/${pathSegments.joinToString("/")}"
        }
    }
    suspend fun put(
        path: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_put")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", data.size.toString())
                }
                setBody(data)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "webdav_put", httpStatus = response.status.value)
                throw WebDavException("Failed to put: ${response.status}", response.status.value, errorBody)
            }

            logSafeSuccess(TAG, "sync", "webdav_put")
            Unit
        }
    }

    suspend fun putConditional(
        path: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
        ifNoneMatch: Boolean = false,
        ifMatch: String? = null,
    ): Result<WebDavConditionalPutResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(!(ifNoneMatch && ifMatch != null))
            val response: HttpResponse = httpClient.request(config.buildUrl(path)) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", data.size.toString())
                    if (ifNoneMatch) append("If-None-Match", "*")
                    ifMatch?.let { append("If-Match", it) }
                }
                setBody(data)
            }
            if (response.status == HttpStatusCode.PreconditionFailed) {
                return@runCatching WebDavConditionalPutResult.PreconditionFailed(
                    response.headers["etag"],
                )
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                throw WebDavException(
                    "Failed conditional put: ${response.status}",
                    response.status.value,
                    body,
                )
            }
            WebDavConditionalPutResult.Written(
                response.headers["etag"]
                    ?: throw WebDavException("Conditional PUT response has no ETag", response.status.value, ""),
            )
        }
    }

    suspend fun put(
        path: String,
        file: File,
        contentType: String = "application/octet-stream",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_put_file")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Put
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", contentType)
                    append("Content-Length", file.length().toString())
                }
                // Stream file content to avoid loading the whole backup zip into heap.
                setBody(file.readChannel())
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "webdav_put_file", httpStatus = response.status.value)
                throw WebDavException("Failed to put file: ${response.status}", response.status.value, errorBody)
            }

            logSafeSuccess(
                TAG,
                "sync",
                "webdav_put_file",
                itemCount = file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
            Unit
        }
    }

    suspend fun get(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_get")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "webdav_get", httpStatus = response.status.value)
                throw WebDavException("Failed to get: ${response.status}", response.status.value, errorBody)
            }

            val channel = response.bodyAsChannel()
            channel.toInputStream().readBytes()
        }
    }

    suspend fun getVersioned(path: String): Result<WebDavVersionedObject?> = withContext(Dispatchers.IO) {
        runCatching {
            val response: HttpResponse = httpClient.request(config.buildUrl(path)) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }
            if (response.status == HttpStatusCode.NotFound) {
                return@runCatching null
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                throw WebDavException(
                    "Failed to get versioned object: ${response.status}",
                    response.status.value,
                    body,
                )
            }
            WebDavVersionedObject(
                body = response.bodyAsChannel().toInputStream().readBytes(),
                etag = response.headers["etag"]
                    ?: throw WebDavException("Versioned object response has no ETag", response.status.value, ""),
            )
        }
    }

    suspend fun getStream(path: String): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_get_stream")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "webdav_get_stream", httpStatus = response.status.value)
                throw WebDavException("Failed to get stream: ${response.status}", response.status.value, errorBody)
            }

            response.bodyAsChannel().toInputStream()
        }
    }

    suspend fun downloadToFile(path: String, targetFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_download_file")

            httpClient.prepareRequest(url) {
                method = HttpMethod.Get
                basicAuth(config.username, config.password)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = response.bodyAsText()
                    logSafeFailure(TAG, "sync", "webdav_download_file", httpStatus = response.status.value)
                    throw WebDavException("Failed to download: ${response.status}", response.status.value, errorBody)
                }

                val channel = response.bodyAsChannel()
                targetFile.outputStream().use { outputStream ->
                    val buffer = ByteArray(8192)
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                }
                logSafeSuccess(
                    TAG,
                    "sync",
                    "webdav_download_file",
                    itemCount = targetFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
            Unit
        }
    }

    suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_delete")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Delete
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "webdav_delete", httpStatus = response.status.value)
                throw WebDavException("Failed to delete: ${response.status}", response.status.value, errorBody)
            }

            logSafeSuccess(TAG, "sync", "webdav_delete")
            Unit
        }
    }

    suspend fun head(path: String): Result<WebDavResourceInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_head")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod.Head
                basicAuth(config.username, config.password)
            }

            if (!response.status.isSuccess()) {
                throw WebDavException("Resource not found: ${response.status}", response.status.value, "")
            }

            WebDavResourceInfo(
                href = path,
                displayName = path.substringAfterLast("/"),
                contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0,
                contentType = response.headers["Content-Type"] ?: "application/octet-stream",
                lastModified = parseLastModified(response.headers["Last-Modified"]),
                isCollection = false,
                etag = response.headers["ETag"],
            )
        }
    }

    suspend fun mkcol(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_mkcol")

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod("MKCOL")
                basicAuth(config.username, config.password)
            }

            // 201 Created or 405 Method Not Allowed (already exists) are acceptable
            if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "webdav_mkcol", httpStatus = response.status.value)
                throw WebDavException("Failed to create collection: ${response.status}", response.status.value, errorBody)
            }

            logSafeSuccess(TAG, "sync", "webdav_mkcol")
            Unit
        }
    }

    suspend fun propfind(
        path: String = "",
        depth: Int = 1,
    ): Result<List<WebDavResourceInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = config.buildUrl(path)
            logSafeStarted(TAG, "sync", "webdav_propfind")

            val propfindBody = """<?xml version="1.0" encoding="UTF-8"?>
                |<D:propfind xmlns:D="DAV:">
                |  <D:prop>
                |    <D:displayname/>
                |    <D:getcontentlength/>
                |    <D:getcontenttype/>
                |    <D:getlastmodified/>
                |    <D:getetag/>
                |    <D:resourcetype/>
                |  </D:prop>
                |</D:propfind>
            """.trimMargin()

            val response: HttpResponse = httpClient.request(url) {
                method = HttpMethod("PROPFIND")
                basicAuth(config.username, config.password)
                headers {
                    append("Content-Type", "application/xml; charset=utf-8")
                    append("Depth", depth.toString())
                }
                setBody(propfindBody)
            }

            if (!response.status.isSuccess() && response.status.value != 207) {
                val errorBody = response.bodyAsText()
                logSafeFailure(TAG, "sync", "webdav_propfind", httpStatus = response.status.value)
                throw WebDavException("Failed to propfind: ${response.status}", response.status.value, errorBody)
            }

            val xmlBody = response.bodyAsText()
            parsePropfindResponse(xmlBody, url)
        }
    }

    suspend fun exists(path: String): Boolean {
        return head(path).isSuccess
    }

    suspend fun ensureCollectionExists(path: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            logSafeStarted(TAG, "sync", "webdav_ensure_collection")

            // Try propfind first to check if it exists
            val propfindResult = propfind(path, depth = 0)
            if (propfindResult.isSuccess) {
                logSafeSuccess(TAG, "sync", "webdav_ensure_collection")
                return@runCatching
            }

            // Create collection if not exists
            mkcol(path).getOrThrow()
        }
    }

    suspend fun list(path: String = ""): Result<List<WebDavResourceInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = propfind(path, depth = 1).getOrThrow()
            // Filter out the parent directory itself (first entry)
            if (result.isNotEmpty()) {
                result.drop(1)
            } else {
                emptyList()
            }
        }
    }

    private fun parsePropfindResponse(xml: String, baseUrl: String): List<WebDavResourceInfo> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val resources = mutableListOf<WebDavResourceInfo>()
        var currentHref: String? = null
        var currentDisplayName: String? = null
        var currentContentLength: Long = 0
        var currentContentType: String? = null
        var currentLastModified: Instant? = null
        var currentEtag: String? = null
        var currentIsCollection = false
        var currentTag: String? = null
        var inResponse = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.substringAfter(":")
                    when (currentTag) {
                        "response" -> {
                            inResponse = true
                            currentHref = null
                            currentDisplayName = null
                            currentContentLength = 0
                            currentContentType = null
                            currentLastModified = null
                            currentEtag = null
                            currentIsCollection = false
                        }
                        "collection" -> {
                            currentIsCollection = true
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty() && inResponse) {
                        when (currentTag) {
                            "href" -> currentHref = text
                            "displayname" -> currentDisplayName = text
                            "getcontentlength" -> currentContentLength = text.toLongOrNull() ?: 0
                            "getcontenttype" -> currentContentType = text
                            "getlastmodified" -> currentLastModified = parseLastModified(text)
                            "getetag" -> currentEtag = text
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tagName = parser.name.substringAfter(":")
                    if (tagName == "response" && currentHref != null) {
                        val displayName = currentDisplayName
                            ?: currentHref!!.trimEnd('/').substringAfterLast("/")

                        resources.add(
                            WebDavResourceInfo(
                                href = currentHref!!,
                                displayName = displayName,
                                contentLength = currentContentLength,
                                contentType = currentContentType ?: "application/octet-stream",
                                lastModified = currentLastModified,
                                isCollection = currentIsCollection,
                                etag = currentEtag,
                            )
                        )
                        inResponse = false
                    }
                    currentTag = null
                }
            }
            parser.next()
        }

        return resources
    }

    private fun parseLastModified(dateString: String?): Instant? {
        if (dateString.isNullOrBlank()) return null

        return try {
            // RFC 1123 format: "Tue, 15 Nov 1994 08:12:31 GMT"
            ZonedDateTime.parse(dateString, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                // RFC 850 format: "Tuesday, 15-Nov-94 08:12:31 GMT"
                ZonedDateTime.parse(dateString, DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz")).toInstant()
            } catch (e: Exception) {
                try {
                    // ISO 8601
                    Instant.parse(dateString)
                } catch (e: Exception) {
                    logSafeError(TAG, "sync", "parse_webdav_date", e, warning = true)
                    null
                }
            }
        }
    }
}

sealed interface WebDavConditionalPutResult {
    data class Written(val etag: String) : WebDavConditionalPutResult
    data class PreconditionFailed(val currentEtag: String?) : WebDavConditionalPutResult
}

data class WebDavVersionedObject(val body: ByteArray, val etag: String)

data class WebDavResourceInfo(
    val href: String,
    val displayName: String,
    val contentLength: Long,
    val contentType: String,
    val lastModified: Instant?,
    val isCollection: Boolean,
    val etag: String? = null,
)

class WebDavException(
    message: String,
    val statusCode: Int,
    val responseBody: String,
) : Exception(message)
