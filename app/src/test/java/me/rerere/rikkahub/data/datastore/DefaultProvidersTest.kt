package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
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
        assertEquals(PALENIK_MANAGED_BY, provider.managedBy)
        assertTrue(provider.builtIn)
        assertEquals(12, provider.models.size)
        assertEquals(ModelType.IMAGE, provider.models.single { it.modelId == "gpt-image-2" }.type)
        assertEquals(ModelType.IMAGE, provider.models.single { it.modelId == "gpt-image-1" }.type)
        assertEquals(ModelType.CHAT, provider.models.single { it.modelId == "gpt-5.6" }.type)
    }

    @Test
    fun `canonical palenik id migrates to stable managed identity`() {
        val legacy = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == PALENIK_PROVIDER_ID }
            .copy(
                managedBy = null,
                apiKey = "kept-secret",
                models = listOf(Model(modelId = "user-model", displayName = "User model")),
            )

        val merged = mergeDefaultProviders(listOf(legacy))
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == PALENIK_PROVIDER_ID }

        assertEquals(PALENIK_MANAGED_BY, merged.managedBy)
        assertEquals("kept-secret", merged.apiKey)
        assertTrue(merged.models.any { it.modelId == "user-model" })
        assertTrue(merged.models.any { it.modelId == "gpt-image-2" })
    }

    @Test
    fun `matching base url alone never claims a custom provider`() {
        val custom = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "My PaleInk-compatible gateway",
            baseUrl = PALENIK_BASE_URL,
            apiKey = "custom-key",
            models = listOf(Model(modelId = "custom-model")),
        )

        val merged = mergeDefaultProviders(listOf(custom))
        val preserved = merged.filterIsInstance<ProviderSetting.OpenAI>().single { it.id == custom.id }

        assertEquals(null, preserved.managedBy)
        assertEquals("My PaleInk-compatible gateway", preserved.name)
        assertEquals(listOf("custom-model"), preserved.models.map { it.modelId })
        assertTrue(merged.any { it.id == PALENIK_PROVIDER_ID })
    }

    @Test
    fun `canonical palenik wins and absorbs managed duplicate exactly once`() {
        val canonical = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == PALENIK_PROVIDER_ID }
            .copy(apiKey = "canonical-key")
        val duplicate = canonical.copy(
            id = Uuid.random(),
            apiKey = "duplicate-key",
            models = listOf(Model(modelId = "duplicate-custom-model")),
        )

        val merged = mergeDefaultProviders(listOf(duplicate, canonical))
        val managed = merged.filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.managedBy == PALENIK_MANAGED_BY }

        assertEquals(1, managed.size)
        assertEquals(PALENIK_PROVIDER_ID, managed.single().id)
        assertEquals("canonical-key", managed.single().apiKey)
        assertTrue(managed.single().models.any { it.modelId == "duplicate-custom-model" })
    }

    @Test
    fun `readiness reports model header authentication without provider api key`() {
        val model = Model(
            modelId = "header-auth-model",
            customHeaders = listOf(CustomHeader("Authorization", "Bearer external-token")),
        )
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://gateway.example/v1",
            apiKey = "",
            models = listOf(model),
        )

        assertEquals(
            ProviderReadiness(true, ProviderCredentialSource.MODEL_HEADER),
            provider.requestReadiness(model),
        )
    }

    @Test
    fun `ordinary model headers do not masquerade as credentials`() {
        val model = Model(
            modelId = "non-auth-header-model",
            customHeaders = listOf(CustomHeader("User-Agent", "RikkaHub")),
        )
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://gateway.example/v1",
            apiKey = "",
            models = listOf(model),
        )

        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            provider.requestReadiness(model),
        )
    }

    @Test
    fun `readiness explicitly supports local unauthenticated endpoints`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "http://192.168.1.25:11434/v1",
            apiKey = "",
        )

        assertEquals(
            ProviderReadiness(true, ProviderCredentialSource.LOCAL_NO_AUTH),
            provider.requestReadiness(),
        )
    }

    @Test
    fun `readiness diagnoses missing credentials for remote endpoint`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://gateway.example/v1",
            apiKey = "",
        )

        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            provider.requestReadiness(),
        )
    }

    @Test
    fun `readiness ignores unsupported custom header names`() {
        val model = Model(
            modelId = "not-authenticated",
            customHeaders = listOf(CustomHeader("X-Trace-Token", "not-a-credential")),
        )
        val provider = ProviderSetting.Google(apiKey = "", models = listOf(model))

        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            provider.requestReadiness(model),
        )
    }

    @Test
    fun `google and claude readiness accept adapter supported model credentials`() {
        val googleModel = Model(customHeaders = listOf(CustomHeader("x-goog-api-key", "google-key")))
        val claudeModel = Model(customHeaders = listOf(CustomHeader("x-api-key", "claude-key")))

        assertEquals(
            ProviderCredentialSource.MODEL_HEADER,
            ProviderSetting.Google(apiKey = "").requestReadiness(googleModel).credentialSource,
        )
        assertEquals(
            ProviderCredentialSource.MODEL_HEADER,
            ProviderSetting.Claude(apiKey = "").requestReadiness(claudeModel).credentialSource,
        )
    }

    @Test
    fun `google service account readiness requires vertex mode and complete identity`() {
        val complete = ProviderSetting.Google(
            apiKey = "",
            vertexAI = true,
            useServiceAccount = true,
            privateKey = "private-key",
            serviceAccountEmail = "service@example.test",
            projectId = "project-id",
            location = "us-central1",
        )

        assertEquals(
            ProviderReadiness(true, ProviderCredentialSource.SERVICE_ACCOUNT),
            complete.requestReadiness(),
        )

        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            complete.copy(privateKey = "").requestReadiness(),
        )
        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            complete.copy(serviceAccountEmail = "").requestReadiness(),
        )
        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            complete.copy(projectId = "").requestReadiness(),
        )
        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            complete.copy(location = "").requestReadiness(),
        )
        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            complete.copy(vertexAI = false).requestReadiness(),
        )
        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            complete.copy(useServiceAccount = false).requestReadiness(),
        )
    }

    @Test
    fun `incomplete enabled google service account does not fall back to unused api key`() {
        val provider = ProviderSetting.Google(
            apiKey = "otherwise-valid-key",
            vertexAI = true,
            useServiceAccount = true,
            privateKey = "",
            serviceAccountEmail = "service@example.test",
            projectId = "project-id",
            location = "us-central1",
        )

        assertEquals(
            ProviderReadiness(false, issue = ProviderReadinessIssue.MISSING_CREDENTIALS),
            provider.requestReadiness(),
        )
    }

    @Test
    fun `ipv6 loopback is recognized as local no auth endpoint`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "http://[::1]:11434/v1", apiKey = "")

        assertEquals(
            ProviderCredentialSource.LOCAL_NO_AUTH,
            provider.requestReadiness().credentialSource,
        )
    }

    @Test
    fun `background resolver accepts selected model header credentials`() {
        val model = Model(
            modelId = "header-auth-chat",
            customHeaders = listOf(CustomHeader("X-API-Key", "configured")),
        )
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            baseUrl = "https://gateway.example/v1",
            apiKey = "",
            models = listOf(model),
        )
        val settings = Settings(providers = listOf(provider), fastModelId = model.id)

        assertEquals("header-auth-chat", settings.resolveBackgroundTextModel(null, model.id)?.modelId)
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

    @Test
    fun `background model fallback stays within the explicitly selected provider`() {
        val defaults = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.id == PALENIK_PROVIDER_ID }
        val providerA = defaults.copy(
            id = Uuid.random(),
            name = "Provider A",
            enabled = true,
            apiKey = "configured-a",
            models = listOf(
                defaults.models.single { it.modelId == "gpt-image-2" }.copy(id = Uuid.random()),
                defaults.models.single { it.modelId == "gpt-5.6" }.copy(
                    id = Uuid.random(),
                    modelId = "provider-a-large",
                ),
            ),
        )
        val providerB = defaults.copy(
            id = Uuid.random(),
            name = "Provider B",
            enabled = true,
            apiKey = "configured-b",
            models = listOf(
                defaults.models.single { it.modelId == "gpt-5.4-mini" }.copy(id = Uuid.random()),
            ),
        )
        val settings = Settings(
            providers = listOf(providerA, providerB),
            titleModelId = providerA.models.first().id,
            fastModelId = providerB.models.single().id,
            chatModelId = providerB.models.single().id,
        )

        assertEquals(
            "provider-a-large",
            settings.resolveBackgroundTextModel(
                preferredId = settings.titleModelId,
                fallbackId = settings.fastModelId,
            )?.modelId,
        )
    }
}
