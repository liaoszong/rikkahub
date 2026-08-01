package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolSecurityPolicyTest {
    @Test
    fun `newly discovered tools are disabled and require approval`() {
        val merged = mergeMcpTools(
            storedTools = emptyList(),
            discoveredTools = listOf(tool(name = "delete_file", schemaVersion = 1)),
        )

        assertFalse(merged.single().enable)
        assertTrue(merged.single().needsApproval)
    }

    @Test
    fun `unchanged tools preserve explicit user permission`() {
        val stored = tool(name = "search", schemaVersion = 1).copy(enable = true, needsApproval = false)

        val merged = mergeMcpTools(
            storedTools = listOf(stored),
            discoveredTools = listOf(tool(name = "search", schemaVersion = 1)),
        )

        assertTrue(merged.single().enable)
        assertFalse(merged.single().needsApproval)
    }

    @Test
    fun `schema changes revoke prior permission`() {
        val stored = tool(name = "write_file", schemaVersion = 1).copy(enable = true, needsApproval = false)

        val merged = mergeMcpTools(
            storedTools = listOf(stored),
            discoveredTools = listOf(tool(name = "write_file", schemaVersion = 2)),
        )

        assertFalse(merged.single().enable)
        assertTrue(merged.single().needsApproval)
    }

    private fun tool(name: String, schemaVersion: Int): McpTool = McpTool(
        name = name,
        description = "Tool $name",
        inputSchema = InputSchema.Obj(
            properties = buildJsonObject { put("schemaVersion", schemaVersion) },
        ),
    )
}
