package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.model.CapabilityOverride
import me.rerere.ai.model.CapabilitySetOverride
import me.rerere.ai.model.ModelFeature
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeProviderThinkingTest {
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    @Test
    fun `opus 4_8 uses adaptive thinking and effort`() {
        val request = buildRequest("claude-opus-4-8", ReasoningLevel.XHIGH)

        assertEquals("adaptive", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", request["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
        assertEquals("xhigh", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertNull(request["thinking"]!!.jsonObject["budget_tokens"])
    }

    @Test
    fun `sonnet 4_6 maps xhigh to supported high effort`() {
        val request = buildRequest("claude-sonnet-4-6", ReasoningLevel.XHIGH)

        assertEquals("adaptive", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy claude uses bounded manual thinking budget`() {
        val request = buildRequest("claude-3-7-sonnet-latest", ReasoningLevel.LOW, maxTokens = 4_096)

        assertEquals("enabled", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1_024, request["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(request["output_config"])
    }

    @Test
    fun `fable off omits thinking instead of sending rejected disabled mode`() {
        val request = buildRequest("claude-fable-5", ReasoningLevel.OFF)

        assertNull(request["thinking"])
        assertNull(request["temperature"])
        assertNull(request["top_p"])
    }

    @Test
    fun `legacy off omits thinking and keeps sampling controls`() {
        val request = buildRequest("claude-3-5-sonnet-latest", ReasoningLevel.OFF)

        assertNull(request["thinking"])
        assertTrue(request.containsKey("temperature"))
        assertTrue(request.containsKey("top_p"))
    }

    @Test
    fun `modern adaptive models never receive legacy budget`() {
        listOf("claude-opus-4-6", "claude-opus-4-7", "claude-sonnet-5").forEach { modelId ->
            val request = buildRequest(modelId, ReasoningLevel.HIGH)
            assertFalse(request["thinking"]!!.jsonObject.containsKey("budget_tokens"))
        }
    }

    @Test
    fun `capability override can disable reasoning request fields`() {
        val request = buildRequest(
            modelId = "claude-opus-4-8",
            reasoningLevel = ReasoningLevel.HIGH,
            capabilityOverride = CapabilityOverride(
                features = CapabilitySetOverride(remove = setOf(ModelFeature.REASONING))
            ),
        )

        assertNull(request["thinking"])
        assertNull(request["output_config"])
    }

    private fun buildRequest(
        modelId: String,
        reasoningLevel: ReasoningLevel,
        maxTokens: Int = 16_000,
        capabilityOverride: CapabilityOverride? = null,
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        method.isAccessible = true
        return method.invoke(
            provider,
            ProviderSetting.Claude(promptCaching = false),
            listOf(UIMessage.user("hello")),
            TextGenerationParams(
                model = Model(
                    modelId = modelId,
                    abilities = listOf(ModelAbility.REASONING),
                    capabilityOverride = capabilityOverride,
                ),
                reasoningLevel = reasoningLevel,
                maxTokens = maxTokens,
                temperature = 0.7f,
                topP = 0.9f,
            ),
            false,
        ) as JsonObject
    }
}
