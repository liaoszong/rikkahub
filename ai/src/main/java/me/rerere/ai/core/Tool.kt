package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    val executeWithContext: (suspend (JsonElement, ToolExecutionContext) -> List<UIMessagePart>)? = null,
    /** Stable host authority used only by RequestLedger; never exposed to a model/provider. */
    @Transient val ledgerAuthorityId: String? = null,
    /** Provider-neutral side-effect hint: none/read_only/reversible_write/irreversible/unknown. */
    @Transient val ledgerSideEffectClass: String? = null,
    /**
     * False for a local orchestration tool whose paid/network effects are represented by child
     * ledger requests. This prevents the parent tool invocation from claiming the same billable
     * boundary as its children.
     */
    @Transient val ledgerOwnsExternalDispatch: Boolean = true,
)

/** Runtime-only context for tools that need conversation attachments or progressive UI updates. */
data class ToolExecutionContext(
    val toolCallId: String,
    val messages: List<UIMessage>,
    val emitProgress: suspend (List<UIMessagePart>) -> Unit,
    /** Opaque host-owned scope identity, such as a conversation ID. */
    val contextId: String? = null,
    /** Stable host-owned execution identity; never derive billing idempotency from provider IDs. */
    val executionRequestId: String = toolCallId,
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
