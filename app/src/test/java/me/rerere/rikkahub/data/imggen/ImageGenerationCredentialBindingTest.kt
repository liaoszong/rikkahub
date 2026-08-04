package me.rerere.rikkahub.data.imggen

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.credential.CredentialSlotId
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageGenerationCredentialBindingTest {
    @Test
    fun `frozen provider ref executes with old key after settings rotate`(): Unit = runBlocking {
        val original = provider(apiKey = "key-A")
        val model = original.models.single()
        val evidence = providerEvidence(original, "vault:v1:00000000-0000-0000-0000-0000000000a1")
        val current = original.copy(apiKey = "key-B")
        val request = request(
            model = model,
            provider = original,
            evidence = evidence,
        )

        val execution = resolveImageGenerationExecutionSettings(
            settings = Settings(providers = listOf(current)),
            request = request,
            resolveSecret = { "key-A" },
        )

        assertEquals("key-A", (execution.provider as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `endpoint change fails closed before provider execution`(): Unit = runBlocking {
        val original = provider(apiKey = "key-A")
        val model = original.models.single()
        val evidence = providerEvidence(original, "vault:v1:00000000-0000-0000-0000-0000000000a2")
        val moved = original.copy(apiKey = "key-B", baseUrl = "https://other.example/v1")

        val failure = assertThrows(ImageGenerationException::class.java) {
            runBlocking {
                resolveImageGenerationExecutionSettings(
                    settings = Settings(providers = listOf(moved)),
                    request = request(model, original, evidence),
                    resolveSecret = { "key-A" },
                )
            }
        }

        assertEquals(ImageGenerationFailureKind.CONFIGURATION, failure.kind)
    }

    @Test
    fun `frozen custom auth header replaces only matching stable header`(): Unit = runBlocking {
        val headerId = Uuid.parse("00000000-0000-0000-0000-0000000000b1")
        val original = provider(
            apiKey = "fallback",
            header = CustomHeader("Authorization", "Bearer A", headerId),
        )
        val currentModel = original.models.single().copy(
            customHeaders = listOf(CustomHeader("Authorization", "Bearer B", headerId)),
        )
        val current = original.copy(models = listOf(currentModel))
        val reference = "vault:v1:00000000-0000-0000-0000-0000000000b2"
        val evidence = ImageGenerationCredentialEvidence(
            reference = reference,
            namespace = "settings.providers",
            ownerStableId = headerId.toString(),
            fieldSlot = "header.value",
            kind = "header",
            target = ImageGenerationCredentialTarget.MODEL_CUSTOM_HEADER,
            customHeaderId = headerId.toString(),
        )

        val execution = resolveImageGenerationExecutionSettings(
            settings = Settings(providers = listOf(current)),
            request = request(original.models.single(), original, evidence),
            resolveSecret = { "Bearer A" },
        )

        assertEquals("Bearer A", execution.model.customHeaders.single().value)
        assertEquals("fallback", (execution.provider as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `request without frozen credential cannot silently pick up a new key`(): Unit = runBlocking {
        val anonymous = provider(apiKey = "")
        val current = anonymous.copy(apiKey = "key-B")

        assertThrows(ImageGenerationException::class.java) {
            runBlocking {
                resolveImageGenerationExecutionSettings(
                    settings = Settings(providers = listOf(current)),
                    request = request(anonymous.models.single(), anonymous, evidence = null),
                    resolveSecret = { error("resolver must not be called") },
                )
            }
        }
    }

    @Test
    fun `freeze chooses stable provider slot without copying secret`(): Unit {
        val provider = provider(apiKey = "key-A")
        val owner = "openai:${provider.id}"
        val slot = CredentialSlotId.of("settings.providers", owner, "apikey").value
        val reference = "vault:v1:00000000-0000-0000-0000-0000000000c1"
        val settings = Settings(
            providers = listOf(provider),
            credentialReferencesBySlot = mapOf(slot to reference),
        )

        val evidence = settings.freezeImageGenerationCredential(provider, provider.models.single())

        assertEquals(reference, evidence?.reference)
        assertEquals(ImageGenerationCredentialTarget.PROVIDER_API_KEY, evidence?.target)
    }

    private fun provider(
        apiKey: String,
        header: CustomHeader? = null,
    ): ProviderSetting.OpenAI {
        val providerId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val model = Model(
            id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
            modelId = "gpt-image-1",
            displayName = "Image",
            type = ModelType.IMAGE,
            customHeaders = listOfNotNull(header),
        )
        return ProviderSetting.OpenAI(
            id = providerId,
            name = "Images",
            models = listOf(model),
            apiKey = apiKey,
            baseUrl = "https://images.example/v1",
        )
    }

    private fun providerEvidence(provider: ProviderSetting.OpenAI, reference: String) =
        ImageGenerationCredentialEvidence(
            reference = reference,
            namespace = "settings.providers",
            ownerStableId = "openai:${provider.id}",
            fieldSlot = "apikey",
            kind = "secret",
            target = ImageGenerationCredentialTarget.PROVIDER_API_KEY,
        )

    private fun request(
        model: Model,
        provider: ProviderSetting,
        evidence: ImageGenerationCredentialEvidence?,
    ) = ImageGenerationRequest(
        requestId = "request-1",
        prompt = "draw",
        modelId = model.id.toString(),
        modelName = model.displayName,
        providerId = provider.id.toString(),
        credentialEvidence = evidence,
        transportConfigurationDigest = imageTransportConfigurationDigest(model, provider),
        size = "1024x1024",
        numberOfImages = 1,
    )
}
