package me.rerere.ai.provider.providers

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress

internal object GeneratedImageDownloadPolicy {
    const val MAX_IMAGE_BYTES: Long = 25L * 1024 * 1024
    const val MAX_REDIRECTS = 3

    private val allowedMimeTypes = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/gif",
        "image/avif",
    )

    fun isLocalDevelopmentBaseUrl(rawUrl: String): Boolean {
        val url = rawUrl.toHttpUrlOrNull() ?: return false
        return isLoopbackHost(url.host)
    }

    fun validateUrl(rawUrl: String, allowLocalDevelopment: Boolean): HttpUrl {
        val url = rawUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Generated image URL is invalid")
        val localDevelopmentUrl = allowLocalDevelopment && isLoopbackHost(url.host)
        require(url.scheme == "https" || (url.scheme == "http" && localDevelopmentUrl)) {
            "Generated image URL must use HTTPS"
        }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Generated image URL must not contain credentials"
        }
        return url
    }

    fun validateResolvedAddresses(
        url: HttpUrl,
        addresses: List<InetAddress>,
        allowLocalDevelopment: Boolean,
    ) {
        require(addresses.isNotEmpty()) { "Generated image host did not resolve" }
        val localDevelopmentUrl = allowLocalDevelopment && isLoopbackHost(url.host)
        addresses.forEach { address ->
            val allowedDevelopmentLoopback = localDevelopmentUrl && address.isLoopbackAddress
            require(allowedDevelopmentLoopback || !address.isBlockedDestination()) {
                "Generated image URL resolves to a local or private network"
            }
        }
    }

    fun validateMimeType(contentType: String?): String {
        val mimeType = contentType?.substringBefore(';')?.trim()?.lowercase()
            ?: throw IllegalArgumentException("Generated image response has no Content-Type")
        require(mimeType in allowedMimeTypes) {
            "Generated image response has unsupported Content-Type"
        }
        return mimeType
    }

    fun validateContentLength(contentLength: Long) {
        require(contentLength < 0 || contentLength <= MAX_IMAGE_BYTES) {
            "Generated image response is too large"
        }
    }

    fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_IMAGE_BYTES) { "Generated image response is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun isLoopbackHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val isIpLiteral = host.contains(':') || host.all { it.isDigit() || it == '.' }
        if (!isIpLiteral) return false
        return runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }

    private fun InetAddress.isBlockedDestination(): Boolean {
        if (
            isAnyLocalAddress ||
            isLoopbackAddress ||
            isLinkLocalAddress ||
            isSiteLocalAddress ||
            isMulticastAddress
        ) {
            return true
        }
        val raw = address
        if (raw.size == 4) {
            val first = raw[0].toInt() and 0xff
            val second = raw[1].toInt() and 0xff
            return first == 0 ||
                first == 10 ||
                first == 127 ||
                first == 169 && second == 254 ||
                first == 172 && second in 16..31 ||
                first == 192 && second == 168 ||
                first == 100 && second in 64..127 ||
                first >= 224
        }
        val first = raw.firstOrNull()?.toInt()?.and(0xff) ?: return true
        return first and 0xfe == 0xfc // IPv6 unique-local fc00::/7.
    }
}
