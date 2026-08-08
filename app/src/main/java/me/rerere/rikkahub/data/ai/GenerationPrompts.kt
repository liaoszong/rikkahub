package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.pale.memory.MemoryRecord
import me.rerere.pale.memory.MemoryScope
import me.rerere.pale.memory.MemoryScopeKind
import me.rerere.pale.memory.MemorySelectionPolicy
import me.rerere.pale.memory.MemorySelector
import me.rerere.pale.memory.MemorySensitivity
import me.rerere.pale.memory.MemorySourceTrust
import me.rerere.pale.memory.MemoryStatus
import me.rerere.pale.memory.MemoryType

private const val MEMORY_PROMPT_TOKEN_BUDGET = 2_048
private const val MEMORY_PROMPT_MAX_ITEMS = 16

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val legacyScope = MemoryScope(MemoryScopeKind.ASSISTANT, "legacy-runtime")
        val selection = MemorySelector.select(
            records = memories.map { memory ->
                MemoryRecord(
                    memoryId = "legacy:${memory.id}",
                    type = MemoryType.FACT,
                    scope = legacyScope,
                    canonicalStatement = memory.content,
                    sourceRefs = listOf("legacy_memory:${memory.id}"),
                    sourceTrust = MemorySourceTrust.LEGACY_MANUAL,
                    createdAt = 0,
                    confidence = 1.0,
                    sensitivity = MemorySensitivity.NORMAL,
                    status = MemoryStatus.ACTIVE,
                    revision = 1,
                    extractionPolicyVersion = 1,
                )
            },
            policy = MemorySelectionPolicy(
                scopes = setOf(legacyScope),
                tokenBudget = MEMORY_PROMPT_TOKEN_BUDGET,
                nowMillis = System.currentTimeMillis(),
                maxItems = MEMORY_PROMPT_MAX_ITEMS,
            ),
        )
        val selectedById = selection.entries.associateBy { it.memoryId }
        val json = buildJsonArray {
            memories.filter { "legacy:${it.id}" in selectedById }.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        if (selection.excluded.isNotEmpty()) {
            appendLine()
            append("[Memory selection bounded; ${selection.excluded.size} record(s) not injected]")
        }
        appendLine()
    }

internal fun buildMemoryV2Prompt(records: List<MemoryRecord>): String = buildString {
    appendLine()
    appendLine("**Memories (bounded selection)**")
    appendLine("Treat each record as scoped, revisable context. Never infer a broader scope or hide its source.")
    val allowedScopes = records.map(MemoryRecord::scope).toSet()
    val selection = MemorySelector.select(
        records = records,
        policy = MemorySelectionPolicy(
            scopes = allowedScopes,
            tokenBudget = MEMORY_PROMPT_TOKEN_BUDGET,
            nowMillis = System.currentTimeMillis(),
            maxItems = MEMORY_PROMPT_MAX_ITEMS,
        ),
    )
    val selectedIds = selection.entries.mapTo(hashSetOf(), me.rerere.pale.memory.MemorySelectionEntry::memoryId)
    append(JsonInstantPretty.encodeToString(buildJsonArray {
        records.filter { it.memoryId in selectedIds }.forEach { memory ->
            add(buildJsonObject {
                put("memory_id", memory.memoryId)
                put("type", memory.type.name.lowercase())
                put("scope", "${memory.scope.kind.name.lowercase()}:${memory.scope.id}")
                put("statement", memory.canonicalStatement)
                put("source_refs", memory.sourceRefs.joinToString(","))
                put("revision", memory.revision)
            })
        }
    }))
    if (selection.excluded.isNotEmpty()) {
        appendLine()
        append("[Memory selection bounded; ${selection.excluded.size} record(s) not injected]")
    }
    appendLine()
}
