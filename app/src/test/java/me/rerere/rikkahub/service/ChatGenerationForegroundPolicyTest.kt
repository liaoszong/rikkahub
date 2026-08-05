package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationForegroundPolicyTest {
    @Test
    fun `send without answer remains a local persistence operation`() {
        assertFalse(ChatGenerationForegroundPolicy.requiresForSend(answer = false))
        assertTrue(ChatGenerationForegroundPolicy.requiresForSend(answer = true))
    }

    @Test
    fun `regeneration without provider work does not reserve foreground service`() {
        assertFalse(
            ChatGenerationForegroundPolicy.requiresForRegeneration(
                messageRole = MessageRole.ASSISTANT,
                regenerateAssistantMessage = false,
            )
        )
        assertTrue(
            ChatGenerationForegroundPolicy.requiresForRegeneration(
                messageRole = MessageRole.USER,
                regenerateAssistantMessage = false,
            )
        )
        assertTrue(
            ChatGenerationForegroundPolicy.requiresForRegeneration(
                messageRole = MessageRole.ASSISTANT,
                regenerateAssistantMessage = true,
            )
        )
    }
}
