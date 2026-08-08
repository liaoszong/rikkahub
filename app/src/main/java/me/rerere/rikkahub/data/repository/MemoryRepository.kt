package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryV2DAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryAuditEventV2Entity
import me.rerere.rikkahub.data.db.entity.MemoryRecordV2Entity
import me.rerere.rikkahub.data.db.entity.MemoryRevisionV2Entity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.pale.memory.MemoryRecord
import me.rerere.pale.memory.MemoryScope
import me.rerere.pale.memory.MemoryScopeKind
import me.rerere.pale.memory.MemorySensitivity
import me.rerere.pale.memory.MemorySourceTrust
import me.rerere.pale.memory.MemoryStatus
import me.rerere.pale.memory.MemoryType
import me.rerere.pale.memory.MemoryCandidate
import me.rerere.pale.memory.MemoryCandidateDecision
import me.rerere.pale.memory.MemoryWritePolicy

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val memoryV2DAO: MemoryV2DAO,
    private val database: AppDatabase,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId).combine(
            memoryV2DAO.observeScope("assistant", assistantId),
        ) { entities, records ->
            val activeIds = records.filter { it.status == "active" }.mapNotNullTo(hashSetOf()) { it.legacyId }
            entities.filter { it.id in activeIds }.map { AssistantMemory(it.id, it.content) }
        }

    fun observeAllLegacyMemories(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId).map { entities ->
            entities.map { AssistantMemory(it.id, it.content) }
        }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        val activeIds = memoryV2DAO.getActiveScope("assistant", assistantId)
            .mapNotNullTo(hashSetOf()) { it.legacyId }
        return memoryDAO.getMemoriesOfAssistant(assistantId).filter { it.id in activeIds }
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID).combine(
            memoryV2DAO.observeScope("user", GLOBAL_MEMORY_ID),
        ) { entities, records ->
            val activeIds = records.filter { it.status == "active" }.mapNotNullTo(hashSetOf()) { it.legacyId }
            entities.filter { it.id in activeIds }.map { AssistantMemory(it.id, it.content) }
        }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        val activeIds = memoryV2DAO.getActiveScope("user", GLOBAL_MEMORY_ID)
            .mapNotNullTo(hashSetOf()) { it.legacyId }
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID).filter { it.id in activeIds }
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        database.withTransaction {
            val scopeKind = if (assistantId == GLOBAL_MEMORY_ID) "user" else "assistant"
            val existing = memoryV2DAO.getScope(scopeKind, assistantId)
            memoryV2DAO.updateScopeStatus(scopeKind, assistantId, "deleted", nowMillis())
            existing.forEach { record ->
                memoryV2DAO.get(record.memoryId)?.let {
                    appendRevision(it, "scope_deleted")
                    appendAudit(it, "scope_deleted")
                }
            }
            memoryDAO.deleteMemoriesOfAssistant(assistantId)
            val projectionMatches = memoryDAO.getMemoriesOfAssistant(assistantId).isEmpty() &&
                memoryV2DAO.getScope(scopeKind, assistantId).all { it.status == "deleted" }
            existing.firstOrNull()?.let { record ->
                appendAudit(record, if (projectionMatches) "dual_write_match" else "dual_write_mismatch")
            }
            check(projectionMatches) { "Memory compatibility projections diverged for scope $assistantId" }
        }
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        lateinit var newMemory: MemoryEntity
        database.withTransaction {
            val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
            newMemory = old.copy(content = content)
            memoryDAO.updateMemory(newMemory)
            check(memoryV2DAO.updateLegacyStatement(id, content, nowMillis()) == 1) {
                "Memory V2 projection missing for legacy record #$id"
            }
            memoryV2DAO.getByLegacyId(id)!!.let {
                appendRevision(it, "updated")
                appendAudit(it, "updated")
            }
            verifyDualWrite(id)
        }
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        sourceTrust: MemorySourceTrust = MemorySourceTrust.EXPLICIT_USER,
        confidence: Double = 1.0,
    ): AssistantMemory {
        require(content.isNotBlank())
        var newId = 0
        database.withTransaction {
            newId = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = content,
                )
            ).toInt()
            val now = nowMillis()
            val scope = MemoryScope(
                if (assistantId == GLOBAL_MEMORY_ID) MemoryScopeKind.USER else MemoryScopeKind.ASSISTANT,
                assistantId,
            )
            val decision = MemoryWritePolicy.evaluate(
                MemoryCandidate(
                    memoryId = "legacy:$newId",
                    type = MemoryType.FACT,
                    scope = scope,
                    statement = content,
                    sourceRefs = listOf("memory_mutation:$newId"),
                    sourceTrust = sourceTrust,
                    confidence = confidence,
                    explicitUserMutation = sourceTrust == MemorySourceTrust.EXPLICIT_USER,
                )
            )
            check(decision != MemoryCandidateDecision.REJECT_EXTERNAL_FACT &&
                decision != MemoryCandidateDecision.REJECT_LOW_VALUE) { "Memory candidate rejected by policy" }
            val active = decision == MemoryCandidateDecision.ACTIVATE
            val record = MemoryRecordV2Entity(
                memoryId = "legacy:$newId",
                legacyId = newId,
                type = "fact",
                scopeKind = if (assistantId == GLOBAL_MEMORY_ID) "user" else "assistant",
                scopeId = assistantId,
                canonicalStatement = content,
                sourceRefsJson = json.encodeToString(listOf("memory_mutation:$newId")),
                sourceTrust = sourceTrust.name.lowercase(Locale.ROOT),
                createdAt = now,
                confirmedAt = now.takeIf { active },
                confidence = confidence,
                sensitivity = "normal",
                status = if (active) "active" else "candidate",
                revision = 1,
                extractionPolicyVersion = 1,
                updatedAt = now,
            )
            memoryV2DAO.insert(record)
            appendRevision(record, if (active) "created" else "candidate_created")
            appendAudit(record, if (active) "created" else "candidate_created")
            verifyDualWrite(newId)
        }
        return AssistantMemory(newId, content)
    }

    suspend fun deleteMemory(id: Int) {
        database.withTransaction {
            check(memoryV2DAO.updateLegacyStatus(id, "deleted", nowMillis()) == 1) {
                "Memory V2 projection missing for legacy record #$id"
            }
            memoryV2DAO.getByLegacyId(id)!!.let {
                appendRevision(it, "deleted")
                appendAudit(it, "deleted")
            }
            memoryDAO.deleteMemory(id)
            verifyDualWrite(id, expectedDeleted = true)
        }
    }

    suspend fun getActiveMemoryV2(assistantId: String): List<MemoryRecord> {
        val scopeKind = if (assistantId == GLOBAL_MEMORY_ID) "user" else "assistant"
        return memoryV2DAO.getActiveScope(scopeKind, assistantId).map(::toDomain)
    }

    fun observeMemoryV2(assistantId: String): Flow<List<MemoryRecord>> {
        val scopeKind = if (assistantId == GLOBAL_MEMORY_ID) "user" else "assistant"
        return memoryV2DAO.observeScope(scopeKind, assistantId).map { records -> records.map(::toDomain) }
    }

    suspend fun getMemoryRevisionTimeline(id: Int): List<MemoryRevisionV2Entity> {
        val memoryId = memoryV2DAO.getByLegacyId(id)?.memoryId ?: return emptyList()
        return memoryV2DAO.getRevisions(memoryId)
    }

    suspend fun setMemoryEnabled(id: Int, enabled: Boolean) {
        database.withTransaction {
            check(memoryV2DAO.updateLegacyStatus(id, if (enabled) "active" else "disabled", nowMillis()) == 1)
            memoryV2DAO.getByLegacyId(id)!!.let {
                appendRevision(it, if (enabled) "enabled" else "disabled")
                appendAudit(it, if (enabled) "enabled" else "disabled")
            }
            verifyDualWrite(id)
        }
    }

    private suspend fun verifyDualWrite(id: Int, expectedDeleted: Boolean = false) {
        val legacy = memoryDAO.getMemoryById(id)
        val projected = memoryV2DAO.getByLegacyId(id)
            ?: error("Memory V2 projection missing for legacy record #$id")
        val matches = if (expectedDeleted) {
            legacy == null && projected.status == "deleted"
        } else {
            legacy != null && projected.canonicalStatement == legacy.content &&
                projected.scopeId == legacy.assistantId && projected.status != "deleted"
        }
        appendAudit(projected, if (matches) "dual_write_match" else "dual_write_mismatch")
        check(matches) { "Memory compatibility projections diverged for legacy record #$id" }
    }

    private suspend fun appendAudit(record: MemoryRecordV2Entity, eventKind: String) {
        memoryV2DAO.appendAudit(
            MemoryAuditEventV2Entity(
                eventId = UUID.randomUUID().toString().lowercase(Locale.ROOT),
                memoryId = record.memoryId,
                eventKind = eventKind,
                revision = record.revision,
                payloadDigest = sha256("${record.memoryId}|$eventKind|${record.revision}|${record.status}"),
                createdAt = nowMillis(),
            )
        )
    }

    private suspend fun appendRevision(record: MemoryRecordV2Entity, eventKind: String) {
        memoryV2DAO.insertRevision(
            MemoryRevisionV2Entity(
                memoryId = record.memoryId,
                revision = record.revision,
                canonicalStatement = record.canonicalStatement,
                sourceRefsJson = record.sourceRefsJson,
                status = record.status,
                supersedesRevision = (record.revision - 1).takeIf { it > 0 },
                eventKind = eventKind,
                createdAt = record.updatedAt,
            )
        )
    }

    private fun toDomain(entity: MemoryRecordV2Entity) = MemoryRecord(
        memoryId = entity.memoryId,
        type = MemoryType.valueOf(entity.type.uppercase(Locale.ROOT)),
        scope = MemoryScope(
            MemoryScopeKind.valueOf(entity.scopeKind.uppercase(Locale.ROOT)),
            entity.scopeId,
        ),
        canonicalStatement = entity.canonicalStatement,
        sourceRefs = json.decodeFromString(entity.sourceRefsJson),
        sourceTrust = MemorySourceTrust.valueOf(entity.sourceTrust.uppercase(Locale.ROOT)),
        createdAt = entity.createdAt,
        confirmedAt = entity.confirmedAt,
        lastUsedAt = entity.lastUsedAt,
        expiresAt = entity.expiresAt,
        confidence = entity.confidence,
        sensitivity = MemorySensitivity.valueOf(entity.sensitivity.uppercase(Locale.ROOT)),
        status = MemoryStatus.valueOf(entity.status.uppercase(Locale.ROOT)),
        revision = entity.revision,
        supersedes = json.decodeFromString(entity.supersedesJson),
        conflictsWith = json.decodeFromString(entity.conflictsWithJson),
        extractionPolicyVersion = entity.extractionPolicyVersion,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
