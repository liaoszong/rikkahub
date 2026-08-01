package me.rerere.ai.model

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType

/**
 * A provider-neutral, serializable description of what a model can do.
 *
 * This contract intentionally lives beside (rather than inside) the legacy [Model] contract so
 * persisted model settings remain wire-compatible while capability-aware call sites migrate.
 */
@Serializable
data class CapabilitySnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val inputMedia: Set<CapabilityMedia> = setOf(CapabilityMedia.TEXT),
    val outputMedia: Set<CapabilityMedia> = setOf(CapabilityMedia.TEXT),
    val features: Set<ModelFeature> = emptySet(),
    val apiSurfaces: Set<ApiSurface> = emptySet(),
    val origin: CapabilityOrigin = CapabilityOrigin.INFERRED,
) {
    init {
        require(schemaVersion > 0) { "Capability snapshot schemaVersion must be positive" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
enum class CapabilityMedia {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    DOCUMENT,
}

@Serializable
enum class ModelFeature {
    IMAGE_GENERATION,
    IMAGE_EDITING,
    TOOL_CALLING,
    REASONING,
}

/** Endpoint families, not vendor names. Providers map these surfaces to their concrete URLs. */
@Serializable
enum class ApiSurface {
    CHAT_COMPLETIONS,
    RESPONSES,
    MESSAGES,
    GENERATE_CONTENT,
    IMAGE_GENERATIONS,
    IMAGE_EDITS,
    EMBEDDINGS,
}

@Serializable
enum class CapabilityOrigin {
    INFERRED,
    PROVIDER_DECLARED,
    USER_OVERRIDE,
    MERGED,
}

/**
 * Field override semantics are deterministic:
 *
 * 1. [replace] replaces the inferred/provider-declared set when non-null. An empty set therefore
 *    explicitly disables every value in that field.
 * 2. [add] is applied next.
 * 3. [remove] is applied last and wins if a value is present in both add and remove.
 *
 * When [replace] is null and add/remove are empty, the original set is inherited unchanged.
 */
@Serializable
data class CapabilitySetOverride<T>(
    val replace: Set<T>? = null,
    val add: Set<T> = emptySet(),
    val remove: Set<T> = emptySet(),
) {
    fun applyTo(base: Set<T>): Set<T> = buildSet {
        addAll(replace ?: base)
        addAll(add)
        removeAll(remove)
    }

    val isSpecified: Boolean
        get() = replace != null || add.isNotEmpty() || remove.isNotEmpty()
}

@Serializable
data class CapabilityOverride(
    val schemaVersion: Int = CapabilitySnapshot.CURRENT_SCHEMA_VERSION,
    val inputMedia: CapabilitySetOverride<CapabilityMedia> = CapabilitySetOverride(),
    val outputMedia: CapabilitySetOverride<CapabilityMedia> = CapabilitySetOverride(),
    val features: CapabilitySetOverride<ModelFeature> = CapabilitySetOverride(),
    val apiSurfaces: CapabilitySetOverride<ApiSurface> = CapabilitySetOverride(),
) {
    init {
        require(schemaVersion > 0) { "Capability override schemaVersion must be positive" }
    }

    val isSpecified: Boolean
        get() = inputMedia.isSpecified || outputMedia.isSpecified || features.isSpecified || apiSurfaces.isSpecified
}

object CapabilitySnapshotResolver {
    /**
     * Bridges today's persisted [Model] fields into the versioned capability contract.
     * API surface inference is deliberately conservative and may be replaced by provider/user
     * declarations through [merge].
     */
    fun fromLegacyModel(model: Model): CapabilitySnapshot {
        val inputMedia = model.inputModalities.mapTo(linkedSetOf(), Modality::toCapabilityMedia)
        val outputMedia = model.outputModalities.mapTo(linkedSetOf(), Modality::toCapabilityMedia)
        val features = linkedSetOf<ModelFeature>()

        if (ModelAbility.TOOL in model.abilities) features += ModelFeature.TOOL_CALLING
        if (ModelAbility.REASONING in model.abilities) features += ModelFeature.REASONING

        val generatesImages = model.type == ModelType.IMAGE ||
            Modality.IMAGE in model.outputModalities ||
            BuiltInTools.ImageGeneration in model.tools
        if (generatesImages) {
            features += ModelFeature.IMAGE_GENERATION
            outputMedia += CapabilityMedia.IMAGE
        }
        if (generatesImages && CapabilityMedia.IMAGE in inputMedia) {
            features += ModelFeature.IMAGE_EDITING
        }

        val apiSurfaces = when (model.type) {
            ModelType.CHAT -> setOf(ApiSurface.CHAT_COMPLETIONS)
            ModelType.IMAGE -> setOf(ApiSurface.IMAGE_GENERATIONS)
            ModelType.EMBEDDING -> setOf(ApiSurface.EMBEDDINGS)
        }

        return CapabilitySnapshot(
            inputMedia = inputMedia,
            outputMedia = outputMedia,
            features = features,
            apiSurfaces = apiSurfaces,
            origin = CapabilityOrigin.INFERRED,
        )
    }

    /**
     * Merges an explicit override into a derived or provider-declared snapshot field by field.
     * Future schema versions fail closed instead of being interpreted with older semantics.
     */
    fun merge(base: CapabilitySnapshot, override: CapabilityOverride?): CapabilitySnapshot {
        requireSupportedVersion(base.schemaVersion, "snapshot")
        if (override == null || !override.isSpecified) return base
        requireSupportedVersion(override.schemaVersion, "override")

        return CapabilitySnapshot(
            inputMedia = override.inputMedia.applyTo(base.inputMedia),
            outputMedia = override.outputMedia.applyTo(base.outputMedia),
            features = override.features.applyTo(base.features),
            apiSurfaces = override.apiSurfaces.applyTo(base.apiSurfaces),
            origin = CapabilityOrigin.MERGED,
        )
    }

    private fun requireSupportedVersion(version: Int, kind: String) {
        require(version == CapabilitySnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported capability $kind schema version $version; " +
                "supported=${CapabilitySnapshot.CURRENT_SCHEMA_VERSION}"
        }
    }
}

private fun Modality.toCapabilityMedia(): CapabilityMedia = when (this) {
    Modality.TEXT -> CapabilityMedia.TEXT
    Modality.IMAGE -> CapabilityMedia.IMAGE
}
