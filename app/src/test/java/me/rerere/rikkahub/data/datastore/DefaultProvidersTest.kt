package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DefaultProvidersTest {
    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }

    @Test
    fun `palenik defaults classify image models separately from chat models`() {
        val provider = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == PALENIK_PROVIDER_ID }

        assertEquals(PALENIK_BASE_URL, provider.baseUrl)
        assertTrue(provider.builtIn)
        assertEquals(12, provider.models.size)
        assertEquals(ModelType.IMAGE, provider.models.single { it.modelId == "gpt-image-2" }.type)
        assertEquals(ModelType.IMAGE, provider.models.single { it.modelId == "gpt-image-1" }.type)
        assertEquals(ModelType.CHAT, provider.models.single { it.modelId == "gpt-5.6" }.type)
    }

    @Test
    fun `image model fallback prefers newest configured image model`() {
        val provider = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == PALENIK_PROVIDER_ID }
            .copy(enabled = true, apiKey = "configured")
        val settings = Settings(
            providers = listOf(provider),
            imageGenerationModelId = Uuid.random(),
        )

        assertEquals("gpt-image-2", settings.resolveImageGenerationModel()?.modelId)
    }

    @Test
    fun `background model fallback avoids auto and uses configured fast chat model`() {
        val provider = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == PALENIK_PROVIDER_ID }
            .copy(enabled = true, apiKey = "configured")
        val settings = Settings(
            providers = listOf(provider),
            chatModelId = DEFAULT_AUTO_MODEL_ID,
            fastModelId = DEFAULT_AUTO_MODEL_ID,
        )

        assertEquals(
            "gpt-5.4-mini",
            settings.resolveBackgroundTextModel(preferredId = null, fallbackId = settings.fastModelId)?.modelId,
        )
    }
}
