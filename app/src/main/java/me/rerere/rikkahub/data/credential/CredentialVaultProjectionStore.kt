package me.rerere.rikkahub.data.credential

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Bridges the pure Settings JSON projection to the device vault.
 *
 * A slot is resolved exactly once under the vault lock before deciding whether to reuse or rotate
 * it. Audience changes deliberately fail closed: credentials are never forwarded to a different
 * endpoint merely because the logical settings field kept the same id.
 */
internal class CredentialVaultProjectionStore(
    private val vault: CredentialVault,
    private val journal: CredentialMigrationJournal,
) : CredentialSettingsProjectionStore {
    /** Metadata-only proof used by Settings transaction planning. Secret bytes never leave here. */
    fun inspectBinding(reference: String): CredentialBindingProof? {
        val refId = CredentialRefId.parseReference(reference) ?: return null
        return when (val resolved = vault.resolve(refId)) {
            is CredentialReadResult.Found -> try {
                CredentialBindingProof(
                    reference = reference,
                    revision = resolved.value.revision,
                    slotId = resolved.value.slotId,
                    audience = resolved.value.audience,
                )
            } finally {
                resolved.value.secret.fill(0)
            }
            else -> null
        }
    }

    /**
     * Restores references that are still authoritative in DataStore when a vault-first transaction
     * did not reach the atomic DataStore commit. This is intentionally re-entrant and is also run
     * during startup, covering process death between the index swap and preferences commit.
     */
    fun rollbackUncommittedBindings(committedReferencesBySlot: Map<String, String>) {
        journal.incomplete()
            .forEach { record ->
                val committedRefId = committedReferencesBySlot[record.slotId.value]
                    ?.let { CredentialRefId.parseReference(it) }
                val pending = vault.resolve(record.refId)
                if (committedRefId == record.refId) {
                    if (pending is CredentialReadResult.Found) {
                        try {
                            val active = vault.resolveActive(record.slotId, pending.value.audience)
                            if (active is CredentialReadResult.Found) {
                                try {
                                    if (active.value.refId == record.refId) finishJournal(record)
                                } finally {
                                    active.value.secret.fill(0)
                                }
                            }
                        } finally {
                            pending.value.secret.fill(0)
                        }
                    }
                    return@forEach
                }
                if (pending !is CredentialReadResult.Found) {
                    // Crash after PREPARE but before the immutable envelope became durable.
                    journal.discard(record.migrationId)
                    return@forEach
                }
                try {
                    if (pending.value.slotId != record.slotId) {
                        return@forEach
                    }
                    val pendingExpectation = CredentialExpectation(record.refId, pending.value.revision)
                    val activePending = vault.resolveActive(record.slotId, pending.value.audience)
                    val pendingIsActive = if (activePending is CredentialReadResult.Found) {
                        try {
                            activePending.value.refId == record.refId
                        } finally {
                            activePending.value.secret.fill(0)
                        }
                    } else {
                        false
                    }
                    if (pendingIsActive) {
                        if (committedRefId == null) {
                            if (!vault.removeActiveReference(record.slotId, pendingExpectation)) return@forEach
                        } else {
                            val restored = vault.restoreActiveReference(committedRefId, pendingExpectation)
                            if (restored !is CredentialWriteResult.Written) return@forEach
                        }
                    } else if (committedRefId != null) {
                        val committed = vault.resolve(committedRefId)
                        if (committed !is CredentialReadResult.Found) return@forEach
                        try {
                            val active = vault.resolveActive(record.slotId, committed.value.audience)
                            if (active !is CredentialReadResult.Found) return@forEach
                            try {
                                if (active.value.refId != committedRefId) return@forEach
                            } finally {
                                active.value.secret.fill(0)
                            }
                        } finally {
                            committed.value.secret.fill(0)
                        }
                    }
                    // The prepared reference was never committed to DataStore and is therefore
                    // abandoned. A retry always allocates another immutable reference.
                    journal.discard(record.migrationId)
                } finally {
                    pending.value.secret.fill(0)
                }
            }
    }

    /** Called only after the DataStore edit containing every returned reference has committed. */
    fun completePersistence(bindings: Collection<CredentialSettingsBinding>) {
        bindings.distinctBy { it.reference }.forEach { binding ->
            val refId = CredentialRefId.parseReference(binding.reference) ?: return@forEach
            val migrationId = migrationId(binding.address.slotId(), refId)
            var record = journal.get(migrationId) ?: return@forEach
            if (record.stage == CredentialMigrationStage.ENVELOPE_VERIFIED) {
                record = journal.advance(migrationId, CredentialMigrationStage.REFERENCES_WRITTEN)
            }
            if (record.stage == CredentialMigrationStage.REFERENCES_WRITTEN) {
                journal.advance(migrationId, CredentialMigrationStage.LEGACY_CLEARED)
            }
        }
    }

    override fun seal(
        address: CredentialSettingsAddress,
        secret: JsonElement,
    ): CredentialSettingsSealResult {
        val vaultAddress = address.toVaultAddress()
        val secretBytes = secret.toString().toByteArray(StandardCharsets.UTF_8)
        return try {
            when (val active = vault.resolveActive(vaultAddress.slotId, vaultAddress.audience)) {
                is CredentialReadResult.Found -> {
                    try {
                        if (active.value.secret.contentEquals(secretBytes)) {
                            markEnvelopeVerifiedIfPrepared(vaultAddress.slotId, active.value.refId)
                            CredentialSettingsSealResult.Stored(
                                reference = active.value.refId.referenceString(),
                                revision = active.value.revision,
                            )
                        } else {
                            replacePrepared(
                                address = vaultAddress,
                                previous = CredentialExpectation(active.value.refId, active.value.revision),
                                secret = secretBytes,
                                revisioned = vaultAddress.usesRevisionedReplacement(),
                            )
                        }
                    } finally {
                        active.value.secret.fill(0)
                    }
                }

                CredentialReadResult.Missing -> createPrepared(vaultAddress, secretBytes)
                is CredentialReadResult.Locked -> CredentialSettingsSealResult.Locked(active.reason.name)
                is CredentialReadResult.Corrupt -> CredentialSettingsSealResult.Failed(active.reason)
            }
        } finally {
            secretBytes.fill(0)
        }
    }

    /**
     * Explicit recovery path for an endpoint/audience edit.
     *
     * Normal [seal] remains fail-closed. A settings screen may call this only after asking the
     * user to enter the credential again, carrying the old binding as the compare-and-set proof.
     */
    fun rebindAudience(
        address: CredentialSettingsAddress,
        expectedReference: String,
        expectedRevision: Long,
        replacementSecret: JsonElement,
    ): CredentialSettingsSealResult {
        val expectedRefId = CredentialRefId.parseReference(expectedReference)
            ?: return CredentialSettingsSealResult.Failed("Invalid previous credential reference")
        val vaultAddress = address.toVaultAddress()
        val secretBytes = replacementSecret.toString().toByteArray(StandardCharsets.UTF_8)
        return try {
            // Reject stale/mismatched proofs before allocating a journal entry. In particular, an
            // unfinished initial migration for the old reference must never be mistaken for the
            // new immutable rebind envelope.
            val expectedProof = inspectBinding(expectedReference)
                ?: return CredentialSettingsSealResult.Failed("Previous credential proof is unavailable")
            if (
                expectedProof.slotId != vaultAddress.slotId ||
                expectedProof.revision != expectedRevision ||
                expectedProof.audience == vaultAddress.audience
            ) {
                return CredentialSettingsSealResult.Failed("Stale credential proof or unchanged audience")
            }
            val expectedActive = vault.resolveActive(vaultAddress.slotId, expectedProof.audience)
            if (expectedActive !is CredentialReadResult.Found) {
                return CredentialSettingsSealResult.Failed("Previous credential is no longer active")
            }
            try {
                if (
                    expectedActive.value.refId != expectedRefId ||
                    expectedActive.value.revision != expectedRevision
                ) {
                    return CredentialSettingsSealResult.Failed("Stale credential proof")
                }
            } finally {
                expectedActive.value.secret.fill(0)
            }
            val expected = CredentialExpectation(expectedRefId, expectedRevision)
            val newRefId = CredentialRefId.new()
            journal.prepare(migrationId(vaultAddress.slotId, newRefId), vaultAddress.slotId, newRefId)
            when (
                val result = vault.rebindAudience(
                    address = vaultAddress,
                    expected = expected,
                    replacementSecret = secretBytes,
                    refId = newRefId,
                )
            ) {
                is CredentialWriteResult.Written -> {
                    markEnvelopeVerifiedIfPrepared(vaultAddress.slotId, result.refId)
                    CredentialSettingsSealResult.Stored(result.reference, result.revision)
                }
                is CredentialWriteResult.Orphaned -> when (
                    val recovered = vault.recoverOrphan(vaultAddress, result.refId, expected)
                ) {
                    is CredentialWriteResult.Written -> {
                        markEnvelopeVerifiedIfPrepared(vaultAddress.slotId, recovered.refId)
                        CredentialSettingsSealResult.Stored(recovered.reference, recovered.revision)
                    }
                    else -> recovered.toProjectionResult(vaultAddress)
                }
                else -> result.toProjectionResult(vaultAddress)
            }
        } finally {
            secretBytes.fill(0)
        }
    }

    private fun createPrepared(
        address: CredentialAddress,
        secret: ByteArray,
    ): CredentialSettingsSealResult {
        val refId = CredentialRefId.new().also { newRef ->
            journal.prepare(migrationId(address.slotId, newRef), address.slotId, newRef)
        }
        return when (val result = vault.create(address, secret, refId)) {
            is CredentialWriteResult.Written -> {
                markEnvelopeVerifiedIfPrepared(address.slotId, result.refId)
                CredentialSettingsSealResult.Stored(result.reference, result.revision)
            }
            is CredentialWriteResult.Orphaned -> when (val recovered = vault.recoverOrphan(address, result.refId)) {
                is CredentialWriteResult.Written -> {
                    markEnvelopeVerifiedIfPrepared(address.slotId, recovered.refId)
                    CredentialSettingsSealResult.Stored(recovered.reference, recovered.revision)
                }
                else -> recovered.toProjectionResult(address)
            }
            else -> result.toProjectionResult(address)
        }
    }

    private fun replacePrepared(
        address: CredentialAddress,
        previous: CredentialExpectation,
        secret: ByteArray,
        revisioned: Boolean,
    ): CredentialSettingsSealResult {
        val refId = CredentialRefId.new()
        journal.prepare(migrationId(address.slotId, refId), address.slotId, refId)
        val write = if (revisioned) {
            vault.compareAndSet(
                refId = requireNotNull(previous.refId),
                expectedRevision = requireNotNull(previous.revision),
                secret = secret,
                nextRefId = refId,
            )
        } else {
            vault.rotate(address, previous, secret, refId)
        }
        return when (write) {
            is CredentialWriteResult.Written -> {
                markEnvelopeVerifiedIfPrepared(address.slotId, write.refId)
                CredentialSettingsSealResult.Stored(write.reference, write.revision)
            }
            is CredentialWriteResult.Orphaned -> when (
                val recovered = vault.recoverOrphan(address, write.refId, previous)
            ) {
                is CredentialWriteResult.Written -> {
                    markEnvelopeVerifiedIfPrepared(address.slotId, recovered.refId)
                    CredentialSettingsSealResult.Stored(recovered.reference, recovered.revision)
                }
                else -> recovered.toProjectionResult(address)
            }
            else -> write.toProjectionResult(address)
        }
    }

    private fun markEnvelopeVerifiedIfPrepared(slotId: CredentialSlotId, refId: CredentialRefId) {
        val id = migrationId(slotId, refId)
        if (journal.get(id)?.stage == CredentialMigrationStage.PREPARE) {
            journal.advance(id, CredentialMigrationStage.ENVELOPE_VERIFIED)
        }
    }

    private fun finishJournal(record: CredentialMigrationRecord) {
        var current = journal.get(record.migrationId) ?: return
        if (current.stage == CredentialMigrationStage.PREPARE) {
            current = journal.advance(record.migrationId, CredentialMigrationStage.ENVELOPE_VERIFIED)
        }
        if (current.stage == CredentialMigrationStage.ENVELOPE_VERIFIED) {
            current = journal.advance(record.migrationId, CredentialMigrationStage.REFERENCES_WRITTEN)
        }
        if (current.stage == CredentialMigrationStage.REFERENCES_WRITTEN) {
            journal.advance(record.migrationId, CredentialMigrationStage.LEGACY_CLEARED)
        }
    }

    private fun migrationId(slotId: CredentialSlotId, refId: CredentialRefId): String =
        "settings-v4:${slotId.value}:${refId.value}"

    override fun resolve(
        reference: String,
        address: CredentialSettingsAddress,
    ): CredentialSettingsResolveResult = when (val result = vault.resolve(reference)) {
        is CredentialReadResult.Found -> {
            try {
                if (
                    result.value.slotId != address.slotId() ||
                    result.value.kind != address.kind ||
                    result.value.audience != address.audience
                ) {
                    CredentialSettingsResolveResult.Missing
                } else {
                    CredentialSettingsResolveResult.Found(
                        secret = Json.parseToJsonElement(String(result.value.secret, StandardCharsets.UTF_8)),
                        revision = result.value.revision,
                    )
                }
            } finally {
                result.value.secret.fill(0)
            }
        }

        CredentialReadResult.Missing -> CredentialSettingsResolveResult.Missing
        is CredentialReadResult.Locked -> CredentialSettingsResolveResult.Locked(result.reason.name)
        is CredentialReadResult.Corrupt -> CredentialSettingsResolveResult.Corrupt(result.reason)
    }

    private fun CredentialWriteResult.toProjectionResult(
        address: CredentialAddress,
    ): CredentialSettingsSealResult = when (this) {
        is CredentialWriteResult.Written -> CredentialSettingsSealResult.Stored(reference, revision)
        is CredentialWriteResult.Orphaned -> when (val recovered = vault.recoverOrphan(address, refId)) {
            is CredentialWriteResult.Written -> CredentialSettingsSealResult.Stored(recovered.reference, recovered.revision)
            is CredentialWriteResult.Locked -> CredentialSettingsSealResult.Locked(recovered.reason.name)
            is CredentialWriteResult.Conflict -> CredentialSettingsSealResult.Failed("Credential slot changed concurrently")
            is CredentialWriteResult.Orphaned -> CredentialSettingsSealResult.Failed(recovered.reason)
            is CredentialWriteResult.Failed -> CredentialSettingsSealResult.Failed(recovered.reason)
        }
        is CredentialWriteResult.Conflict -> CredentialSettingsSealResult.Failed(
            "Credential slot already exists with another audience or changed concurrently",
        )
        is CredentialWriteResult.Locked -> CredentialSettingsSealResult.Locked(reason.name)
        is CredentialWriteResult.Failed -> CredentialSettingsSealResult.Failed(reason)
    }

    private fun CredentialSettingsAddress.toVaultAddress() = CredentialAddress(
        slotId = slotId(),
        namespace = namespace,
        ownerStableId = ownerStableId,
        fieldSlot = fieldSlot,
        kind = kind,
        audience = audience,
    )

    private fun CredentialAddress.usesRevisionedReplacement(): Boolean =
        namespace == "settings.mcpServers" && fieldSlot in MCP_OAUTH_REVISIONED_SLOTS

    private companion object {
        val MCP_OAUTH_REVISIONED_SLOTS = setOf("accesstoken", "refreshtoken")
    }
}

