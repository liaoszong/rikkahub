package me.rerere.ai.context

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * A rebuildable, provider-neutral account of the context selected for one provider request.
 *
 * A manifest is diagnostic evidence, not conversation truth and not a provider payload. It never
 * stores message content. [contentDigest] and [sourceDigest] make comparisons possible without
 * leaking prompts, tool output, attachment paths, or memory text into logs and diagnostics.
 */
@Serializable
data class ContextManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val compilerVersion: String,
    val manifestId: String,
    val requestRef: String,
    val mode: ContextManifestMode = ContextManifestMode.SHADOW,
    val capabilitySnapshotId: String,
    val selectorPolicy: String,
    val modelWindowTokens: Int? = null,
    val reservedOutputTokens: Int? = null,
    val safetyMarginTokens: Int = 0,
    val entries: List<ContextEntry>,
    val includedTokens: Long,
    val excludedTokens: Long,
    val sourceDigest: String,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported context manifest schema version $schemaVersion"
        }
        require(compilerVersion.isNotBlank()) { "compilerVersion is required" }
        require(manifestId.isNotBlank()) { "manifestId is required" }
        require(requestRef.isNotBlank()) { "requestRef is required" }
        require(capabilitySnapshotId.isNotBlank()) { "capabilitySnapshotId is required" }
        require(selectorPolicy.isNotBlank()) { "selectorPolicy is required" }
        require(modelWindowTokens == null || modelWindowTokens > 0)
        require(reservedOutputTokens == null || reservedOutputTokens >= 0)
        require(safetyMarginTokens >= 0)
        require(includedTokens >= 0)
        require(excludedTokens >= 0)
        require(
            includedTokens == entries
                .filter { it.disposition == ContextDisposition.INCLUDED }
                .sumOf(ContextEntry::compiledTokenEstimate)
        ) { "includedTokens must match included entries" }
        require(
            excludedTokens == entries
                .filter { it.disposition == ContextDisposition.EXCLUDED }
                .sumOf(ContextEntry::originalTokenEstimate)
        ) { "excludedTokens must match excluded entries" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class ContextEntry(
    val ordinal: Int,
    val sourceRef: String,
    val role: MessageRole,
    val semanticKind: ContextSemanticKind,
    val trustLevel: ContextTrustLevel,
    val requirement: ContextRequirement,
    val disposition: ContextDisposition,
    val transform: ContextTransform,
    val exclusionReason: ContextExclusionReason? = null,
    val originalTokenEstimate: Long,
    val compiledTokenEstimate: Long,
    val contentDigest: String,
    val evidenceRefs: List<String> = emptyList(),
    val citationRefs: List<String> = emptyList(),
) {
    init {
        require(ordinal >= 0)
        require(sourceRef.isNotBlank())
        require(originalTokenEstimate >= 0)
        require(compiledTokenEstimate >= 0)
        require(contentDigest.isNotBlank())
        require((disposition == ContextDisposition.EXCLUDED) == (exclusionReason != null)) {
            "Excluded entries require a reason and included entries must not have one"
        }
    }
}

@Serializable
enum class ContextManifestMode {
    /** Records current behaviour without controlling provider input. */
    SHADOW,

    /** Reserved for the later Context Engine cut-over. */
    AUTHORITATIVE,
}

@Serializable
enum class ContextSemanticKind {
    SYSTEM_INSTRUCTION,
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    TOOL_INTERACTION,
    DOCUMENT_INPUT,
    MEDIA_INPUT,
    TRANSFORM_OUTPUT,
    EPISODIC_SUMMARY,
}

@Serializable
enum class ContextTrustLevel {
    TRUSTED_HOST,
    USER_ASSERTED,
    MODEL_OUTPUT,
    TOOL_OUTPUT,
    EXTERNAL_CONTENT,
}

@Serializable
enum class ContextRequirement {
    REQUIRED,
    PREFERRED,
    COMPRESSIBLE,
    RETRIEVABLE,
}

@Serializable
enum class ContextDisposition {
    INCLUDED,
    EXCLUDED,
}

@Serializable
enum class ContextTransform {
    NONE,
    INPUT_TRANSFORMED,
    GENERATED,
}

@Serializable
enum class ContextExclusionReason {
    MESSAGE_LIMIT,
    TOKEN_BUDGET,
    INPUT_TRANSFORMER_DROPPED,
}

fun interface ContextTokenEstimator {
    fun estimate(message: UIMessage): Long
}

/**
 * A deterministic offline fallback. It is intentionally labelled as an estimate: provider token
 * counters will later replace it through the same interface. CJK and other non-ASCII characters
 * are weighted more conservatively than ASCII, while binary data URLs scale with their length.
 */
object ConservativeContextTokenEstimator : ContextTokenEstimator {
    override fun estimate(message: UIMessage): Long {
        val weightedCharacters = message.parts.sumOf(::weightedCharacters)
        return MESSAGE_OVERHEAD_TOKENS + (weightedCharacters + 3L) / 4L
    }

    @Suppress("DEPRECATION")
    private fun weightedCharacters(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Text -> part.text.weightedLength()
        is UIMessagePart.Reasoning -> part.reasoning.weightedLength()
        is UIMessagePart.ProviderOpaque -> part.payloadJson.weightedLength()
        is UIMessagePart.Image -> mediaWeight(part.url)
        is UIMessagePart.Video -> mediaWeight(part.url)
        is UIMessagePart.Audio -> mediaWeight(part.url)
        is UIMessagePart.Document -> mediaWeight(part.url) + part.fileName.weightedLength() +
            part.mime.length

        is UIMessagePart.Tool -> part.toolName.length.toLong() + part.input.weightedLength() +
            part.output.sumOf(::weightedCharacters)

        is UIMessagePart.ToolCall -> part.toolName.length.toLong() + part.arguments.weightedLength()
        is UIMessagePart.ToolResult -> part.toolName.length.toLong() + part.content.toString().weightedLength() +
            part.arguments.toString().weightedLength()

        UIMessagePart.Search -> MEDIA_REFERENCE_WEIGHT
    }

    private fun mediaWeight(value: String): Long = if (value.startsWith("data:")) {
        value.length.toLong()
    } else {
        MEDIA_REFERENCE_WEIGHT
    }

    private fun String.weightedLength(): Long = sumOf { character ->
        if (character.code <= 0x7f) 1L else 4L
    }

    private const val MESSAGE_OVERHEAD_TOKENS = 8L
    private const val MEDIA_REFERENCE_WEIGHT = 1_024L
}

data class ShadowContextManifestInput(
    val requestRef: String,
    val capabilitySnapshotId: String,
    val selectorPolicy: String,
    val sourceMessages: List<UIMessage>,
    val selectedMessages: List<UIMessage>,
    val compiledMessages: List<UIMessage>,
    val reservedOutputTokens: Int? = null,
    val modelWindowTokens: Int? = null,
    val safetyMarginTokens: Int = 0,
    val mode: ContextManifestMode = ContextManifestMode.SHADOW,
)

/**
 * Describes the legacy selector and transformer pipeline without changing either one.
 */
class ShadowContextManifestCompiler(
    private val tokenEstimator: ContextTokenEstimator = ConservativeContextTokenEstimator,
) {
    fun compile(input: ShadowContextManifestInput): ContextManifest {
        require(input.requestRef.isNotBlank())
        require(input.capabilitySnapshotId.isNotBlank())
        require(input.selectorPolicy.isNotBlank())

        val selectedIds = input.selectedMessages.mapTo(linkedSetOf(), UIMessage::id)
        val sourceIds = input.sourceMessages.mapTo(linkedSetOf(), UIMessage::id)
        val compiledById = input.compiledMessages.associateBy(UIMessage::id)
        val sourceDigests = input.sourceMessages.associate { it.id to it.contentDigest() }
        val compiledDigests = input.compiledMessages.associate { it.id to it.contentDigest() }
        val sourceTokenEstimates = input.sourceMessages.associate { it.id to tokenEstimator.estimate(it) }
        val compiledTokenEstimates = input.compiledMessages.associate { it.id to tokenEstimator.estimate(it) }
        val latestUserId = input.sourceMessages.lastOrNull { it.role == MessageRole.USER }?.id

        val entries = buildList {
            input.sourceMessages.forEachIndexed { ordinal, source ->
                val compiled = compiledById[source.id]
                val sourceDigest = sourceDigests.getValue(source.id)
                val selected = source.id in selectedIds
                val exclusionReason = when {
                    !selected -> ContextExclusionReason.MESSAGE_LIMIT
                    compiled == null -> ContextExclusionReason.INPUT_TRANSFORMER_DROPPED
                    else -> null
                }
                add(
                    ContextEntry(
                        ordinal = ordinal,
                        sourceRef = "message:${source.id}",
                        role = source.role,
                        semanticKind = source.semanticKind(),
                        trustLevel = source.trustLevel(),
                        requirement = source.requirement(latestUserId),
                        disposition = if (exclusionReason == null) {
                            ContextDisposition.INCLUDED
                        } else {
                            ContextDisposition.EXCLUDED
                        },
                        transform = when {
                            compiled == null || !selected -> ContextTransform.NONE
                            sourceDigest != compiledDigests.getValue(compiled.id) ->
                                ContextTransform.INPUT_TRANSFORMED

                            else -> ContextTransform.NONE
                        },
                        exclusionReason = if (
                            exclusionReason == ContextExclusionReason.MESSAGE_LIMIT &&
                            input.mode == ContextManifestMode.AUTHORITATIVE
                        ) ContextExclusionReason.TOKEN_BUDGET else exclusionReason,
                        originalTokenEstimate = sourceTokenEstimates.getValue(source.id),
                        compiledTokenEstimate = compiled?.let {
                            compiledTokenEstimates.getValue(it.id)
                        } ?: 0L,
                        contentDigest = sourceDigest,
                    )
                )
            }

            input.compiledMessages
                .filterNot { it.id in sourceIds }
                .forEach { generated ->
                    val digest = compiledDigests.getValue(generated.id)
                    add(
                        ContextEntry(
                            ordinal = size,
                            sourceRef = "generated:${generated.role.name.lowercase()}:$digest",
                            role = generated.role,
                            semanticKind = if (generated.role == MessageRole.SYSTEM) {
                                ContextSemanticKind.SYSTEM_INSTRUCTION
                            } else {
                                ContextSemanticKind.TRANSFORM_OUTPUT
                            },
                            trustLevel = if (generated.role == MessageRole.SYSTEM) {
                                ContextTrustLevel.TRUSTED_HOST
                            } else {
                                generated.trustLevel()
                            },
                            requirement = if (generated.role == MessageRole.SYSTEM) {
                                ContextRequirement.REQUIRED
                            } else {
                                ContextRequirement.PREFERRED
                            },
                            disposition = ContextDisposition.INCLUDED,
                            transform = ContextTransform.GENERATED,
                            originalTokenEstimate = 0L,
                            compiledTokenEstimate = compiledTokenEstimates.getValue(generated.id),
                            contentDigest = digest,
                        )
                    )
                }
        }

        val sourceDigest = ContextDigests.sha256(
            input.sourceMessages.joinToString(separator = "\n") { message ->
                "${message.id}:${sourceDigests.getValue(message.id)}"
            }
        )
        val includedTokens = entries
            .filter { it.disposition == ContextDisposition.INCLUDED }
            .sumOf(ContextEntry::compiledTokenEstimate)
        val excludedTokens = entries
            .filter { it.disposition == ContextDisposition.EXCLUDED }
            .sumOf(ContextEntry::originalTokenEstimate)
        val manifestDigestInput = buildString {
            append(input.compilerVersion()).append('|')
            append(input.requestRef).append('|')
            append(input.capabilitySnapshotId).append('|')
            append(input.selectorPolicy).append('|')
            append(input.modelWindowTokens).append('|')
            append(input.reservedOutputTokens).append('|')
            append(input.safetyMarginTokens).append('|')
            append(sourceDigest).append('|')
            entries.forEach { entry ->
                append(entry.ordinal).append(':')
                append(entry.sourceRef).append(':')
                append(entry.contentDigest).append(':')
                append(entry.disposition.name).append(':')
                append(entry.transform.name).append(':')
                append(entry.exclusionReason?.name.orEmpty()).append(':')
                append(entry.originalTokenEstimate).append(':')
                append(entry.compiledTokenEstimate).append(';')
            }
        }

        return ContextManifest(
            compilerVersion = input.compilerVersion(),
            manifestId = ContextDigests.sha256(manifestDigestInput),
            requestRef = input.requestRef,
            capabilitySnapshotId = input.capabilitySnapshotId,
            selectorPolicy = input.selectorPolicy,
            modelWindowTokens = input.modelWindowTokens,
            reservedOutputTokens = input.reservedOutputTokens,
            safetyMarginTokens = input.safetyMarginTokens,
            entries = entries,
            includedTokens = includedTokens,
            excludedTokens = excludedTokens,
            sourceDigest = sourceDigest,
            mode = input.mode,
        )
    }

    companion object {
        const val COMPILER_VERSION: String = "shadow-context-v1"
        const val AUTHORITATIVE_COMPILER_VERSION: String = "authoritative-context-v1"
    }

    private fun ShadowContextManifestInput.compilerVersion(): String =
        if (mode == ContextManifestMode.AUTHORITATIVE) AUTHORITATIVE_COMPILER_VERSION else COMPILER_VERSION
}

object ContextDigests {
    fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0x0f])
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun UIMessage.semanticKind(): ContextSemanticKind = when {
    role == MessageRole.SYSTEM -> ContextSemanticKind.SYSTEM_INSTRUCTION
    parts.filterIsInstance<UIMessagePart.Text>().any {
        it.metadata?.get("context_provenance")?.toString()?.trim('"') in
            setOf("structured_compaction", "legacy_summary")
    } -> ContextSemanticKind.EPISODIC_SUMMARY
    parts.any { it is UIMessagePart.Tool || it is UIMessagePart.ToolCall || it is UIMessagePart.ToolResult } ->
        ContextSemanticKind.TOOL_INTERACTION

    parts.any { it is UIMessagePart.Document } -> ContextSemanticKind.DOCUMENT_INPUT
    parts.any { it is UIMessagePart.Image || it is UIMessagePart.Video || it is UIMessagePart.Audio } ->
        ContextSemanticKind.MEDIA_INPUT

    role == MessageRole.USER -> ContextSemanticKind.USER_MESSAGE
    else -> ContextSemanticKind.ASSISTANT_MESSAGE
}

