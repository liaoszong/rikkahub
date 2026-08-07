package me.rerere.rikkahub.data.db.conversation

import android.util.Log
import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationGraphDAO
import me.rerere.rikkahub.data.db.dao.ConversationMigrationDAO
import me.rerere.rikkahub.data.db.dao.LegacyMessageNodeHeader
import me.rerere.rikkahub.data.db.dao.MessageFtsOutboxDAO
import me.rerere.rikkahub.data.db.entity.ConversationMessageEntity
import me.rerere.rikkahub.data.db.entity.ConversationMigrationQuarantineEntity
import me.rerere.rikkahub.data.db.entity.ConversationV2Values
import me.rerere.rikkahub.data.db.entity.MessageBranchGroupEntity
import me.rerere.rikkahub.data.db.entity.MessageFtsOutboxEntity
import me.rerere.rikkahub.data.db.entity.MessagePartEntity
import me.rerere.rikkahub.utils.logSafeError
import java.util.UUID
import kotlin.uuid.Uuid

private const val TAG = "ConversationV2Backfill"

class ConversationV2BackfillCoordinator(
    private val database: AppDatabase,
    private val graphDAO: ConversationGraphDAO,
    private val migrationDAO: ConversationMigrationDAO,
    private val ftsOutboxDAO: MessageFtsOutboxDAO,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    workerId: String = UUID.randomUUID().toString(),
) {
    private val workerId = "conversation-v2-$workerId"

    /**
     * Installs conservative invalidation guards for the temporary legacy-write phase.
     * The triggers never alter legacy JSON; they only make a stale shadow ineligible for reads.
     */
    suspend fun installLegacyInvalidationTriggers() {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            INVALIDATION_TRIGGER_NAMES.forEach { triggerName ->
                db.execSQL("DROP TRIGGER IF EXISTS $triggerName")
            }
            db.execSQL(LEGACY_NODE_INSERT_TRIGGER)
            db.execSQL(LEGACY_NODE_UPDATE_TRIGGER)
            db.execSQL(LEGACY_NODE_DELETE_TRIGGER)
            db.execSQL(LEGACY_STORAGE_DOWNGRADE_TRIGGER)
        }
    }

    suspend fun runPending(
        maxConversations: Int = DEFAULT_MAX_CONVERSATIONS,
        maxNodesPerConversation: Int = Int.MAX_VALUE,
    ): ConversationV2BackfillSummary {
        require(maxConversations > 0)
        require(maxNodesPerConversation > 0)
        installLegacyInvalidationTriggers()
        migrationDAO.seedMissingJournals(nowMillis())

        var inspected = 0
        var ready = 0
        var quarantined = 0
        var inProgress = 0
        var failed = 0
        while (inspected < maxConversations) {
            val now = nowMillis()
            val candidates = migrationDAO.getLeaseCandidates(
                now = now,
                limit = minOf(LEASE_BATCH_SIZE, maxConversations - inspected),
            )
            if (candidates.isEmpty()) break
            var claimedAny = false
            for (conversationId in candidates) {
                if (inspected >= maxConversations) break
                val claimed = migrationDAO.claimLease(
                    conversationId = conversationId,
                    workerId = workerId,
                    now = nowMillis(),
                    leaseUntil = nowMillis() + LEASE_DURATION_MS,
                )
                if (claimed != 1) continue
                claimedAny = true
                inspected++
                when (migrateClaimedConversation(conversationId, maxNodesPerConversation)) {
                    BackfillOutcome.READY, BackfillOutcome.ALREADY_READY -> ready++
                    BackfillOutcome.QUARANTINED -> quarantined++
                    BackfillOutcome.IN_PROGRESS -> inProgress++
                    BackfillOutcome.FAILED -> failed++
                    BackfillOutcome.LEASE_LOST -> Unit
                }
            }
            if (!claimedAny) break
        }
        return ConversationV2BackfillSummary(
            inspected = inspected,
            ready = ready,
            quarantined = quarantined,
            inProgress = inProgress,
            failed = failed,
        )
    }

    private suspend fun migrateClaimedConversation(
        conversationId: String,
        nodeBudget: Int,
    ): BackfillOutcome = try {
        when (prepareClaimedConversation(conversationId)) {
            PrepareOutcome.ALREADY_READY -> return BackfillOutcome.ALREADY_READY
            PrepareOutcome.LEASE_LOST -> return BackfillOutcome.LEASE_LOST
            PrepareOutcome.READY_TO_COPY -> Unit
        }

        val currentJournal = migrationDAO.getJournal(conversationId)
            ?: return BackfillOutcome.LEASE_LOST
        if (currentJournal.phase == ConversationV2Values.MIGRATION_VERIFYING) {
            return finalizeVerification(conversationId)
        }

        repeat(nodeBudget) {
            when (processNextNode(conversationId)) {
                StepOutcome.NODE_WRITTEN -> Unit
                StepOutcome.NO_MORE_NODES -> {
                    when (prepareVerification(conversationId)) {
                        VerificationPreparation.READY -> return finalizeVerification(conversationId)
                        VerificationPreparation.SOURCE_RESET -> {
                            migrationDAO.releaseLease(conversationId, workerId, nowMillis())
                            return BackfillOutcome.IN_PROGRESS
                        }
                    }
                }
            }
        }
        migrationDAO.releaseLease(conversationId, workerId, nowMillis())
        BackfillOutcome.IN_PROGRESS
    } catch (issue: QuarantineIssue) {
        quarantine(conversationId, issue)
        BackfillOutcome.QUARANTINED
    } catch (_: LeaseLostException) {
        BackfillOutcome.LEASE_LOST
    } catch (error: Throwable) {
        val now = nowMillis()
        migrationDAO.recordTransientFailure(
            conversationId = conversationId,
            workerId = workerId,
            errorCode = "TRANSIENT_BACKFILL_FAILURE",
            detail = error.safeDetail(),
            now = now,
            retryAt = now + RETRY_DELAY_MS,
        )
        logSafeError(
            tag = TAG,
            domain = "conversation_migration",
            operation = "backfill_shadow_graph",
            error = error,
            requestId = conversationId,
        )
        BackfillOutcome.FAILED
    }

    private suspend fun prepareClaimedConversation(conversationId: String): PrepareOutcome =
        database.withTransaction {
            val state = migrationDAO.getConversationState(conversationId)
                ?: throw LeaseLostException()
            val journal = migrationDAO.getJournal(conversationId)
                ?: throw LeaseLostException()
            if (
                state.storageVersion == ConversationV2Values.STORAGE_VERSION_V2 &&
                journal.phase == ConversationV2Values.MIGRATION_READY
            ) {
                return@withTransaction PrepareOutcome.ALREADY_READY
            }
            if (state.deletedAt != null || journal.leaseOwner != workerId) {
                return@withTransaction PrepareOutcome.LEASE_LOST
            }
            check(state.storageVersion == ConversationV2Values.STORAGE_VERSION_LEGACY) {
                "Conversation $conversationId has inconsistent storage version ${state.storageVersion}"
            }

            val headers = migrationDAO.getLegacyNodeHeaders(conversationId)
            val sourceDigest = computeLegacySourceDigest(conversationId, headers)
            val sourceChanged = journal.legacySourceDigest != sourceDigest ||
                journal.sourceRevision != state.revision ||
                journal.phase == ConversationV2Values.MIGRATION_PENDING
            if (sourceChanged) {
                clearShadow(conversationId)
                migrationDAO.deleteQuarantine(conversationId)
                val reset = migrationDAO.resetForSource(
                    conversationId = conversationId,
                    workerId = workerId,
                    sourceRevision = state.revision,
                    sourceDigest = sourceDigest,
                    groupCount = null,
                    now = nowMillis(),
                    leaseUntil = nowMillis() + LEASE_DURATION_MS,
                )
                if (reset != 1) throw LeaseLostException()
            }
            PrepareOutcome.READY_TO_COPY
        }

    private suspend fun processNextNode(conversationId: String): StepOutcome = database.withTransaction {
        val journal = migrationDAO.getJournal(conversationId) ?: throw LeaseLostException()
        if (
            journal.phase != ConversationV2Values.MIGRATION_COPYING ||
            journal.leaseOwner != workerId
        ) {
            throw LeaseLostException()
        }
        val headers = migrationDAO.getLegacyNodeHeaders(conversationId)
        val header = headers.getOrNull(journal.nextNodeIndex)
            ?: return@withTransaction StepOutcome.NO_MORE_NODES
        val rawMessages = readLegacyMessages(header)
        val identities = graphDAO.getMessageIdentities(conversationId)
        val seenMessageIds = identities.flatMapTo(mutableSetOf()) { identity ->
            listOfNotNull(identity.messageId, identity.legacyMessageId)
        }
        val prepared = prepareNode(
            conversationId = conversationId,
            header = header,
            legacyOrder = journal.writtenGroupCount,
            rawMessages = rawMessages,
            parentMessageId = journal.previousSelectedMessageId,
            seenMessageIds = seenMessageIds,
        )

        prepared.group?.let { graphDAO.insertBranchGroup(it) }
        if (prepared.messages.isNotEmpty()) graphDAO.insertMessages(prepared.messages)
        if (prepared.parts.isNotEmpty()) graphDAO.insertParts(prepared.parts)
        val flags = decodeFlags(journal.inferenceFlagsJson).apply { addAll(prepared.inferenceFlags) }
        val checkpointed = migrationDAO.checkpointNode(
            conversationId = conversationId,
            workerId = workerId,
            nextNodeIndex = journal.nextNodeIndex + 1,
            previousSelectedMessageId = prepared.selectedMessageId ?: journal.previousSelectedMessageId,
            writtenGroupCount = journal.writtenGroupCount + if (prepared.group == null) 0 else 1,
            writtenMessageCount = journal.writtenMessageCount + prepared.messages.size,
            writtenPartCount = journal.writtenPartCount + prepared.parts.size,
            inferenceFlagsJson = encodeFlags(flags),
            now = nowMillis(),
            leaseUntil = nowMillis() + LEASE_DURATION_MS,
        )
        if (checkpointed != 1) throw LeaseLostException()
        StepOutcome.NODE_WRITTEN
    }

    private suspend fun prepareVerification(conversationId: String): VerificationPreparation =
        database.withTransaction {
            val journal = migrationDAO.getJournal(conversationId) ?: throw LeaseLostException()
            if (
                journal.phase != ConversationV2Values.MIGRATION_COPYING ||
                journal.leaseOwner != workerId
            ) {
                throw LeaseLostException()
            }
            val prepared = prepareLegacyConversation(conversationId)
            if (prepared.sourceDigest != journal.legacySourceDigest) {
                resetAfterSourceChange(conversationId, prepared)
                return@withTransaction VerificationPreparation.SOURCE_RESET
            }
            if (prepared.activeLeafMessageId != journal.previousSelectedMessageId) {
                throw QuarantineIssue(
                    reasonCode = "ACTIVE_PATH_MISMATCH",
                    nodeId = null,
                    variantIndex = null,
                    rawPayload = null,
                    detail = "Selected legacy path differs from the checkpointed path",
                )
            }
            val marked = migrationDAO.markVerifying(
                conversationId = conversationId,
                workerId = workerId,
                groupCount = prepared.graph.groups.size,
                messageCount = prepared.graph.messages.size,
                partCount = prepared.graph.parts.size,
                legacyDigest = digestConversationV2Graph(prepared.graph),
                now = nowMillis(),
                leaseUntil = nowMillis() + LEASE_DURATION_MS,
            )
            if (marked != 1) throw LeaseLostException()
            VerificationPreparation.READY
        }

    private suspend fun finalizeVerification(conversationId: String): BackfillOutcome =
        database.withTransaction {
            val journal = migrationDAO.getJournal(conversationId) ?: throw LeaseLostException()
            val state = migrationDAO.getConversationState(conversationId) ?: throw LeaseLostException()
            if (
                journal.phase != ConversationV2Values.MIGRATION_VERIFYING ||
                journal.leaseOwner != workerId
            ) {
                throw LeaseLostException()
            }

            val headers = migrationDAO.getLegacyNodeHeaders(conversationId)
            val currentSourceDigest = computeLegacySourceDigest(conversationId, headers)
            if (currentSourceDigest != journal.legacySourceDigest || state.revision != journal.sourceRevision) {
                val prepared = prepareLegacyConversation(conversationId)
                resetAfterSourceChange(conversationId, prepared)
                return@withTransaction BackfillOutcome.IN_PROGRESS
            }

            val graph = loadConversationV2Graph(
                graphDAO = graphDAO,
                conversationId = conversationId,
                activeLeafMessageId = journal.previousSelectedMessageId,
            )
            val groupCount = graphDAO.countBranchGroups(conversationId)
            val messageCount = graphDAO.countMessages(conversationId)
            val partCount = graphDAO.countParts(conversationId)
            val countsMatch = groupCount == journal.expectedGroupCount &&
                messageCount == journal.expectedMessageCount &&
                partCount == journal.expectedPartCount &&
                groupCount == journal.writtenGroupCount &&
                messageCount == journal.writtenMessageCount &&
                partCount == journal.writtenPartCount
            if (!countsMatch) {
                throw QuarantineIssue(
                    reasonCode = "PROJECTION_COUNT_MISMATCH",
                    nodeId = null,
                    variantIndex = null,
                    rawPayload = null,
                    detail = "Expected and written graph counts differ",
                )
            }

            val v2Digest = digestConversationV2Graph(graph)
            if (v2Digest != journal.legacyProjectionDigest) {
                throw QuarantineIssue(
                    reasonCode = "PROJECTION_DIGEST_MISMATCH",
                    nodeId = null,
                    variantIndex = null,
                    rawPayload = null,
                    detail = "Legacy and v2 semantic projections differ",
                )
            }
            if (
                migrationDAO.markConversationReady(
                    conversationId = conversationId,
                    sourceRevision = journal.sourceRevision,
                    activeLeafMessageId = journal.previousSelectedMessageId,
                ) != 1
            ) {
                throw LeaseLostException()
            }
            val now = nowMillis()
            val previousOrder = ftsOutboxDAO.getMaxEventOrder(conversationId)
            val eventOrder = previousOrder?.let { maxOf(now, Math.addExact(it, 1L)) } ?: now
            val inserted = ftsOutboxDAO.enqueue(
                MessageFtsOutboxEntity(
                    eventId = deterministicConversationV2Id(
                        "fts-backfill",
                        conversationId,
                        eventOrder.toString(),
                        ConversationV2Values.OUTBOX_UPSERT,
                    ),
                    conversationId = conversationId,
                    targetRevision = journal.sourceRevision,
                    operation = ConversationV2Values.OUTBOX_UPSERT,
                    createdAt = eventOrder,
                    updatedAt = eventOrder,
                ),
            )
            check(inserted != -1L) { "Unable to enqueue FTS backfill for $conversationId" }
            if (migrationDAO.markReady(conversationId, workerId, v2Digest, now) != 1) {
                throw LeaseLostException()
            }
            BackfillOutcome.READY
        }

    private suspend fun resetAfterSourceChange(
        conversationId: String,
        prepared: PreparedLegacyConversation,
    ) {
        val state = migrationDAO.getConversationState(conversationId) ?: throw LeaseLostException()
        clearShadow(conversationId)
        migrationDAO.deleteQuarantine(conversationId)
        if (
            migrationDAO.resetForSource(
                conversationId = conversationId,
                workerId = workerId,
                sourceRevision = state.revision,
                sourceDigest = prepared.sourceDigest,
                groupCount = prepared.graph.groups.size,
                now = nowMillis(),
                leaseUntil = nowMillis() + LEASE_DURATION_MS,
            ) != 1
        ) {
            throw LeaseLostException()
        }
    }

    private suspend fun clearShadow(conversationId: String) {
        graphDAO.deleteMessages(conversationId)
        graphDAO.deleteBranchGroups(conversationId)
    }

    private suspend fun prepareLegacyConversation(conversationId: String): PreparedLegacyConversation {
        val headers = migrationDAO.getLegacyNodeHeaders(conversationId)
        val sourceDigest = computeLegacySourceDigest(conversationId, headers)
        val groups = mutableListOf<MessageBranchGroupEntity>()
        val messages = mutableListOf<ConversationMessageEntity>()
        val parts = mutableListOf<MessagePartEntity>()
        val seenMessageIds = mutableSetOf<String>()
        var parentMessageId: String? = null
        headers.forEach { header ->
            val prepared = prepareNode(
                conversationId = conversationId,
                header = header,
                legacyOrder = groups.size,
                rawMessages = readLegacyMessages(header),
                parentMessageId = parentMessageId,
                seenMessageIds = seenMessageIds,
            )
            prepared.group?.let { group -> groups += group }
            messages += prepared.messages
            parts += prepared.parts
            prepared.selectedMessageId?.let { parentMessageId = it }
        }
        return PreparedLegacyConversation(
            sourceDigest = sourceDigest,
            activeLeafMessageId = parentMessageId,
            graph = ConversationV2Graph(
                activeLeafMessageId = parentMessageId,
                groups = groups,
                messages = messages,
                parts = parts,
            ),
        )
    }

    private fun prepareNode(
        conversationId: String,
        header: LegacyMessageNodeHeader,
        legacyOrder: Int,
        rawMessages: String,
        parentMessageId: String?,
        seenMessageIds: MutableSet<String>,
    ): PreparedNode {
        runCatching { Uuid.parse(header.id) }.getOrElse {
            throw QuarantineIssue(
                reasonCode = "INVALID_NODE_ID",
                nodeId = header.id,
                variantIndex = null,
                rawPayload = null,
                detail = "Legacy node ID is not a UUID",
            )
        }
        val root = runCatching { json.parseToJsonElement(rawMessages) }.getOrElse { error ->
            throw QuarantineIssue(
                reasonCode = "MALFORMED_MESSAGE_JSON",
                nodeId = header.id,
                variantIndex = null,
                rawPayload = rawMessages,
                detail = error.safeDetail(),
            )
        }
        val rawMessageArray = root as? JsonArray ?: throw QuarantineIssue(
            reasonCode = "MESSAGE_ROOT_NOT_ARRAY",
            nodeId = header.id,
            variantIndex = null,
            rawPayload = rawMessages,
            detail = "Legacy messages root is not an array",
        )
        if (rawMessageArray.isNotEmpty() && header.selectIndex !in rawMessageArray.indices) {
            throw QuarantineIssue(
                reasonCode = "SELECT_INDEX_OUT_OF_BOUNDS",
                nodeId = header.id,
                variantIndex = null,
                rawPayload = rawMessages,
                detail = "selectIndex=${header.selectIndex}, messages=${rawMessageArray.size}",
            )
        }

        val flags = mutableSetOf(INFERENCE_PARENT_PATH, INFERENCE_STATE)
        if (rawMessageArray.isEmpty()) {
            flags += INFERENCE_EMPTY_BRANCH_GROUP_DROPPED
            return PreparedNode(
                group = null,
                messages = emptyList(),
                parts = emptyList(),
                selectedMessageId = null,
                inferenceFlags = flags,
            )
        }
        val messages = mutableListOf<ConversationMessageEntity>()
        val parts = mutableListOf<MessagePartEntity>()
        rawMessageArray.forEachIndexed { variantIndex, element ->
            val rawMessage = element as? JsonObject ?: throw QuarantineIssue(
                reasonCode = "MESSAGE_NOT_OBJECT",
                nodeId = header.id,
                variantIndex = variantIndex,
                rawPayload = element.toString(),
                detail = "Legacy message is not a JSON object",
            )
            val originalMessageId = rawMessage["id"]
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
            val parsedMessageId = originalMessageId
                ?.let { rawId -> runCatching { Uuid.parse(rawId).toString() }.getOrNull() }
            val isDuplicate = parsedMessageId != null && parsedMessageId in seenMessageIds
            val effectiveMessageId = if (parsedMessageId != null && !isDuplicate) {
                parsedMessageId
            } else {
                flags += INFERENCE_MESSAGE_ID_REPAIRED
                if (isDuplicate) flags += INFERENCE_DUPLICATE_MESSAGE_ID
                deterministicConversationV2Id(
                    "legacy-message",
                    conversationId,
                    header.id,
                    variantIndex.toString(),
                    sha256Hex(element.toCanonicalJson()),
                )
            }
            seenMessageIds += effectiveMessageId
            originalMessageId?.let { seenMessageIds += it }

            val rawParts = rawMessage["parts"] as? JsonArray ?: throw QuarantineIssue(
                reasonCode = "PARTS_NOT_ARRAY",
                nodeId = header.id,
                variantIndex = variantIndex,
                rawPayload = element.toString(),
                detail = "Message parts are missing or not an array",
            )
            val normalizedParts = rawParts.mapIndexed { ordinal, rawPart ->
                val partObject = rawPart as? JsonObject ?: throw QuarantineIssue(
                    reasonCode = "PART_NOT_OBJECT",
                    nodeId = header.id,
                    variantIndex = variantIndex,
                    rawPayload = rawPart.toString(),
                    detail = "Message part $ordinal is not a JSON object",
                )
                val kind = partObject["type"]
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: throw QuarantineIssue(
                        reasonCode = "PART_KIND_MISSING",
                        nodeId = header.id,
                        variantIndex = variantIndex,
                        rawPayload = rawPart.toString(),
                        detail = "Message part $ordinal has no discriminator",
                    )
                normalizeLegacyPart(partObject, kind, flags)
            }
            val normalizedMessage = JsonObject(
                rawMessage.toMutableMap().apply {
                    put("id", JsonPrimitive(effectiveMessageId))
                    put("parts", JsonArray(normalizedParts))
                    if ("createdAt" !in this) {
                        put("createdAt", JsonPrimitive(LEGACY_LOCAL_DATE_TIME_FALLBACK))
                        flags += INFERENCE_MESSAGE_CREATED_AT
                    }
                },
            )
            val message = runCatching {
                json.decodeFromJsonElement(UIMessage.serializer(), normalizedMessage)
            }.getOrElse { error ->
                throw QuarantineIssue(
                    reasonCode = "MESSAGE_DECODE_FAILED",
                    nodeId = header.id,
                    variantIndex = variantIndex,
                    rawPayload = element.toString(),
                    detail = error.safeDetail(),
                )
            }
            if (normalizedParts.size != message.parts.size) {
                throw QuarantineIssue(
                    reasonCode = "PART_COUNT_CHANGED_DURING_DECODE",
                    nodeId = header.id,
                    variantIndex = variantIndex,
                    rawPayload = element.toString(),
                    detail = "Typed and raw part counts differ",
                )
            }
            val requestIds = message.parts
                .filterIsInstance<UIMessagePart.Tool>()
                .map { it.requestId }
                .filter(String::isNotBlank)
                .distinct()
            val state = if (message.parts.any { part ->
                    (part is UIMessagePart.Reasoning && part.finishedAt == null) ||
                        (part is UIMessagePart.Tool && part.isRunning)
                }
            ) {
                ConversationV2Values.MESSAGE_INTERRUPTED
            } else {
                ConversationV2Values.MESSAGE_COMPLETED
            }
            val envelopeExtras = rawMessage
                .filterKeys { it !in KNOWN_MESSAGE_FIELDS }
                .takeIf { it.isNotEmpty() }
                ?.let(::JsonObject)
                ?.toCanonicalJson()
            val legacyMessageId = originalMessageId.takeIf { it != effectiveMessageId }
            messages += ConversationMessageEntity(
                conversationId = conversationId,
                messageId = effectiveMessageId,
                parentMessageId = parentMessageId,
                branchGroupId = header.id,
                siblingOrdinal = variantIndex,
                legacyMessageId = legacyMessageId,
                requestId = requestIds.singleOrNull(),
                role = message.role.name.lowercase(),
                state = state,
                modelId = message.modelId?.toString(),
                createdAt = message.createdAt.toString(),
                finishedAt = message.finishedAt?.toString(),
                usageJson = normalizedMessage["usage"]?.toCanonicalJson(),
                annotationsJson = normalizedMessage["annotations"]?.toCanonicalJson() ?: "[]",
                translation = message.translation,
                envelopeExtrasJson = envelopeExtras,
                contentDigest = sha256Hex(normalizedMessage.toCanonicalJson()),
                legacyInferred = true,
            )
            normalizedParts.forEachIndexed { ordinal, partObject ->
                val kind = partObject["type"]
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                    .orEmpty()
                val canonicalPayload = partObject.toCanonicalJson()
                val payloadDigest = sha256Hex(canonicalPayload)
                parts += MessagePartEntity(
                    conversationId = conversationId,
                    partId = stableConversationPartId(
                        conversationId = conversationId,
                        messageId = effectiveMessageId,
                        ordinal = ordinal,
                    ),
                    messageId = effectiveMessageId,
                    ordinal = ordinal,
                    kind = kind,
                    payloadJson = canonicalPayload,
                    payloadDigest = payloadDigest,
                    assetId = message.parts[ordinal].backfillMediaAssetIdOrNull(),
                    toolInvocationId = message.parts[ordinal].toolInvocationIdOrNull(),
                )
            }
        }

        return PreparedNode(
            group = MessageBranchGroupEntity(
                conversationId = conversationId,
                branchGroupId = header.id,
                legacyNodeIndex = header.nodeIndex,
                legacyOrder = legacyOrder,
                createdAt = messages.first().createdAt,
                legacyInferred = true,
            ),
            messages = messages,
            parts = parts,
            selectedMessageId = messages.getOrNull(header.selectIndex)?.messageId,
            inferenceFlags = flags,
        )
    }

    private fun normalizeLegacyPart(
        part: JsonObject,
        kind: String,
        inferenceFlags: MutableSet<String>,
    ): JsonObject {
        val normalized = part.toMutableMap()
        when (kind) {
            "reasoning" -> {
                if ("createdAt" !in normalized) {
                    normalized["createdAt"] = JsonPrimitive(LEGACY_INSTANT_FALLBACK)
                    inferenceFlags += INFERENCE_REASONING_TIMESTAMPS
                }
                if ("finishedAt" !in normalized) {
                    normalized["finishedAt"] = JsonNull
                    inferenceFlags += INFERENCE_REASONING_TIMESTAMPS
                }
            }

            "tool" -> listOf("output", "progress").forEach { field ->
                val nested = normalized[field] as? JsonArray ?: return@forEach
                normalized[field] = JsonArray(
                    nested.map { child ->
                        val childObject = child as? JsonObject ?: return@map child
                        val childKind = (childObject["type"] as? JsonPrimitive)
                            ?.contentOrNull
                            ?: return@map child
                        normalizeLegacyPart(childObject, childKind, inferenceFlags)
                    },
                )
            }
        }
        return JsonObject(normalized)
    }

    private suspend fun computeLegacySourceDigest(
        conversationId: String,
        headers: List<LegacyMessageNodeHeader>,
    ): String {
        val digest = ConversationV2LegacySourceDigest(conversationId, headers.size)
        headers.forEach { header ->
            digest.addNode(
                nodeId = header.id,
                nodeIndex = header.nodeIndex,
                selectIndex = header.selectIndex,
                messageLength = header.messageLength,
                rawMessages = readLegacyMessages(header),
            )
        }
        return digest.finish()
    }

    private suspend fun readLegacyMessages(header: LegacyMessageNodeHeader): String {
        if (header.messageLength == 0L) return ""
        val result = StringBuilder(header.messageLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var start = 1L
        while (start <= header.messageLength) {
            val chunk = migrationDAO.getLegacyMessagesChunk(header.id, start, MESSAGE_CHUNK_SIZE)
                ?: throw LegacySourceChangedException(header.id)
            result.append(chunk)
            val remaining = header.messageLength - start + 1
            val expectedCodePoints = minOf(MESSAGE_CHUNK_SIZE.toLong(), remaining).toInt()
            if (chunk.codePointCount(0, chunk.length) != expectedCodePoints) {
                throw LegacySourceChangedException(header.id)
            }
            start += expectedCodePoints
        }
        return result.toString()
    }

    private suspend fun quarantine(conversationId: String, issue: QuarantineIssue) {
        database.withTransaction {
            val rawDigest = issue.rawPayload?.let(::sha256Hex)
            val now = nowMillis()
            migrationDAO.insertQuarantine(
                ConversationMigrationQuarantineEntity(
                    quarantineId = deterministicConversationV2Id(
                        "quarantine",
                        conversationId,
                        issue.nodeId.orEmpty(),
                        issue.variantIndex?.toString().orEmpty(),
                        issue.reasonCode,
                        rawDigest.orEmpty(),
                    ),
                    conversationId = conversationId,
                    nodeId = issue.nodeId,
                    variantIndex = issue.variantIndex,
                    payloadDigest = rawDigest,
                    rawPayload = issue.rawPayload?.takeIf { it.length <= MAX_QUARANTINE_PAYLOAD_CHARS },
                    reasonCode = issue.reasonCode,
                    detail = buildString {
                        issue.detail?.let { append(it.take(MAX_ERROR_DETAIL_CHARS)) }
                        if (issue.rawPayload != null && issue.rawPayload.length > MAX_QUARANTINE_PAYLOAD_CHARS) {
                            if (isNotEmpty()) append(' ')
                            append("; raw payload retained in legacy message_node, chars=")
                            append(issue.rawPayload.length)
                        }
                    }.takeIf(String::isNotBlank),
                    createdAt = now,
                ),
            )
            if (
                migrationDAO.markQuarantined(
                    conversationId = conversationId,
                    workerId = workerId,
                    reasonCode = issue.reasonCode,
                    detail = issue.detail?.take(MAX_ERROR_DETAIL_CHARS),
                    now = now,
                ) != 1
            ) {
                throw LeaseLostException()
            }
        }
        Log.w(TAG, "Conversation $conversationId quarantined: ${issue.reasonCode}")
    }

    private fun decodeFlags(raw: String): MutableSet<String> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }
    }.getOrDefault(mutableSetOf())

    private fun encodeFlags(flags: Set<String>): String = JsonArray(
        flags.sorted().map(::JsonPrimitive),
    ).toCanonicalJson()

    private enum class PrepareOutcome { READY_TO_COPY, ALREADY_READY, LEASE_LOST }
    private enum class StepOutcome { NODE_WRITTEN, NO_MORE_NODES }
    private enum class VerificationPreparation { READY, SOURCE_RESET }
    private enum class BackfillOutcome { READY, ALREADY_READY, QUARANTINED, IN_PROGRESS, FAILED, LEASE_LOST }

    private data class PreparedNode(
        val group: MessageBranchGroupEntity?,
        val messages: List<ConversationMessageEntity>,
        val parts: List<MessagePartEntity>,
        val selectedMessageId: String?,
        val inferenceFlags: Set<String>,
    )

    private data class PreparedLegacyConversation(
        val sourceDigest: String,
        val activeLeafMessageId: String?,
        val graph: ConversationV2Graph,
    )

    private class QuarantineIssue(
        val reasonCode: String,
        val nodeId: String?,
        val variantIndex: Int?,
        val rawPayload: String?,
        val detail: String?,
    ) : RuntimeException(reasonCode)

    private class LeaseLostException : RuntimeException()

    private class LegacySourceChangedException(nodeId: String) :
        IllegalStateException("Legacy node $nodeId changed while being read")

    private companion object {
        const val DEFAULT_MAX_CONVERSATIONS = 64
        const val LEASE_BATCH_SIZE = 8
        const val LEASE_DURATION_MS = 2 * 60 * 1000L
        const val RETRY_DELAY_MS = 30 * 1000L
        const val MESSAGE_CHUNK_SIZE = 256 * 1024
        const val MAX_QUARANTINE_PAYLOAD_CHARS = 64 * 1024
        const val MAX_ERROR_DETAIL_CHARS = 1_000
        const val LEGACY_LOCAL_DATE_TIME_FALLBACK = "1970-01-01T00:00:00"
        const val LEGACY_INSTANT_FALLBACK = "1970-01-01T00:00:00Z"

        const val INFERENCE_PARENT_PATH = "LEGACY_SELECTED_PATH_PARENT_INFERRED"
        const val INFERENCE_STATE = "MESSAGE_STATE_INFERRED"
        const val INFERENCE_MESSAGE_ID_REPAIRED = "MESSAGE_ID_REPAIRED"
        const val INFERENCE_DUPLICATE_MESSAGE_ID = "DUPLICATE_MESSAGE_ID"
        const val INFERENCE_MESSAGE_CREATED_AT = "MESSAGE_CREATED_AT_REPAIRED"
        const val INFERENCE_REASONING_TIMESTAMPS = "REASONING_TIMESTAMPS_REPAIRED"

        val KNOWN_MESSAGE_FIELDS = setOf(
            "id",
            "role",
            "parts",
            "annotations",
            "createdAt",
            "finishedAt",
            "modelId",
            "usage",
            "translation",
        )

        val LEGACY_NODE_INSERT_TRIGGER = legacyNodeInvalidationTrigger(
            name = "conversation_v2_invalidate_message_node_insert",
            timing = "AFTER INSERT",
            conversationExpression = "NEW.conversation_id",
        )
        val LEGACY_NODE_DELETE_TRIGGER = legacyNodeInvalidationTrigger(
            name = "conversation_v2_invalidate_message_node_delete",
            timing = "AFTER DELETE",
            conversationExpression = "OLD.conversation_id",
        )
        val LEGACY_NODE_UPDATE_TRIGGER = legacyNodeInvalidationTrigger(
            name = "conversation_v2_invalidate_message_node_update",
            timing = "AFTER UPDATE",
            conversationExpression = "NEW.conversation_id",
        )
        val LEGACY_STORAGE_DOWNGRADE_TRIGGER =
            """
            CREATE TRIGGER IF NOT EXISTS conversation_v2_invalidate_legacy_conversation_write
            AFTER UPDATE OF nodes, revision, storage_version ON ConversationEntity
            WHEN NEW.storage_version = 1
                AND COALESCE(NEW.last_writer_replica_id, '') != '$CONVERSATION_V2_INTERNAL_WRITER_MARKER'
                AND (OLD.storage_version = 2 OR NEW.revision <= OLD.revision)
            BEGIN
                UPDATE ConversationEntity
                SET revision = CASE
                        WHEN revision <= OLD.revision THEN OLD.revision + 1
                        ELSE revision
                    END,
                    active_leaf_message_id = NULL
                WHERE id = NEW.id;
                UPDATE conversation_migration_journal
                SET phase = 'PENDING', legacy_source_digest = NULL,
                    legacy_projection_digest = NULL, v2_projection_digest = NULL,
                    next_node_index = 0, previous_selected_message_id = NULL,
                    expected_group_count = NULL, expected_message_count = NULL,
                    expected_part_count = NULL, written_group_count = 0,
                    written_message_count = 0, written_part_count = 0,
                    inference_flags_json = '[]', last_error_code = 'LEGACY_WRITE_INVALIDATED_SHADOW',
                    last_error_detail = NULL, lease_owner = NULL, lease_until = NULL,
                    updated_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
                WHERE conversation_id = NEW.id;
            END
            """.trimIndent()

        fun legacyNodeInvalidationTrigger(
            name: String,
            timing: String,
            conversationExpression: String,
        ): String =
            """
            CREATE TRIGGER IF NOT EXISTS $name
            $timing ON message_node
            WHEN COALESCE((
                SELECT last_writer_replica_id
                FROM ConversationEntity
                WHERE id = $conversationExpression
            ), '') != '$CONVERSATION_V2_INTERNAL_WRITER_MARKER'
            BEGIN
                UPDATE ConversationEntity
                SET storage_version = 1, active_leaf_message_id = NULL,
                    revision = revision + 1,
                    last_writer_replica_id = '$CONVERSATION_V2_INTERNAL_WRITER_MARKER'
                WHERE id = $conversationExpression AND storage_version = 2;
                UPDATE conversation_migration_journal
                SET phase = 'PENDING', legacy_source_digest = NULL,
                    legacy_projection_digest = NULL, v2_projection_digest = NULL,
                    next_node_index = 0, previous_selected_message_id = NULL,
                    expected_group_count = NULL, expected_message_count = NULL,
                    expected_part_count = NULL, written_group_count = 0,
                    written_message_count = 0, written_part_count = 0,
                    inference_flags_json = '[]', last_error_code = 'LEGACY_WRITE_INVALIDATED_SHADOW',
                    last_error_detail = NULL, lease_owner = NULL, lease_until = NULL,
                    updated_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
                WHERE conversation_id = $conversationExpression;
                UPDATE ConversationEntity
                SET last_writer_replica_id = NULL
                WHERE id = $conversationExpression
                    AND last_writer_replica_id = '$CONVERSATION_V2_INTERNAL_WRITER_MARKER';
            END
            """.trimIndent()

        val INVALIDATION_TRIGGER_NAMES = listOf(
            "conversation_v2_invalidate_message_node_insert",
            "conversation_v2_invalidate_message_node_update",
            "conversation_v2_invalidate_message_node_delete",
            "conversation_v2_invalidate_legacy_conversation_write",
            "conversation_v2_invalidate_storage_downgrade",
        )
    }
}

private fun UIMessagePart.backfillMediaAssetIdOrNull(): String? = when (this) {
    is UIMessagePart.Image -> assetId
    is UIMessagePart.Video -> assetId
    is UIMessagePart.Audio -> assetId
    is UIMessagePart.Document -> assetId
    else -> null
}

data class ConversationV2BackfillSummary(
    val inspected: Int,
    val ready: Int,
    val quarantined: Int,
    val inProgress: Int,
    val failed: Int,
)

private fun UIMessagePart.toolInvocationIdOrNull(): String? =
    (this as? UIMessagePart.Tool)?.toolCallId?.takeIf(String::isNotBlank)

private fun Throwable.safeDetail(): String = this::class.java.simpleName.ifBlank { "UnknownError" }
