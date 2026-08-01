package me.rerere.ai.model

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
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
    fun `future schema versions fail closed during merge`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CapabilitySnapshotResolver.merge(
                CapabilitySnapshot(schemaVersion = CapabilitySnapshot.CURRENT_SCHEMA_VERSION + 1),
                CapabilityOverride(features = CapabilitySetOverride(add = setOf(ModelFeature.REASONING))),
            )
        }

        assertTrue(error.message.orEmpty().contains("Unsupported capability snapshot schema version"))
    }
}
