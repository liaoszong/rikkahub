package me.rerere.ai.context

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ContextManifestTest {
    private val compiler = ShadowContextManifestCompiler()

    @Test
    fun `same input produces the same manifest identity`() {
        val messages = listOf(
            message("00000000-0000-0000-0000-000000000001", MessageRole.USER, "你好"),
            message("00000000-0000-0000-0000-000000000002", MessageRole.ASSISTANT, "你好，有什么可以帮你？"),
        )
        val input = manifestInput(
            source = messages,
            selected = messages,
            compiled = messages,
        )

        val first = compiler.compile(input)
        val second = compiler.compile(input)

        assertEquals(first, second)
        assertEquals(first.sourceDigest, second.sourceDigest)
        assertEquals(first.manifestId, second.manifestId)
        assertTrue(first.entries.all { it.disposition == ContextDisposition.INCLUDED })
    }

    @Test
    fun `legacy message limit is visible without changing source messages`() {
        val messages = listOf(
            message("00000000-0000-0000-0000-000000000011", MessageRole.USER, "old question"),
            message("00000000-0000-0000-0000-000000000012", MessageRole.ASSISTANT, "old answer"),
            message("00000000-0000-0000-0000-000000000013", MessageRole.USER, "current question"),
        )
        val selected = messages.takeLast(1)

        val manifest = compiler.compile(
            manifestInput(source = messages, selected = selected, compiled = selected)
        )

        assertEquals(3, messages.size)
        assertEquals(2, manifest.entries.count { it.disposition == ContextDisposition.EXCLUDED })
        assertEquals(
            listOf(ContextExclusionReason.MESSAGE_LIMIT, ContextExclusionReason.MESSAGE_LIMIT),
            manifest.entries.take(2).map(ContextEntry::exclusionReason),
        )
        assertEquals(ContextRequirement.REQUIRED, manifest.entries.last().requirement)
        assertTrue(manifest.excludedTokens > 0)
    }

    @Test
    fun `transform changes and generated system message are represented explicitly`() {
        val user = message(
            "00000000-0000-0000-0000-000000000021",
            MessageRole.USER,
            "raw input",
        )
        val transformedUser = user.copy(parts = listOf(UIMessagePart.Text("templated input")))
        val system = message(
            "00000000-0000-0000-0000-000000000022",
            MessageRole.SYSTEM,
            "system prompt",
        )

        val manifest = compiler.compile(
            manifestInput(
                source = listOf(user),
                selected = listOf(user),
                compiled = listOf(system, transformedUser),
            )
        )

        val userEntry = manifest.entries.single { it.sourceRef == "message:${user.id}" }
        assertEquals(ContextTransform.INPUT_TRANSFORMED, userEntry.transform)
        assertTrue(userEntry.originalTokenEstimate > 0)
        val generated = manifest.entries.single { it.transform == ContextTransform.GENERATED }
        assertEquals(ContextSemanticKind.SYSTEM_INSTRUCTION, generated.semanticKind)
        assertEquals(ContextTrustLevel.TRUSTED_HOST, generated.trustLevel)
        assertEquals(ContextRequirement.REQUIRED, generated.requirement)
    }

    @Test
    fun `selected message dropped by transformer has a distinct exclusion reason`() {
        val source = message(
            "00000000-0000-0000-0000-000000000031",
            MessageRole.USER,
            "drop me",
        )

        val manifest = compiler.compile(
            manifestInput(
                source = listOf(source),
                selected = listOf(source),
                compiled = emptyList(),
            )
        )

        assertEquals(ContextDisposition.EXCLUDED, manifest.entries.single().disposition)
        assertEquals(
            ContextExclusionReason.INPUT_TRANSFORMER_DROPPED,
            manifest.entries.single().exclusionReason,
        )
    }

    @Test
    fun `schema round trips and ignores future fields`() {
        val message = message(
            "00000000-0000-0000-0000-000000000041",
            MessageRole.USER,
            "round trip",
        )
        val manifest = compiler.compile(
            manifestInput(source = listOf(message), selected = listOf(message), compiled = listOf(message))
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(manifest)
        val withFutureField = encoded.dropLast(1) + ",\"futureField\":true}"

        assertEquals(manifest, json.decodeFromString<ContextManifest>(withFutureField))
    }

    private fun manifestInput(
        source: List<UIMessage>,
        selected: List<UIMessage>,
        compiled: List<UIMessage>,
    ) = ShadowContextManifestInput(
        requestRef = "conversation:c1:response:r1",
        capabilitySnapshotId = "capability-v1",
        selectorPolicy = "legacy-message-limit:10",
        sourceMessages = source,
        selectedMessages = selected,
        compiledMessages = compiled,
        reservedOutputTokens = 4_096,
    )

    private fun message(id: String, role: MessageRole, text: String) = UIMessage(
        id = Uuid.parse(id),
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
