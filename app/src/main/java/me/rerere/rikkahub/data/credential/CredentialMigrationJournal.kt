package me.rerere.rikkahub.data.credential

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

enum class CredentialMigrationStage {
    PREPARE,
    ENVELOPE_VERIFIED,
    REFERENCES_WRITTEN,
    LEGACY_CLEARED,
}

data class CredentialMigrationRecord(
    val migrationId: String,
    val slotId: CredentialSlotId,
    val refId: CredentialRefId,
    val stage: CredentialMigrationStage,
)

/** Durable, secret-free state machine for re-entrant legacy-to-reference migration. */
class CredentialMigrationJournal(root: File) {
    private val lock = Any()
    private val files = AtomicCredentialFiles(root)
    private val fileName = "migration-journal.v1"
    private val magic = byteArrayOf(0x52, 0x4b, 0x43, 0x4d, 0x49, 0x47, 0x31, 0x00) // RKCMIG1

    fun prepare(migrationId: String, slotId: CredentialSlotId, refId: CredentialRefId): CredentialMigrationRecord =
        synchronized(lock) {
            require(migrationId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
            val records = load().toMutableMap()
            records[migrationId]?.let { existing ->
                require(existing.slotId == slotId && existing.refId == refId) { "Migration id was reused" }
                return@synchronized existing
            }
            val record = CredentialMigrationRecord(migrationId, slotId, refId, CredentialMigrationStage.PREPARE)
            records[migrationId] = record
            store(records.values)
            record
        }

    fun advance(migrationId: String, target: CredentialMigrationStage): CredentialMigrationRecord = synchronized(lock) {
        val records = load().toMutableMap()
        val current = requireNotNull(records[migrationId]) { "Migration was not prepared" }
        if (current.stage == target) return@synchronized current
        require(target.ordinal == current.stage.ordinal + 1) {
            "Invalid migration transition ${current.stage} -> $target"
        }
        val next = current.copy(stage = target)
        records[migrationId] = next
        store(records.values)
        next
    }

    fun get(migrationId: String): CredentialMigrationRecord? = synchronized(lock) { load()[migrationId] }

    fun incomplete(): List<CredentialMigrationRecord> = synchronized(lock) {
        load().values.filter { it.stage != CredentialMigrationStage.LEGACY_CLEARED }
    }

    /** Drops a transaction that DataStore never made authoritative. Its immutable envelope may
     * remain as an unreachable orphan and can be reclaimed by a later bounded vault GC. */
    fun discard(migrationId: String) = synchronized(lock) {
        val records = load().toMutableMap()
        if (records.remove(migrationId) != null) store(records.values)
    }

    private fun load(): Map<String, CredentialMigrationRecord> {
        val bytes = files.read(fileName) ?: return emptyMap()
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val actualMagic = ByteArray(magic.size).also(input::readFully)
            require(actualMagic.contentEquals(magic)) { "Credential migration journal magic mismatch" }
            require(input.readInt() == 1)
            val count = input.readInt().also { require(it in 0..100_000) }
            val values = List(count) {
                CredentialMigrationRecord(
                    migrationId = input.readUTF(),
                    slotId = CredentialSlotId.stored(input.readUTF()),
                    refId = CredentialRefId.stored(input.readUTF()),
                    stage = input.readStage(),
                )
            }
            require(input.read() == -1) { "Trailing migration journal bytes" }
            require(values.map { it.migrationId }.distinct().size == values.size)
            values.associateBy { it.migrationId }
        }
    }

    private fun store(records: Collection<CredentialMigrationRecord>) {
        val sorted = records.sortedBy { it.migrationId }
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(magic)
                output.writeInt(1)
                output.writeInt(sorted.size)
                sorted.forEach { record ->
                    output.writeUTF(record.migrationId)
                    output.writeUTF(record.slotId.value)
                    output.writeUTF(record.refId.value)
                    output.writeInt(record.stage.ordinal)
                }
            }
            buffer.toByteArray()
        }
        files.writeVerified(fileName, bytes) { candidate -> verify(candidate, sorted.size) }
    }

    private fun verify(candidate: ByteArray, expectedCount: Int) {
        DataInputStream(ByteArrayInputStream(candidate)).use { input ->
            val candidateMagic = ByteArray(magic.size).also(input::readFully)
            require(candidateMagic.contentEquals(magic))
            require(input.readInt() == 1)
            val count = input.readInt()
            repeat(count) {
                input.readUTF()
                CredentialSlotId.stored(input.readUTF())
                CredentialRefId.stored(input.readUTF())
                input.readStage()
            }
            require(input.read() == -1)
            require(count == expectedCount)
        }
    }

    private fun DataInputStream.readStage(): CredentialMigrationStage {
        val ordinal = readInt()
        require(ordinal in CredentialMigrationStage.entries.indices) { "Unknown credential migration stage" }
        return CredentialMigrationStage.entries[ordinal]
    }
}