internal data class CredentialBindingProof(
    val reference: String,
    val revision: Long,
    val slotId: CredentialSlotId,
    val audience: String,
)

/** Ensures vault-first writes are either committed to DataStore or restored to its last snapshot. */
internal class CredentialProjectionCommitter(
    private val store: CredentialVaultProjectionStore,
) {
    suspend fun <T> commit(
        previousReferencesBySlot: Map<String, String>,
        projectedBindings: Collection<CredentialSettingsBinding>,
        writeDataStore: suspend () -> T,
    ): T {
        val result = try {
            writeDataStore()
        } catch (failure: Throwable) {
            runCatching { store.rollbackUncommittedBindings(previousReferencesBySlot) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
        // DataStore is authoritative from this point onward. A journal cleanup failure must not
        // roll the vault back behind the already committed references; startup will finish it.
        store.completePersistence(projectedBindings)
        return result
    }
}

/**
 * Deliberate user-authorized endpoint rebind. The replacement value must come from a fresh UI
 * entry; constructing this intent from a previously resolved runtime secret is forbidden.
 */
internal data class CredentialAudienceRebindIntent(
    val address: CredentialSettingsAddress,
    val expectedReference: String,
    val expectedRevision: Long,
    val replacementSecret: JsonElement,
)

/** Safe UI projection for an audience edit. It deliberately carries no resolved credential. */
internal data class CredentialAudienceRebindCandidate(
    val address: CredentialSettingsAddress,
    val expectedReference: String,
    val expectedRevision: Long,
    val jsonPath: String,
)
