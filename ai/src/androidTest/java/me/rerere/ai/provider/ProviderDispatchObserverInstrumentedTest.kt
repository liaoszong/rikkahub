package me.rerere.ai.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.model.ApiSurface
import me.rerere.ai.model.CapabilityMedia
import me.rerere.ai.model.CapabilitySnapshot
import me.rerere.ai.model.ModelFeature
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that durable state is persisted before OkHttp can own a potentially billable request.
 * The observer deliberately fails and the transport interceptor must remain untouched.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDispatchObserverInstrumentedTest {
    private val transportAttempts = AtomicInteger()
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        transportAttempts.set(0)
        client = OkHttpClient.Builder()
            .addInterceptor {
                transportAttempts.incrementAndGet()
                error("transport must not be reached when dispatch persistence fails")
            }
            .build()
    }

    @Test
    fun openAiChatCompletionsBlocksSyncAndStreamBeforeTransport() {
        val api = ChatCompletionsAPI(client, KeyRoulette.default())
        val setting = ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1")

        assertBlocked { api.generateText(setting, emptyList(), textParams()) }
        assertBlocked { api.streamText(setting, emptyList(), textParams()).first() }
    }

    @Test
    fun openAiResponsesBlocksSyncAndStreamBeforeTransport() {
        val api = ResponseAPI(client, KeyRoulette.default())
        val setting = ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1")

        assertBlocked { api.generateText(setting, emptyList(), textParams()) }
        assertBlocked { api.streamText(setting, emptyList(), textParams()).first() }
    }

    @Test
    fun claudeBlocksSyncAndStreamBeforeTransport() {
        val provider = ClaudeProvider(client)
        val setting = ProviderSetting.Claude(apiKey = "test", baseUrl = "https://example.invalid/v1")

        assertBlocked { provider.generateText(setting, emptyList(), textParams()) }
        assertBlocked { provider.streamText(setting, emptyList(), textParams()).first() }
    }

    @Test
    fun googleBlocksSyncAndStreamBeforeTransport() {
        val provider = GoogleProvider(client)
        val setting = ProviderSetting.Google(apiKey = "test", baseUrl = "https://example.invalid/v1beta")

        assertBlocked { provider.generateText(setting, emptyList(), textParams()) }
        assertBlocked { provider.streamText(setting, emptyList(), textParams()).first() }
    }

    @Test
    fun openAiImageGenerationBlocksBeforeTransport() {
        val provider = OpenAIProvider(client)
        val setting = ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1")
        val params = ImageGenerationParams(
            model = Model(modelId = "test-image-model", type = ModelType.IMAGE),
            prompt = "test",
            dispatchObserver = blockingObserver,
        )

        assertBlocked { provider.generateImage(setting, params).first() }
    }

    @Test
    fun openAiImageEditBlocksBeforeTransport() {
        val provider = OpenAIProvider(client)
        val setting = ProviderSetting.OpenAI(apiKey = "test", baseUrl = "https://example.invalid/v1")
        val input = File.createTempFile(
            "dispatch-observer-",
            ".png",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        )
        try {
            val params = ImageEditParams(
                model = Model(
                    modelId = "test-image-model",
                    type = ModelType.IMAGE,
                    declaredCapabilities = CapabilitySnapshot(
                        inputMedia = setOf(CapabilityMedia.IMAGE),
                        outputMedia = setOf(CapabilityMedia.IMAGE),
                        features = setOf(ModelFeature.IMAGE_EDITING),
                        apiSurfaces = setOf(ApiSurface.IMAGE_EDITS),
                    ),
                ),
                prompt = "test",
                images = listOf(input.absolutePath),
                dispatchObserver = blockingObserver,
            )

            assertBlocked { provider.editImage(setting, params).first() }
        } finally {
            input.delete()
        }
    }

    private fun textParams() = TextGenerationParams(
        model = Model(modelId = "test-chat-model"),
        dispatchObserver = blockingObserver,
    )

    private fun assertBlocked(block: suspend () -> Unit) = runBlocking {
        var blocked = false
        try {
            block()
        } catch (_: DispatchPersistenceException) {
            blocked = true
        }
        assertTrue("dispatch observer was not invoked", blocked)
        assertEquals("transport was reached before durable dispatch", 0, transportAttempts.get())
    }

    private val blockingObserver = ProviderDispatchObserver {
        throw DispatchPersistenceException()
    }

    private class DispatchPersistenceException : RuntimeException()
}
