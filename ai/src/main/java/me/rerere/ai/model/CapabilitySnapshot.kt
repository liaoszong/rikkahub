package me.rerere.ai.model

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting

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
    /** Null means the registry/provider has not supplied a verified limit. */
    val contextWindowTokens: Int? = null,
    /** Null means the registry/provider has not supplied a verified output limit. */
    val maxOutputTokens: Int? = null,
    /** Stable tokenizer/counting adapter name when one is available. */
    val tokenizerId: String? = null,
    val origin: CapabilityOrigin = CapabilityOrigin.INFERRED,
) {
    init {
        require(schemaVersion > 0) { "Capability snapshot schemaVersion must be positive" }
        require(contextWindowTokens == null || contextWindowTokens > 0)
        require(maxOutputTokens == null || maxOutputTokens > 0)
        require(
            contextWindowTokens == null || maxOutputTokens == null || maxOutputTokens <= contextWindowTokens
        )
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
    WEB_SEARCH,
    URL_CONTEXT,
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
    /** Null inherits the declared limit; a positive value is an explicit user correction. */
    val contextWindowTokens: Int? = null,
    /** Null inherits the declared limit; a positive value is an explicit user correction. */
    val maxOutputTokens: Int? = null,
) {
    init {
        require(schemaVersion > 0) { "Capability override schemaVersion must be positive" }
        require(contextWindowTokens == null || contextWindowTokens > 0)
        require(maxOutputTokens == null || maxOutputTokens > 0)
    }

    val isSpecified: Boolean
        get() = inputMedia.isSpecified || outputMedia.isSpecified || features.isSpecified || apiSurfaces.isSpecified ||
            contextWindowTokens != null || maxOutputTokens != null
}

object CapabilitySnapshotResolver {
    /**
     * The single runtime resolution path for model capabilities.
     *
     * Resolution order is deliberately stable:
     *
     * 1. an explicit registry/probe declaration, or the legacy compatibility adapter;
     * 2. the concrete provider/API-surface declaration;
     * 3. the user's replace -> add -> remove override.
     */
    fun effectiveCapabilitySnapshot(
        model: Model,
        providerSetting: ProviderSetting? = null,
    ): CapabilitySnapshot {
        val declared = model.declaredCapabilities
            ?.also { requireSupportedVersion(it.schemaVersion, "snapshot") }
            ?: fromLegacyModel(model)
        val providerDeclared = declareProviderCapabilities(
            base = declared,
            model = model,
            providerSetting = model.providerOverwrite ?: providerSetting,
        )
        return merge(providerDeclared, model.capabilityOverride)
    }

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
        if (BuiltInTools.Search in model.tools) features += ModelFeature.WEB_SEARCH
        if (BuiltInTools.UrlContext in model.tools) features += ModelFeature.URL_CONTEXT

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

        val contextWindowTokens = override.contextWindowTokens ?: base.contextWindowTokens
        val requestedMaxOutputTokens = override.maxOutputTokens ?: base.maxOutputTokens
        return CapabilitySnapshot(
            inputMedia = override.inputMedia.applyTo(base.inputMedia),
            outputMedia = override.outputMedia.applyTo(base.outputMedia),
            features = override.features.applyTo(base.features),
            apiSurfaces = override.apiSurfaces.applyTo(base.apiSurfaces),
            contextWindowTokens = contextWindowTokens,
            // A smaller user window implicitly caps output instead of producing an invalid model.
            maxOutputTokens = requestedMaxOutputTokens?.let { maxOutput ->
                contextWindowTokens?.let(maxOutput::coerceAtMost) ?: maxOutput
            },
            tokenizerId = base.tokenizerId,
            origin = CapabilityOrigin.MERGED,
        )
    }

    private fun declareProviderCapabilities(
        base: CapabilitySnapshot,
        model: Model,
        providerSetting: ProviderSetting?,
    ): CapabilitySnapshot {
        if (providerSetting == null) return base

        val inputMedia = when (providerSetting) {
            // The current Google adapter serializes these media types as inlineData.
            is ProviderSetting.Google -> base.inputMedia + setOf(
                CapabilityMedia.AUDIO,
                CapabilityMedia.VIDEO,
            )

            is ProviderSetting.OpenAI,
            is ProviderSetting.Claude -> base.inputMedia
        }
        val providerFeatures = when (providerSetting) {
            is ProviderSetting.OpenAI -> if (
                providerSetting.useResponseApi && model.type == ModelType.CHAT
            ) {
                base.features + setOf(ModelFeature.WEB_SEARCH, ModelFeature.IMAGE_GENERATION)
            } else {
                base.features
            }

            is ProviderSetting.Google -> if (model.type != ModelType.EMBEDDING) {
                base.features + setOf(ModelFeature.WEB_SEARCH, ModelFeature.URL_CONTEXT)
            } else {
                base.features
            }

            is ProviderSetting.Claude -> base.features
        }
        val apiSurfaces = when (providerSetting) {
            is ProviderSetting.OpenAI -> when (model.type) {
                ModelType.CHAT -> setOf(
                    if (providerSetting.useResponseApi) ApiSurface.RESPONSES
                    else ApiSurface.CHAT_COMPLETIONS
                )

                ModelType.IMAGE -> buildSet {
                    add(ApiSurface.IMAGE_GENERATIONS)
                    if (ModelFeature.IMAGE_EDITING in base.features) add(ApiSurface.IMAGE_EDITS)
                }

                ModelType.EMBEDDING -> setOf(ApiSurface.EMBEDDINGS)
            }

            is ProviderSetting.Google -> when (model.type) {
                ModelType.CHAT,
                ModelType.IMAGE -> setOf(ApiSurface.GENERATE_CONTENT)

                ModelType.EMBEDDING -> setOf(ApiSurface.EMBEDDINGS)
            }

            is ProviderSetting.Claude -> setOf(ApiSurface.MESSAGES)
        }

        return base.copy(
            inputMedia = inputMedia,
            features = providerFeatures,
            apiSurfaces = apiSurfaces,
            origin = CapabilityOrigin.PROVIDER_DECLARED,
        )
    }

    private fun requireSupportedVersion(version: Int, kind: String) {
        require(version == CapabilitySnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported capability $kind schema version $version; " +
                "supported=${CapabilitySnapshot.CURRENT_SCHEMA_VERSION}"
        }
    }
}

