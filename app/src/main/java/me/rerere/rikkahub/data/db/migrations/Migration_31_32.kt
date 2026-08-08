package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive Memory V2 projection. Legacy rows remain readable during the compatibility window. */
val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_record_v2` (
                `memory_id` TEXT NOT NULL, `legacy_id` INTEGER, `type` TEXT NOT NULL,
                `scope_kind` TEXT NOT NULL, `scope_id` TEXT NOT NULL,
                `canonical_statement` TEXT NOT NULL, `source_refs_json` TEXT NOT NULL,
                `source_trust` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
                `confirmed_at` INTEGER, `last_used_at` INTEGER, `expires_at` INTEGER,
                `confidence` REAL NOT NULL, `sensitivity` TEXT NOT NULL, `status` TEXT NOT NULL,
                `revision` INTEGER NOT NULL, `supersedes_json` TEXT NOT NULL,
                `conflicts_with_json` TEXT NOT NULL, `extraction_policy_version` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL, PRIMARY KEY(`memory_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_audit_event_v2` (
                `event_id` TEXT NOT NULL, `memory_id` TEXT NOT NULL, `event_kind` TEXT NOT NULL,
                `revision` INTEGER NOT NULL, `payload_digest` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL, PRIMARY KEY(`event_id`)
            )
            """.trimIndent(),
        )
        listOf(
            "CREATE INDEX IF NOT EXISTS `index_memory_record_v2_scope_kind_scope_id_status` ON `memory_record_v2` (`scope_kind`, `scope_id`, `status`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_record_v2_type_status` ON `memory_record_v2` (`type`, `status`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_record_v2_expires_at` ON `memory_record_v2` (`expires_at`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_record_v2_legacy_id` ON `memory_record_v2` (`legacy_id`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_audit_event_v2_memory_id_created_at` ON `memory_audit_event_v2` (`memory_id`, `created_at`)",
            "CREATE INDEX IF NOT EXISTS `index_memory_audit_event_v2_event_kind_created_at` ON `memory_audit_event_v2` (`event_kind`, `created_at`)",
        ).forEach(db::execSQL)
        db.execSQL(
            """
            INSERT OR IGNORE INTO memory_record_v2 (
                memory_id, legacy_id, type, scope_kind, scope_id, canonical_statement,
                source_refs_json, source_trust, created_at, confidence, sensitivity,
                status, revision, supersedes_json, conflicts_with_json,
                extraction_policy_version, updated_at
            )
            SELECT 'legacy:' || id, id, 'fact',
                CASE WHEN assistant_id = '__global__' THEN 'user' ELSE 'assistant' END,
                assistant_id, content, '["legacy_memory:' || id || '"]', 'legacy_manual',
                0, 1.0, 'normal', 'active', 1, '[]', '[]', 1, 0
            FROM MemoryEntity
            """.trimIndent(),
        )
    }
}
