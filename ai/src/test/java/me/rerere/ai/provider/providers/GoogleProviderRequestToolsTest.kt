package me.rerere.ai.provider.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class GoogleProviderRequestToolsTest {
    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    @Test
    fun `request retains function declarations and built in tools in one array`() {
        val body = invokeBuildCompletionRequestBody(
            TextGenerationParams(
                model = Model(
                    modelId = "gemini-test",
                    abilities = listOf(ModelAbility.TOOL),
                    tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext),
                ),
                tools = listOf(testTool("lookup")),
            )
        )

        val tools = body["tools"]!!.jsonArray
        assertEquals(3, tools.size)
        assertEquals("lookup", tools[0].jsonObject["functionDeclarations"]
            ?.jsonArray?.single()?.jsonObject?.get("name")?.toString()?.trim('"'))
        assertTrue("googleSearch" in tools[1].jsonObject)
        assertTrue("urlContext" in tools[2].jsonObject)
    }

    @Test
    fun `unsupported built in tool fails explicitly instead of being dropped`() {
        val exception = assertThrows(InvocationTargetException::class.java) {
            invokeBuildCompletionRequestBody(
                TextGenerationParams(
                    model = Model(
                        modelId = "gemini-test",
                        abilities = listOf(ModelAbility.TOOL),
                        tools = setOf(BuiltInTools.ImageGeneration),
                    ),
                    tools = listOf(testTool("lookup")),
                )
            )
        }

        assertTrue(exception.cause is IllegalArgumentException)
        assertTrue(exception.cause?.message.orEmpty().contains("image_generation"))
    }

    private fun invokeBuildCompletionRequestBody(params: TextGenerationParams) =
        GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        ).run {
            isAccessible = true
            invoke(provider, listOf(UIMessage.user("hello")), params)
                as kotlinx.serialization.json.JsonObject
        }

    private fun testTool(name: String) = Tool(
        name = name,
        description = "Test tool",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { emptyList() },
    )
}