fun Model.effectiveCapabilitySnapshot(providerSetting: ProviderSetting? = null): CapabilitySnapshot =
    CapabilitySnapshotResolver.effectiveCapabilitySnapshot(this, providerSetting)

/**
 * Resolves the concrete text endpoint family used by the provider adapter.
 *
 * Request ledgers and transports must share this decision. Otherwise an OpenAI-compatible model
 * can be persisted as Chat Completions while the adapter actually dispatches Responses, making
 * recovery and diagnostics reason about a request that never existed.
 */
fun Model.resolveTextApiSurface(providerSetting: ProviderSetting): ApiSurface = when (providerSetting) {
    is ProviderSetting.OpenAI -> {
        val capabilities = effectiveCapabilitySnapshot(providerSetting)
        val supportsResponses = capabilities.supports(ApiSurface.RESPONSES)
        val supportsChatCompletions = capabilities.supports(ApiSurface.CHAT_COMPLETIONS)
        when {
            providerSetting.useResponseApi && supportsResponses -> ApiSurface.RESPONSES
            !providerSetting.useResponseApi && supportsChatCompletions -> ApiSurface.CHAT_COMPLETIONS
            supportsResponses && !supportsChatCompletions -> ApiSurface.RESPONSES
            supportsChatCompletions && !supportsResponses -> ApiSurface.CHAT_COMPLETIONS
            else -> error(
                "Model $modelId does not declare a usable OpenAI text API surface: " +
                    capabilities.apiSurfaces
            )
        }
    }

    is ProviderSetting.Google -> ApiSurface.GENERATE_CONTENT.also {
        require(effectiveCapabilitySnapshot(providerSetting).supports(it)) {
            "Model $modelId does not declare the Google text API surface"
        }
    }

    is ProviderSetting.Claude -> ApiSurface.MESSAGES.also {
        require(effectiveCapabilitySnapshot(providerSetting).supports(it)) {
            "Model $modelId does not declare the Claude text API surface"
        }
    }
}

fun CapabilitySnapshot.supports(feature: ModelFeature): Boolean = feature in features

fun CapabilitySnapshot.supports(surface: ApiSurface): Boolean = surface in apiSurfaces

fun CapabilitySnapshot.supportsInput(media: CapabilityMedia): Boolean = media in inputMedia

fun CapabilitySnapshot.supportsOutput(media: CapabilityMedia): Boolean = media in outputMedia

/** Compatibility view for legacy request encoders that still accept only text/image. */
fun Set<CapabilityMedia>.toLegacyModalities(): List<Modality> =
    listOf(Modality.TEXT, Modality.IMAGE).filter { it.toCapabilityMedia() in this }

/** Persist an explicit media choice without creating a second runtime truth in legacy fields. */
fun Model.withInputMediaCapabilities(media: Set<CapabilityMedia>): Model = copy(
    inputModalities = media.toLegacyModalities(),
    capabilityOverride = (capabilityOverride ?: CapabilityOverride()).copy(
        inputMedia = CapabilitySetOverride(replace = media),
    ),
)

fun Model.withOutputMediaCapabilities(media: Set<CapabilityMedia>): Model = copy(
    outputModalities = media.toLegacyModalities(),
    capabilityOverride = (capabilityOverride ?: CapabilityOverride()).copy(
        outputMedia = CapabilitySetOverride(replace = media),
    ),
)

fun Model.withFeatureCapabilities(features: Set<ModelFeature>): Model = copy(
    abilities = buildList {
        if (ModelFeature.TOOL_CALLING in features) add(ModelAbility.TOOL)
        if (ModelFeature.REASONING in features) add(ModelAbility.REASONING)
    },
    capabilityOverride = (capabilityOverride ?: CapabilityOverride()).copy(
        features = CapabilitySetOverride(replace = features),
    ),
)

private fun Modality.toCapabilityMedia(): CapabilityMedia = when (this) {
    Modality.TEXT -> CapabilityMedia.TEXT
    Modality.IMAGE -> CapabilityMedia.IMAGE
}
