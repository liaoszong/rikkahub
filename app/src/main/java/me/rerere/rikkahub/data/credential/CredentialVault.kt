package me.rerere.rikkahub.data.credential

import java.io.File
import java.security.GeneralSecurityException

class CredentialVault(
    root: File,
    wrappingKeys: CredentialWrappingKeyProvider,
) {
    private val lock = Any()
    private val files = AtomicCredentialFiles(root)
    private val envelopes = CredentialEnvelopeCodec(wrappingKeys)
    private val references = CredentialReferenceIndex(files, wrappingKeys)

    fun create(
        address: CredentialAddress,
        secret: ByteArray,
        refId: CredentialRefId = CredentialRefId.new(),
    ): CredentialWriteResult = synchronized(lock) {
        val loaded = loadIndexForWrite()
        val index = loaded.index ?: return@synchronized requireNotNull(loaded.failure)
        val current = index[address.slotId]
        if (current != null) return@synchronized CredentialWriteResult.Conflict(current.refId, current.credentialRevision)
        val metadata = address.metadata(refId, revision = 1)
        writeNewEnvelope(metadata, secret)?.let { return@synchronized it }
        index[address.slotId] = ActiveCredentialReference(address.slotId, address.audience, refId, 1)
        storeIndex(index)?.let {
            return@synchronized CredentialWriteResult.Orphaned(refId, 1, it.reason, it.cause)
        }
        CredentialWriteResult.Written(refId, refId.referenceString(), 1)
    }

    /** Rotates the active slot to a new immutable reference after comparing the current reference and revision. */
    fun rotate(
        address: CredentialAddress,
        expected: CredentialExpectation,
        secret: ByteArray,
        refId: CredentialRefId = CredentialRefId.new(),
    ): CredentialWriteResult = synchronized(lock) {
        require(expected.refId != null && expected.revision != null) { "Rotation requires an active expectation" }
        val loaded = loadIndexForWrite()
        val index = loaded.index ?: return@synchronized requireNotNull(loaded.failure)
        val current = index[address.slotId]
        if (current?.refId != expected.refId || current.audience != address.audience) {
            return@synchronized CredentialWriteResult.Conflict(current?.refId, current?.credentialRevision)
        }
        val resolved = readEnvelope(current.refId)
        if (resolved !is CredentialReadResult.Found) return@synchronized resolved.asWriteFailure()
        try {
            if (
                resolved.value.slotId != address.slotId ||
                resolved.value.kind != address.kind ||
                resolved.value.audience != address.audience
            ) {
                return@synchronized CredentialWriteResult.Failed("Active credential reference metadata mismatch")
            }
            if (resolved.value.revision != expected.revision) {
                return@synchronized CredentialWriteResult.Conflict(current.refId, resolved.value.revision)
            }
        } finally {
            resolved.value.secret.fill(0)
        }
        val metadata = address.metadata(refId, revision = 1)
        writeNewEnvelope(metadata, secret)?.let { return@synchronized it }
        index[address.slotId] = ActiveCredentialReference(address.slotId, address.audience, refId, 1)
        storeIndex(index)?.let {
            return@synchronized CredentialWriteResult.Orphaned(refId, 1, it.reason, it.cause)
        }
        CredentialWriteResult.Written(refId, refId.referenceString(), 1)
    }

    /**
     * Explicitly binds a newly supplied secret to a changed audience.
     *
     * This is intentionally separate from [rotate]: callers must prove which active credential
     * they are replacing and must provide the replacement secret again. The vault never decrypts
     * the old value and forwards it to [address.audience].
     */
    fun rebindAudience(
        address: CredentialAddress,
        expected: CredentialExpectation,
        replacementSecret: ByteArray,
        refId: CredentialRefId = CredentialRefId.new(),
    ): CredentialWriteResult = synchronized(lock) {
        require(expected.refId != null && expected.revision != null) {
            "Audience rebind requires an active expectation"
        }
        val loaded = loadIndexForWrite()
        val index = loaded.index ?: return@synchronized requireNotNull(loaded.failure)
        val current = index[address.slotId]
        if (current == null || current.refId != expected.refId || current.credentialRevision != expected.revision) {
            return@synchronized CredentialWriteResult.Conflict(current?.refId, current?.credentialRevision)
        }
        if (current.audience == address.audience) {
            return@synchronized CredentialWriteResult.Failed("Audience rebind requires a changed audience")
        }
        val resolved = readEnvelope(current.refId)
        if (resolved !is CredentialReadResult.Found) return@synchronized resolved.asWriteFailure()
        try {
            if (
                resolved.value.slotId != address.slotId ||
                resolved.value.kind != address.kind ||
                resolved.value.audience != current.audience
            ) {
                return@synchronized CredentialWriteResult.Failed("Active credential reference metadata mismatch")
            }
            if (resolved.value.revision != expected.revision) {
                return@synchronized CredentialWriteResult.Conflict(current.refId, resolved.value.revision)
            }
        } finally {
            resolved.value.secret.fill(0)
        }

        val metadata = address.metadata(refId, revision = 1)
        writeNewEnvelope(metadata, replacementSecret)?.let { return@synchronized it }
        index[address.slotId] = ActiveCredentialReference(address.slotId, address.audience, refId, 1)
        storeIndex(index)?.let {
            return@synchronized CredentialWriteResult.Orphaned(refId, 1, it.reason, it.cause)
        }
        CredentialWriteResult.Written(refId, refId.referenceString(), 1)
    }

    /**
     * Compare-and-set refresh that preserves envelope immutability.
     *
     * A successful refresh advances the logical revision but writes a fresh reference. The old
     * authenticated envelope is retained so a failed Settings transaction can restore it.
     */
    fun compareAndSet(
        refId: CredentialRefId,
        expectedRevision: Long,
        secret: ByteArray,
        nextRefId: CredentialRefId = CredentialRefId.new(),
    ): CredentialWriteResult = synchronized(lock) {
        require(expectedRevision >= 1)
        val current = readEnvelope(refId)
        if (current !is CredentialReadResult.Found) return@synchronized current.asWriteFailure()
        val currentRevision = try {
            current.value.revision
        } finally {
            current.value.secret.fill(0)
        }
        if (currentRevision != expectedRevision) {
            return@synchronized CredentialWriteResult.Conflict(refId, currentRevision)
        }
        val loaded = loadIndexForWrite()
        val index = loaded.index ?: return@synchronized requireNotNull(loaded.failure)
        val oldMetadata = readMetadata(refId)
            ?: return@synchronized CredentialWriteResult.Failed("Envelope metadata unreadable")
        val active = index[oldMetadata.slotId]
        if (active?.refId != refId || active.credentialRevision != expectedRevision) {
            return@synchronized CredentialWriteResult.Conflict(active?.refId, active?.credentialRevision)
        }
        val next = oldMetadata.copy(refId = nextRefId, revision = expectedRevision + 1)
        writeNewEnvelope(next, secret)?.let { return@synchronized it }
        index[next.slotId] = active.copy(refId = nextRefId, credentialRevision = next.revision)
        storeIndex(index)?.let {
            return@synchronized CredentialWriteResult.Orphaned(nextRefId, next.revision, it.reason, it.cause)
        }
        CredentialWriteResult.Written(nextRefId, nextRefId.referenceString(), next.revision)
    }

    /** Removes a just-prepared active reference when the authoritative DataStore still has none. */
    fun removeActiveReference(slotId: CredentialSlotId, expected: CredentialExpectation): Boolean = synchronized(lock) {
        require(expected.refId != null && expected.revision != null)
        val loaded = loadIndexForWrite()
        val index = loaded.index ?: throw IllegalStateException(
            (loaded.failure as? CredentialWriteResult.Failed)?.reason ?: "Credential index unavailable",
        )
        val current = index[slotId] ?: return@synchronized true
        if (current.refId != expected.refId || current.credentialRevision != expected.revision) {
            return@synchronized false
        }
        index.remove(slotId)
        storeIndex(index)?.let { throw IllegalStateException(it.reason, it.cause) }
        true
    }

    fun resolve(reference: String): CredentialReadResult =
        CredentialRefId.parseReference(reference)?.let(::resolve) ?: CredentialReadResult.Missing

    fun resolve(refId: CredentialRefId): CredentialReadResult = synchronized(lock) { readEnvelope(refId) }

    /** Re-attaches a verified orphan left by an envelope-then-index crash window. */
    fun recoverOrphan(
        address: CredentialAddress,
        refId: CredentialRefId,
        replaceExpected: CredentialExpectation = CredentialExpectation.ABSENT,
    ): CredentialWriteResult = synchronized(lock) {
        val decoded = readEnvelope(refId)
        if (decoded !is CredentialReadResult.Found) return@synchronized decoded.asWriteFailure()
        try {
            if (
                decoded.value.slotId != address.slotId ||
                decoded.value.kind != address.kind ||
                decoded.value.audience != address.audience
            ) {
                return@synchronized CredentialWriteResult.Failed("Orphan credential metadata mismatch")
            }
            val loaded = loadIndexForWrite()
            val index = loaded.index ?: return@synchronized requireNotNull(loaded.failure)
            val current = index[address.slotId]
            if (replaceExpected == CredentialExpectation.ABSENT) {
                if (current != null && current.refId != refId) {
                    return@synchronized CredentialWriteResult.Conflict(current.refId, current.credentialRevision)
                }
            } else {
                val currentRequired = current
                    ?: return@synchronized CredentialWriteResult.Conflict(null, null)
                if (
                    currentRequired.refId != replaceExpected.refId ||
                    currentRequired.credentialRevision != replaceExpected.revision
                ) {
                    return@synchronized CredentialWriteResult.Conflict(
                        currentRequired.refId,
                        currentRequired.credentialRevision,
                    )
                }
            }
            index[address.slotId] = ActiveCredentialReference(
                address.slotId,
                address.audience,
                refId,
                decoded.value.revision,
            )
            storeIndex(index)?.let { return@synchronized it }
            CredentialWriteResult.Written(refId, refId.referenceString(), decoded.value.revision)
        } finally {
            decoded.value.secret.fill(0)
        }
    }

    /**
     * Restores an existing envelope as the active slot using its authenticated metadata.
     *
     * Recovery callers must never synthesize namespace/owner/field values from a slot hash: the
     * authenticated envelope is the only authority capable of reconstructing a valid address.
     */
    fun restoreActiveReference(
        refId: CredentialRefId,
        replaceExpected: CredentialExpectation,
    ): CredentialWriteResult = synchronized(lock) {
        val metadata = readMetadata(refId)
            ?: return@synchronized CredentialWriteResult.Failed("Credential metadata unavailable for recovery")
        recoverOrphan(
            address = CredentialAddress(
                slotId = metadata.slotId,
                namespace = metadata.namespace,
                ownerStableId = metadata.ownerStableId,
                fieldSlot = metadata.fieldSlot,
                kind = metadata.kind,
                audience = metadata.audience,
            ),
            refId = refId,
            replaceExpected = replaceExpected,
        )
    }

    fun resolveActive(slotId: CredentialSlotId, audience: String): CredentialReadResult = synchronized(lock) {
        val index = try {
            references.load()
        } catch (error: CredentialKeyException) {
            return@synchronized CredentialReadResult.Locked(error.reason, error)
        } catch (error: Throwable) {
            return@synchronized CredentialReadResult.Corrupt("Reference index unreadable", error)
        }
        val active = index[slotId]?.takeIf { it.audience == audience } ?: return@synchronized CredentialReadResult.Missing
        when (val resolved = readEnvelope(active.refId)) {
            is CredentialReadResult.Found -> {
                if (resolved.value.slotId != slotId || resolved.value.audience != audience) {
                    resolved.value.secret.fill(0)
                    CredentialReadResult.Corrupt("Active credential reference metadata mismatch")
                } else {
                    if (active.credentialRevision != resolved.value.revision) {
                        index[slotId] = active.copy(credentialRevision = resolved.value.revision)
                        runCatching { references.store(index.values) }
                    }
                    resolved
                }
            }
            else -> resolved
        }
    }

    private fun readEnvelope(refId: CredentialRefId): CredentialReadResult {
        val bytes = files.read(envelopeName(refId)) ?: return CredentialReadResult.Missing
        return try {
            val decoded = envelopes.decode(bytes)
            if (decoded.metadata.refId != refId) {
                decoded.secret.fill(0)
                error("Credential id does not match file name")
            }
            CredentialReadResult.Found(
                CredentialValue(
                    refId = decoded.metadata.refId,
                    slotId = decoded.metadata.slotId,
                    kind = decoded.metadata.kind,
                    audience = decoded.metadata.audience,
                    revision = decoded.metadata.revision,
                    secret = decoded.secret,
                ),
            )
        } catch (error: CredentialKeyException) {
            CredentialReadResult.Locked(error.reason, error)
        } catch (error: GeneralSecurityException) {
            CredentialReadResult.Corrupt("Credential authentication failed", error)
        } catch (error: Throwable) {
            CredentialReadResult.Corrupt("Credential envelope unreadable", error)
        }
    }

    private fun readMetadata(refId: CredentialRefId): CredentialEnvelopeMetadata? {
        val bytes = files.read(envelopeName(refId)) ?: return null
        return try {
            val decoded = envelopes.decode(bytes)
            try {
                decoded.metadata.takeIf { it.refId == refId }
            } finally {
                decoded.secret.fill(0)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeNewEnvelope(metadata: CredentialEnvelopeMetadata, secret: ByteArray): CredentialWriteResult? = try {
        require(!files.exists(envelopeName(metadata.refId))) { "Credential reference already has an immutable envelope" }
        val encoded = envelopes.encode(metadata, secret)
        files.writeVerified(envelopeName(metadata.refId), encoded) { candidate ->
            val decoded = envelopes.decode(candidate)
            try {
                require(decoded.metadata == metadata && decoded.secret.contentEquals(secret))
            } finally {
                decoded.secret.fill(0)
            }
        }
        null
    } catch (error: CredentialKeyException) {
        CredentialWriteResult.Locked(error.reason, error)
    } catch (error: Throwable) {
        CredentialWriteResult.Failed("Credential envelope write failed", error)
    }

    private data class IndexLoad(
        val index: MutableMap<CredentialSlotId, ActiveCredentialReference>? = null,
        val failure: CredentialWriteResult? = null,
    )

    private fun loadIndexForWrite(): IndexLoad = try {
        IndexLoad(index = references.load())
    } catch (error: CredentialKeyException) {
        IndexLoad(failure = CredentialWriteResult.Locked(error.reason, error))
    } catch (error: Throwable) {
        IndexLoad(failure = CredentialWriteResult.Failed("Reference index unreadable", error))
    }

    private fun storeIndex(index: Map<CredentialSlotId, ActiveCredentialReference>): CredentialWriteResult.Failed? =
        try {
            references.store(index.values)
            null
        } catch (error: Throwable) {
            CredentialWriteResult.Failed("Credential reference index write failed", error)
        }

    private fun CredentialAddress.metadata(refId: CredentialRefId, revision: Long) = CredentialEnvelopeMetadata(
        refId = refId,
        slotId = slotId,
        namespace = namespace,
        ownerStableId = ownerStableId,
        fieldSlot = fieldSlot,
        kind = kind,
        audience = audience,
        revision = revision,
    )

    private fun CredentialReadResult.asWriteFailure(): CredentialWriteResult = when (this) {
        is CredentialReadResult.Locked -> CredentialWriteResult.Locked(reason, cause)
        is CredentialReadResult.Corrupt -> CredentialWriteResult.Failed(reason, cause)
        CredentialReadResult.Missing -> CredentialWriteResult.Conflict(null, null)
        is CredentialReadResult.Found -> error("Found is not a failure")
    }

    private fun envelopeName(refId: CredentialRefId) = "credential.${refId.value}.v1"
}

fun isCredentialReference(value: String): Boolean = CredentialRefId.isReference(value)

fun parseCredentialReference(value: String): CredentialRefId? = CredentialRefId.parseReference(value)

fun CredentialRefId.asCredentialReference(): String = referenceString()
