package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds immutable Memory V2 revision snapshots and backfills the current revision. */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_revision_v2` (
                `memory_id` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `canonical_statement` TEXT NOT NULL,
                `source_refs_json` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `supersedes_revision` INTEGER,
                `event_kind` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`memory_id`, `revision`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_revision_v2_memory_id_created_at` " +
                "ON `memory_revision_v2` (`memory_id`, `created_at`)"
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `memory_revision_v2` (
                `memory_id`, `revision`, `canonical_statement`, `source_refs_json`, `status`,
                `supersedes_revision`, `event_kind`, `created_at`
            )
            SELECT `memory_id`, `revision`, `canonical_statement`, `source_refs_json`, `status`,
                CASE WHEN `revision` > 1 THEN `revision` - 1 ELSE NULL END,
                'migration_snapshot', `updated_at`
            FROM `memory_record_v2`
            """.trimIndent()
        )
    }
}
