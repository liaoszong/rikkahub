package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds normalized, stable citation sources and message occurrences without rewriting legacy payloads. */
val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `citation_source` (
                `source_id` TEXT NOT NULL,
                `canonical_url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `publisher` TEXT,
                `retrieved_at` INTEGER,
                `snippet` TEXT,
                `content_hash` TEXT,
                `metadata_json` TEXT NOT NULL DEFAULT '{}',
                `record_digest` TEXT NOT NULL,
                `revision` INTEGER NOT NULL DEFAULT 0,
                `deleted_at` INTEGER,
                PRIMARY KEY(`source_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_citation` (
                `citation_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `message_id` TEXT NOT NULL,
                `source_id` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `display_title` TEXT NOT NULL DEFAULT '',
                `display_publisher` TEXT,
                `display_retrieved_at` INTEGER,
                `is_available` INTEGER NOT NULL DEFAULT 1,
                `text_start` INTEGER,
                `text_end` INTEGER,
                `text_part_ordinal` INTEGER,
                `offset_unit` TEXT NOT NULL DEFAULT 'unknown',
                `quote` TEXT,
                `provenance` TEXT NOT NULL,
                `provider_metadata_json` TEXT NOT NULL DEFAULT '{}',
                `record_digest` TEXT NOT NULL,
                `revision` INTEGER NOT NULL DEFAULT 0,
                `deleted_at` INTEGER,
                PRIMARY KEY(`citation_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`conversation_id`, `message_id`)
                    REFERENCES `conversation_message`(`conversation_id`, `message_id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`source_id`) REFERENCES `citation_source`(`source_id`)
                    ON UPDATE NO ACTION ON DELETE NO ACTION
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `citation_migration_journal` (
                `conversation_id` TEXT NOT NULL,
                `phase` TEXT NOT NULL DEFAULT 'PENDING',
                `source_revision` INTEGER NOT NULL,
                `projection_digest` TEXT,
                `citation_count` INTEGER NOT NULL DEFAULT 0,
                `attempts` INTEGER NOT NULL DEFAULT 0,
                `lease_owner` TEXT,
                `lease_until` INTEGER,
                `last_error` TEXT,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`conversation_id`),
                FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_citation_source_canonical_url` ON `citation_source` (`canonical_url`)",
            "CREATE INDEX IF NOT EXISTS `index_citation_source_retrieved_at` ON `citation_source` (`retrieved_at`)",
            "CREATE INDEX IF NOT EXISTS `index_citation_source_publisher` ON `citation_source` (`publisher`)",
            "CREATE INDEX IF NOT EXISTS `index_citation_source_content_hash` ON `citation_source` (`content_hash`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_citation_conversation_id_message_id_ordinal` " +
                "ON `message_citation` (`conversation_id`, `message_id`, `ordinal`)",
            "CREATE INDEX IF NOT EXISTS `index_message_citation_conversation_id_message_id` " +
                "ON `message_citation` (`conversation_id`, `message_id`)",
            "CREATE INDEX IF NOT EXISTS `index_message_citation_source_id` ON `message_citation` (`source_id`)",
            "CREATE INDEX IF NOT EXISTS `index_message_citation_conversation_id_source_id` " +
                "ON `message_citation` (`conversation_id`, `source_id`)",
            "CREATE INDEX IF NOT EXISTS `index_citation_migration_journal_phase_lease_until` " +
                "ON `citation_migration_journal` (`phase`, `lease_until`)",
        ).forEach(db::execSQL)
    }
}
