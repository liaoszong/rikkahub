package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the ConversationStore v2 shadow schema.
 *
 * This migration is deliberately metadata-only: legacy message JSON remains untouched and
 * every existing conversation is queued for a restartable post-open backfill.
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        extendConversationCompatibilityProjection(db)
        createConversationV2Tables(db)
        createConversationV2Indices(db)
        seedConversationMigrationJournal(db)
    }
}

private fun extendConversationCompatibilityProjection(db: SupportSQLiteDatabase) {
    db.execSQL(
        "ALTER TABLE `ConversationEntity` ADD COLUMN `revision` " +
            "INTEGER NOT NULL DEFAULT 0",
    )
    db.execSQL(
        "ALTER TABLE `ConversationEntity` ADD COLUMN `active_leaf_message_id` TEXT",
    )
    db.execSQL(
        "ALTER TABLE `ConversationEntity` ADD COLUMN `storage_version` " +
            "INTEGER NOT NULL DEFAULT 1",
    )
    db.execSQL("ALTER TABLE `ConversationEntity` ADD COLUMN `deleted_at` INTEGER")
    db.execSQL(
        "ALTER TABLE `ConversationEntity` ADD COLUMN `last_writer_replica_id` TEXT",
    )
}

private fun createConversationV2Tables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `message_branch_group` (
            `conversation_id` TEXT NOT NULL,
            `branch_group_id` TEXT NOT NULL,
            `legacy_node_index` INTEGER,
            `legacy_order` INTEGER,
            `created_at` TEXT NOT NULL,
            `revision` INTEGER NOT NULL DEFAULT 0,
            `legacy_inferred` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`conversation_id`, `branch_group_id`),
            FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `conversation_message` (
            `conversation_id` TEXT NOT NULL,
            `message_id` TEXT NOT NULL,
            `parent_message_id` TEXT,
            `branch_group_id` TEXT NOT NULL,
            `sibling_ordinal` INTEGER NOT NULL,
            `origin_conversation_id` TEXT,
            `origin_message_id` TEXT,
            `legacy_message_id` TEXT,
            `request_id` TEXT,
            `role` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `model_id` TEXT,
            `provider_id` TEXT,
            `provider_response_id` TEXT,
            `created_at` TEXT NOT NULL,
            `finished_at` TEXT,
            `usage_json` TEXT,
            `annotations_json` TEXT NOT NULL DEFAULT '[]',
            `translation` TEXT,
            `envelope_extras_json` TEXT,
            `revision` INTEGER NOT NULL DEFAULT 0,
            `content_digest` TEXT NOT NULL,
            `legacy_inferred` INTEGER NOT NULL DEFAULT 0,
            `deleted_at` INTEGER,
            PRIMARY KEY(`conversation_id`, `message_id`),
            FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`conversation_id`, `branch_group_id`)
                REFERENCES `message_branch_group`(`conversation_id`, `branch_group_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`conversation_id`, `parent_message_id`)
                REFERENCES `conversation_message`(`conversation_id`, `message_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `message_part` (
            `conversation_id` TEXT NOT NULL,
            `part_id` TEXT NOT NULL,
            `message_id` TEXT NOT NULL,
            `ordinal` INTEGER NOT NULL,
            `kind` TEXT NOT NULL,
            `schema_version` INTEGER NOT NULL DEFAULT 1,
            `payload_json` TEXT NOT NULL,
            `payload_digest` TEXT NOT NULL,
            `asset_id` TEXT,
            `tool_invocation_id` TEXT,
            `revision` INTEGER NOT NULL DEFAULT 0,
            `deleted_at` INTEGER,
            PRIMARY KEY(`conversation_id`, `part_id`),
            FOREIGN KEY(`conversation_id`, `message_id`)
                REFERENCES `conversation_message`(`conversation_id`, `message_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `conversation_migration_journal` (
            `conversation_id` TEXT NOT NULL,
            `phase` TEXT NOT NULL DEFAULT 'PENDING',
            `source_revision` INTEGER NOT NULL DEFAULT 0,
            `legacy_source_digest` TEXT,
            `legacy_projection_digest` TEXT,
            `v2_projection_digest` TEXT,
            `next_node_index` INTEGER NOT NULL DEFAULT 0,
            `previous_selected_message_id` TEXT,
            `expected_group_count` INTEGER,
            `expected_message_count` INTEGER,
            `expected_part_count` INTEGER,
            `written_group_count` INTEGER NOT NULL DEFAULT 0,
            `written_message_count` INTEGER NOT NULL DEFAULT 0,
            `written_part_count` INTEGER NOT NULL DEFAULT 0,
            `inference_flags_json` TEXT NOT NULL DEFAULT '[]',
            `attempts` INTEGER NOT NULL DEFAULT 0,
            `last_error_code` TEXT,
            `last_error_detail` TEXT,
            `lease_owner` TEXT,
            `lease_until` INTEGER,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`conversation_id`),
            FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `conversation_migration_quarantine` (
            `quarantine_id` TEXT NOT NULL,
            `conversation_id` TEXT NOT NULL,
            `node_id` TEXT,
            `variant_index` INTEGER,
            `payload_digest` TEXT,
            `raw_payload` TEXT,
            `reason_code` TEXT NOT NULL,
            `detail` TEXT,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`quarantine_id`),
            FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `message_fts_outbox` (
            `event_id` TEXT NOT NULL,
            `conversation_id` TEXT NOT NULL,
            `target_revision` INTEGER NOT NULL,
            `operation` TEXT NOT NULL,
            `state` TEXT NOT NULL DEFAULT 'PENDING',
            `attempts` INTEGER NOT NULL DEFAULT 0,
            `next_attempt_at` INTEGER NOT NULL DEFAULT 0,
            `lease_owner` TEXT,
            `lease_until` INTEGER,
            `last_error` TEXT,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`event_id`)
        )
        """.trimIndent(),
    )
}

private fun createConversationV2Indices(db: SupportSQLiteDatabase) {
    val statements = listOf(
        "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_deleted_at_is_pinned_update_at` " +
            "ON `ConversationEntity` (`deleted_at`, `is_pinned`, `update_at`)",
        "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_storage_version` " +
            "ON `ConversationEntity` (`storage_version`)",
        "CREATE INDEX IF NOT EXISTS `index_message_branch_group_conversation_id_legacy_order` " +
            "ON `message_branch_group` (`conversation_id`, `legacy_order`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_conversation_message_conversation_id_branch_group_id_sibling_ordinal` " +
            "ON `conversation_message` (`conversation_id`, `branch_group_id`, `sibling_ordinal`)",
        "CREATE INDEX IF NOT EXISTS `index_conversation_message_conversation_id_parent_message_id` " +
            "ON `conversation_message` (`conversation_id`, `parent_message_id`)",
        "CREATE INDEX IF NOT EXISTS `index_conversation_message_conversation_id_branch_group_id` " +
            "ON `conversation_message` (`conversation_id`, `branch_group_id`)",
        "CREATE INDEX IF NOT EXISTS `index_conversation_message_request_id` " +
            "ON `conversation_message` (`request_id`)",
        "CREATE INDEX IF NOT EXISTS `index_conversation_message_origin_conversation_id_origin_message_id` " +
            "ON `conversation_message` (`origin_conversation_id`, `origin_message_id`)",
        "CREATE INDEX IF NOT EXISTS `index_conversation_message_conversation_id_state` " +
            "ON `conversation_message` (`conversation_id`, `state`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_part_conversation_id_message_id_ordinal` " +
            "ON `message_part` (`conversation_id`, `message_id`, `ordinal`)",
        "CREATE INDEX IF NOT EXISTS `index_message_part_asset_id` ON `message_part` (`asset_id`)",
        "CREATE INDEX IF NOT EXISTS `index_message_part_tool_invocation_id` " +
            "ON `message_part` (`tool_invocation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_conversation_migration_journal_phase_lease_until` " +
            "ON `conversation_migration_journal` (`phase`, `lease_until`)",
        "CREATE INDEX IF NOT EXISTS `index_conversation_migration_quarantine_conversation_id` " +
            "ON `conversation_migration_quarantine` (`conversation_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "`index_message_fts_outbox_conversation_id_target_revision_operation` " +
            "ON `message_fts_outbox` (`conversation_id`, `target_revision`, `operation`)",
        "CREATE INDEX IF NOT EXISTS `index_message_fts_outbox_state_next_attempt_at` " +
            "ON `message_fts_outbox` (`state`, `next_attempt_at`)",
    )
    statements.forEach(db::execSQL)
}

private fun seedConversationMigrationJournal(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT OR IGNORE INTO `conversation_migration_journal` (
            `conversation_id`, `phase`, `source_revision`, `next_node_index`,
            `written_group_count`, `written_message_count`, `written_part_count`,
            `inference_flags_json`, `attempts`, `updated_at`
        )
        SELECT
            `id`, 'PENDING', `revision`, 0,
            0, 0, 0,
            '[]', 0, CAST(strftime('%s', 'now') AS INTEGER) * 1000
        FROM `ConversationEntity`
        WHERE `storage_version` = 1
        """.trimIndent(),
    )
}
