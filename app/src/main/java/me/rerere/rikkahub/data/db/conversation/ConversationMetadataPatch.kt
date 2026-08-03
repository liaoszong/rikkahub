package me.rerere.rikkahub.data.db.conversation

import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

internal sealed interface ConversationMetadataField<out T> {
    data object Keep : ConversationMetadataField<Nothing>

    data class Set<T>(val value: T) : ConversationMetadataField<T>
}

internal data class ConversationMetadataPatch(
    val assistantId: ConversationMetadataField<Uuid> = ConversationMetadataField.Keep,
    val title: ConversationMetadataField<String> = ConversationMetadataField.Keep,
    val chatSuggestions: ConversationMetadataField<List<String>> = ConversationMetadataField.Keep,
    val isPinned: ConversationMetadataField<Boolean> = ConversationMetadataField.Keep,
    val customSystemPrompt: ConversationMetadataField<String?> = ConversationMetadataField.Keep,
    val modeInjectionIds: ConversationMetadataField<Set<Uuid>> = ConversationMetadataField.Keep,
    val lorebookIds: ConversationMetadataField<Set<Uuid>> = ConversationMetadataField.Keep,
    val workspaceCwd: ConversationMetadataField<String?> = ConversationMetadataField.Keep,
    val folderId: ConversationMetadataField<Uuid?> = ConversationMetadataField.Keep,
) {
    fun applyTo(conversation: Conversation): Conversation = conversation.copy(
        assistantId = assistantId.resolve(conversation.assistantId),
        title = title.resolve(conversation.title),
        chatSuggestions = chatSuggestions.resolve(conversation.chatSuggestions),
        isPinned = isPinned.resolve(conversation.isPinned),
        customSystemPrompt = customSystemPrompt.resolve(conversation.customSystemPrompt),
        modeInjectionIds = modeInjectionIds.resolve(conversation.modeInjectionIds),
        lorebookIds = lorebookIds.resolve(conversation.lorebookIds),
        workspaceCwd = workspaceCwd.resolve(conversation.workspaceCwd),
        folderId = folderId.resolve(conversation.folderId),
    )
}

private fun <T> ConversationMetadataField<T>.resolve(current: T): T = when (this) {
    ConversationMetadataField.Keep -> current
    is ConversationMetadataField.Set -> value
}
