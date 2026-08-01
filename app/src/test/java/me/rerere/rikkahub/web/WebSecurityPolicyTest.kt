package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.web.routes.resolveManagedWebFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WebSecurityPolicyTest {
    @Test
    fun `web server defaults to loopback`() {
        assertTrue(Settings().webServerLocalhostOnly)
        assertTrue(
            shouldBindWebServerToLoopback(
                requestedLocalhostOnly = true,
                jwtEnabled = false,
                accessPassword = "",
            )
        )
    }

    @Test
    fun `lan binding requires jwt and a nonblank password`() {
        assertTrue(shouldBindWebServerToLoopback(false, jwtEnabled = false, accessPassword = "secret"))
        assertTrue(shouldBindWebServerToLoopback(false, jwtEnabled = true, accessPassword = ""))
        assertFalse(shouldBindWebServerToLoopback(false, jwtEnabled = true, accessPassword = "secret"))
    }

    @Test
    fun `web file resolver only serves registered files inside files root`() {
        val root: File = Files.createTempDirectory("rikkahub-web-files").toFile()
        try {
            val upload = root.resolve("upload/managed.png").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("image")
            }
            val datastore = root.resolve("datastore/settings.preferences_pb").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("secret")
            }

            assertEquals(
                upload.canonicalFile,
                resolveManagedWebFile(root, "upload/managed.png", "upload/managed.png"),
            )
            assertNull(resolveManagedWebFile(root, "datastore/settings.preferences_pb", null))
            assertNull(resolveManagedWebFile(root, "../outside.txt", "../outside.txt"))
            assertTrue(datastore.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
