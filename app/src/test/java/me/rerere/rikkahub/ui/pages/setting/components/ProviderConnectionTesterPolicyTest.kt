package me.rerere.rikkahub.ui.pages.setting.components

import kotlin.uuid.Uuid
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionTesterPolicyTest {
    private val providerId = Uuid.random()
    private val modelId = Uuid.random()
    private val headerId = Uuid.random()
    private val overrideId = Uuid.random()

    private val persisted = ProviderSetting.OpenAI(
        id = providerId,
        name = "Persisted",
        apiKey = "persisted-key",
        baseUrl = "https://api.example.test/v1",
        models = listOf(
            Model(
                id = modelId,
                modelId = "chat-model",
                displayName = "Chat model",
                customHeaders = listOf(CustomHeader("Authorization", "Bearer persisted", headerId)),
                providerOverwrite = ProviderSetting.OpenAI(
                    id = overrideId,
                    name = "Override",
                    apiKey = "override-key",
                    baseUrl = "https://override.example.test/v1",
                ),
            ),
        ),
    )

    @Test
    fun `exact persisted snapshot is allowed`() {
        assertTrue(providerDraftMatchesPersisted(persisted, persisted.copy()))
    }

    @Test
    fun `same audience unsaved api key is blocked`() {
        assertFalse(providerDraftMatchesPersisted(persisted.copy(apiKey = "unsaved-key"), persisted))
    }

    @Test
    fun `unsaved model custom credential is blocked`() {
        val draft = persisted.copy(
            models = persisted.models.map { model ->
                model.copy(customHeaders = listOf(CustomHeader("Authorization", "Bearer draft", headerId)))
            },
        )
        assertFalse(providerDraftMatchesPersisted(draft, persisted))
    }

    @Test
    fun `unsaved provider override audience or key is blocked`() {
        val draft = persisted.copy(
            models = persisted.models.map { model ->
                model.copy(
                    providerOverwrite = (model.providerOverwrite as ProviderSetting.OpenAI).copy(
                        apiKey = "draft-override-key",
                        baseUrl = "https://new-override.example.test/v1",
                    ),
                )
            },
        )
        assertFalse(providerDraftMatchesPersisted(draft, persisted))
    }

    @Test
    fun `missing durable provider is blocked`() {
        assertFalse(providerDraftMatchesPersisted(persisted, null))
    }
}
