package me.rerere.rikkahub.data.credential

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkahub.data.ai.mcp.McpHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialEntryIdentityTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `legacy custom and mcp entries acquire persistent stable ids`() {
        val header = json.decodeFromString<CustomHeader>("""{"name":"Authorization","value":"secret"}""")
        val body = json.decodeFromString<CustomBody>("""{"key":"api_key","value":"secret"}""")
        val mcp = json.decodeFromString<McpHeader>("""{"first":"X-Api-Key","second":"secret"}""")

        assertTrue(json.encodeToString(header).contains(header.id.toString()))
        assertTrue(json.encodeToString(body).contains(body.id.toString()))
        assertTrue(json.encodeToString(mcp).contains(mcp.id.toString()))
        assertEquals(JsonPrimitive("secret"), body.value)
        assertEquals("X-Api-Key", mcp.first)
        assertEquals("secret", mcp.second)
    }
}