@Suppress("DEPRECATION")
private fun UIMessage.trustLevel(): ContextTrustLevel = when {
    role == MessageRole.SYSTEM -> ContextTrustLevel.TRUSTED_HOST
    role == MessageRole.USER -> ContextTrustLevel.USER_ASSERTED
    parts.any { part ->
        part is UIMessagePart.Tool && part.output.isNotEmpty() || part is UIMessagePart.ToolResult
    } -> ContextTrustLevel.TOOL_OUTPUT

    role == MessageRole.ASSISTANT -> ContextTrustLevel.MODEL_OUTPUT
    else -> ContextTrustLevel.EXTERNAL_CONTENT
}

private fun UIMessage.requirement(latestUserId: Uuid?): ContextRequirement = when {
    role == MessageRole.SYSTEM -> ContextRequirement.REQUIRED
    id == latestUserId -> ContextRequirement.REQUIRED
    parts.any { it is UIMessagePart.Tool } -> ContextRequirement.PREFERRED
    role == MessageRole.ASSISTANT -> ContextRequirement.COMPRESSIBLE
    else -> ContextRequirement.PREFERRED
}

@Suppress("DEPRECATION")
private fun UIMessage.contentDigest(): String = ContextDigests.sha256(
    buildString {
        append(role.name).append('|')
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> append("text:").append(part.text)
                is UIMessagePart.Image -> append("image:").append(part.assetId ?: part.url)
                is UIMessagePart.Video -> append("video:").append(part.assetId ?: part.url)
                is UIMessagePart.Audio -> append("audio:").append(part.assetId ?: part.url)
                is UIMessagePart.Document -> append("document:")
                    .append(part.assetId ?: part.url)
                    .append(':').append(part.fileName)
                    .append(':').append(part.mime)

                is UIMessagePart.Reasoning -> append("reasoning:").append(part.reasoning)
                is UIMessagePart.ProviderOpaque -> append("provider_opaque:")
                    .append(part.provider).append(':').append(part.blockType).append(':')
                    .append(part.payloadJson)
                is UIMessagePart.Tool -> {
                    append("tool:").append(part.toolCallId).append(':')
                        .append(part.toolName).append(':').append(part.input).append(':')
                        .append(part.executionState?.name.orEmpty()).append(':')
                    part.output.forEach { output -> append(output.canonicalPart()) }
                }

                is UIMessagePart.ToolCall -> append("tool_call:").append(part.toolCallId)
                    .append(':').append(part.toolName).append(':').append(part.arguments)

                is UIMessagePart.ToolResult -> append("tool_result:").append(part.toolCallId)
                    .append(':').append(part.toolName).append(':').append(part.content)
                    .append(':').append(part.arguments)

                UIMessagePart.Search -> append("search")
            }
            append('\n')
        }
    }
)

@Suppress("DEPRECATION")
private fun UIMessagePart.canonicalPart(): String = when (this) {
    is UIMessagePart.Text -> "text:$text"
    is UIMessagePart.Image -> "image:${assetId ?: url}"
    is UIMessagePart.Video -> "video:${assetId ?: url}"
    is UIMessagePart.Audio -> "audio:${assetId ?: url}"
    is UIMessagePart.Document -> "document:${assetId ?: url}:$fileName:$mime"
    is UIMessagePart.Reasoning -> "reasoning:$reasoning"
    is UIMessagePart.ProviderOpaque -> "provider_opaque:$provider:$blockType:$payloadJson"
    is UIMessagePart.Tool -> "tool:$toolCallId:$toolName:$input:${output.joinToString { it.canonicalPart() }}"
    is UIMessagePart.ToolCall -> "tool_call:$toolCallId:$toolName:$arguments"
    is UIMessagePart.ToolResult -> "tool_result:$toolCallId:$toolName:$content:$arguments"
    UIMessagePart.Search -> "search"
}
