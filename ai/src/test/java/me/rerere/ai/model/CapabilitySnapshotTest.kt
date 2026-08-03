package me.rerere.ai.model

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitySnapshotTest {
    @Test
    fun `legacy image model derives generation editing and image api surface`() {
        val snapshot = CapabilitySnapshotResolver.fromLegacyModel(
            Model(
                modelId = "gpt-image-2",
                type = ModelType.IMAGE,
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                outputModalities = listOf(Modality.IMAGE),
            )
        )

        assertEquals(setOf(CapabilityMedia.TEXT, CapabilityMedia.IMAGE), snapshot.inputMedia)
        assertEquals(setOf(CapabilityMedia.IMAGE), snapshot.outputMedia)
        assertTrue(ModelFeature.IMAGE_GENERATION in snapshot.features)
        assertTrue(ModelFeature.IMAGE_EDITING in snapshot.features)
        assertEquals(setOf(ApiSurface.IMAGE_GENERATIONS), snapshot.apiSurfaces)
    }

    @Test
    fun `legacy chat model retains tool reasoning and built in image generation`() {
        val snapshot = CapabilitySnapshotResolver.fromLegacyModel(
            Model(
                modelId = "multimodal-chat",
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                tools = setOf(BuiltInTools.ImageGeneration),
            )
        )

        assertTrue(ModelFeature.TOOL_CALLING in snapshot.features)
        assertTrue(ModelFeature.REASONING in snapshot.features)
        assertTrue(ModelFeature.IMAGE_GENERATION in snapshot.features)
        assertFalse(ModelFeature.IMAGE_EDITING in snapshot.features)
        assertTrue(CapabilityMedia.IMAGE in snapshot.outputMedia)
        assertEquals(setOf(ApiSurface.CHAT_COMPLETIONS), snapshot.apiSurfaces)
    }

    @Test
    fun `override inherits replaces adds and removes with removal winning`() {
        val base = CapabilitySnapshot(
            inputMedia = setOf(CapabilityMedia.TEXT, CapabilityMedia.IMAGE),
            outputMedia = setOf(CapabilityMedia.TEXT),
            features = setOf(ModelFeature.TOOL_CALLING, ModelFeature.REASONING),
            apiSurfaces = setOf(ApiSurface.CHAT_COMPLETIONS),
            origin = CapabilityOrigin.PROVIDER_DECLARED,
        )
        val override = CapabilityOverride(
            outputMedia = CapabilitySetOverride(replace = emptySet()),
            features = CapabilitySetOverride(
                add = setOf(ModelFeature.IMAGE_GENERATION),
                remove = setOf(ModelFeature.REASONING, ModelFeature.IMAGE_GENERATION),
            ),
            apiSurfaces = CapabilitySetOverride(replace = setOf(ApiSurface.RESPONSES)),
        )

        val merged = CapabilitySnapshotResolver.merge(base, override)

        assertEquals(base.inputMedia, merged.inputMedia)
        assertTrue(merged.outputMedia.isEmpty())
        assertEquals(setOf(ModelFeature.TOOL_CALLING), merged.features)
        assertEquals(setOf(ApiSurface.RESPONSES), merged.apiSurfaces)
        assertEquals(CapabilityOrigin.MERGED, merged.origin)
    }

    @Test
    fun `snapshot and override have stable serializable contracts`() {
        val json = Json { encodeDefaults = true }
        val snapshot = CapabilitySnapshot(
            outputMedia = setOf(CapabilityMedia.TEXT, CapabilityMedia.IMAGE),
            features = setOf(ModelFeature.IMAGE_GENERATION),
            apiSurfaces = setOf(ApiSurface.RESPONSES),
        )
        val override = CapabilityOverride(
            apiSurfaces = CapabilitySetOverride(add = setOf(ApiSurface.IMAGE_GENERATIONS))
        )

        assertEquals(snapshot, json.decodeFromString<CapabilitySnapshot>(json.encodeToString(snapshot)))
        assertEquals(override, json.decodeFromString<CapabilityOverride>(json.encodeToString(override)))
    }

    @Test
    fun `effective resolution applies provider declaration before user override`() {
        val model = Model(
            modelId = "gpt-test",
            declaredCapabilities = CapabilitySnapshot(
                features = setOf(ModelFeature.TOOL_CALLING),
                apiSurfaces = setOf(ApiSurface.CHAT_COMPLETIONS),
            ),
            capabilityOverride = CapabilityOverride(
                features = CapabilitySetOverride(remove = setOf(ModelFeature.WEB_SEARCH)),
                apiSurfaces = CapabilitySetOverride(replace = emptySet()),
            ),
        )

        val effective = model.effectiveCapabilitySnapshot(
            ProviderSetting.OpenAI(useResponseApi = true)
        )

        assertEquals(setOf(ModelFeature.TOOL_CALLING, ModelFeature.IMAGE_GENERATION), effective.features)
        assertTrue(effective.apiSurfaces.isEmpty())
        assertEquals(CapabilityOrigin.MERGED, effective.origin)
    }

    @Test
    fun `provider declarations expose concrete api surfaces and adapter media`() {
        val imageModel = Model(
            modelId = "image-test",
            type = ModelType.IMAGE,
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            outputModalities = listOf(Modality.IMAGE),
        )
        val openAiImage = imageModel.effectiveCapabilitySnapshot(ProviderSetting.OpenAI())
        val googleChat = Model(modelId = "gemini-test")
            .effectiveCapabilitySnapshot(ProviderSetting.Google())
        val claudeChat = Model(modelId = "claude-test")
            .effectiveCapabilitySnapshot(ProviderSetting.Claude())

        assertEquals(
            setOf(ApiSurface.IMAGE_GENERATIONS, ApiSurface.IMAGE_EDITS),
            openAiImage.apiSurfaces,
        )
        assertTrue(CapabilityMedia.AUDIO in googleChat.inputMedia)
        assertTrue(CapabilityMedia.VIDEO in googleChat.inputMedia)
        assertEquals(setOf(ApiSurface.GENERATE_CONTENT), googleChat.apiSurfaces)
        assertTrue(ModelFeature.WEB_SEARCH in googleChat.features)
        assertTrue(ModelFeature.URL_CONTEXT in googleChat.features)
        assertEquals(setOf(ApiSurface.MESSAGES), claudeChat.apiSurfaces)
    }

    @Test
    fun `old serialized model remains readable without capability fields`() {
        val decoded = Json.decodeFromString<Model>("""{"modelId":"legacy-model"}""")

        assertEquals(null, decoded.declaredCapabilities)
        assertEquals(null, decoded.capabilityOverride)
        assertEquals(setOf(CapabilityMedia.TEXT), decoded.effectiveCapabilitySnapshot().inputMedia)
    }

    @Test
    fun `future schema versions fail closed during merge`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CapabilitySnapshotResolver.merge(
                CapabilitySnapshot(schemaVersion = CapabilitySnapshot.CURRENT_SCHEMA_VERSION + 1),
                CapabilityOverride(features = CapabilitySetOverride(add = setOf(ModelFeature.REASONING))),
            )
        }

        assertTrue(error.message.orEmpty().contains("Unsupported capability snapshot schema version"))
    }

    @Test
    fun `text surface resolver mirrors provider transport fallback`() {
        val dualSurfaceModel = Model(
            modelId = "dual",
            declaredCapabilities = CapabilitySnapshot(
                apiSurfaces = setOf(ApiSurface.CHAT_COMPLETIONS, ApiSurface.RESPONSES),
            ),
        )

        assertEquals(
            ApiSurface.CHAT_COMPLETIONS,
            dualSurfaceModel.resolveTextApiSurface(ProviderSetting.OpenAI(useResponseApi = false)),
        )
        assertEquals(
            ApiSurface.RESPONSES,
            dualSurfaceModel.resolveTextApiSurface(ProviderSetting.OpenAI(useResponseApi = true)),
        )

        val responsesOnly = dualSurfaceModel.copy(
            capabilityOverride = CapabilityOverride(
                apiSurfaces = CapabilitySetOverride(replace = setOf(ApiSurface.RESPONSES)),
            ),
        )
        assertEquals(
            ApiSurface.RESPONSES,
            responsesOnly.resolveTextApiSurface(ProviderSetting.OpenAI(useResponseApi = false)),
        )
        assertEquals(
            ApiSurface.GENERATE_CONTENT,
            Model(modelId = "gemini").resolveTextApiSurface(ProviderSetting.Google()),
        )
        assertEquals(
            ApiSurface.MESSAGES,
            Model(modelId = "claude").resolveTextApiSurface(ProviderSetting.Claude()),
        )
    }
}
