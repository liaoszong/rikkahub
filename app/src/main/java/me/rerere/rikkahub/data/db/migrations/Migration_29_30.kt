package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the local causal metadata and immutable outbox required by Sync protocol v2. */
val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createSyncV2Tables(db)
        createSyncV2Indices(db)
    }
}

private fun createSyncV2Tables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sync_replica` (
            `replica_id` TEXT NOT NULL,
            `space_id` TEXT NOT NULL,
            `sync_epoch` TEXT NOT NULL,
            `device_label` TEXT,
            `operation_counter` INTEGER NOT NULL DEFAULT 0,
            `hlc_physical_ms` INTEGER NOT NULL DEFAULT 0,
            `hlc_logical` INTEGER NOT NULL DEFAULT 0,
            `acknowledged_vector_json` TEXT NOT NULL DEFAULT '{}',
            `last_successful_sync_at` INTEGER,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`replica_id`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sync_record_head` (
            `space_id` TEXT NOT NULL,
            `sync_epoch` TEXT NOT NULL,
            `entity_type` TEXT NOT NULL,
            `entity_id` TEXT NOT NULL,
            `operation_id` TEXT NOT NULL,
            `dot_replica_id` TEXT NOT NULL,
            `dot_counter` INTEGER NOT NULL,
            `writer_replica_id` TEXT NOT NULL,
            `causal_vector_json` TEXT NOT NULL,
            `hlc_physical_ms` INTEGER NOT NULL,
            `hlc_logical` INTEGER NOT NULL,
            `payload_hash` TEXT,
            `tombstone` INTEGER NOT NULL DEFAULT 0,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`space_id`, `sync_epoch`, `entity_type`, `entity_id`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sync_outbox` (
            `operation_id` TEXT NOT NULL,
            `space_id` TEXT NOT NULL,
            `sync_epoch` TEXT NOT NULL,
            `replica_id` TEXT NOT NULL,
            `sequence` INTEGER NOT NULL,
            `entity_type` TEXT NOT NULL,
            `entity_id` TEXT NOT NULL,
            `base_vector_json` TEXT NOT NULL,
            `dot_counter` INTEGER NOT NULL,
            `hlc_physical_ms` INTEGER NOT NULL,
            `hlc_logical` INTEGER NOT NULL,
            `payload_hash` TEXT,
            `tombstone` INTEGER NOT NULL DEFAULT 0,
            `envelope_bytes` BLOB NOT NULL,
            `state` TEXT NOT NULL,
            `attempt_count` INTEGER NOT NULL DEFAULT 0,
            `next_attempt_at` INTEGER NOT NULL,
            `lease_owner` TEXT,
            `lease_until` INTEGER,
            `remote_etag` TEXT,
            `last_error` TEXT,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `uploaded_at` INTEGER,
            PRIMARY KEY(`operation_id`),
            FOREIGN KEY(`space_id`, `sync_epoch`, `replica_id`)
                REFERENCES `sync_replica`(`space_id`, `sync_epoch`, `replica_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`space_id`, `sync_epoch`, `entity_type`, `entity_id`)
                REFERENCES `sync_record_head`(`space_id`, `sync_epoch`, `entity_type`, `entity_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sync_conflict` (
            `conflict_id` TEXT NOT NULL,
            `space_id` TEXT NOT NULL,
            `sync_epoch` TEXT NOT NULL,
            `entity_type` TEXT NOT NULL,
            `entity_id` TEXT NOT NULL,
            `local_operation_id` TEXT NOT NULL,
            `remote_operation_id` TEXT NOT NULL,
            `base_vector_json` TEXT NOT NULL,
            `local_head_json` TEXT NOT NULL,
            `remote_head_json` TEXT NOT NULL,
            `classification` TEXT NOT NULL,
            `resolution_state` TEXT NOT NULL,
            `auto_mergeable` INTEGER NOT NULL DEFAULT 0,
            `resolved_operation_id` TEXT,
            `detected_at` INTEGER NOT NULL,
            `resolved_at` INTEGER,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`conflict_id`),
            FOREIGN KEY(`space_id`, `sync_epoch`, `entity_type`, `entity_id`)
                REFERENCES `sync_record_head`(`space_id`, `sync_epoch`, `entity_type`, `entity_id`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
}

private fun createSyncV2Indices(db: SupportSQLiteDatabase) {
    listOf(
        "CREATE INDEX IF NOT EXISTS `index_sync_replica_space_id` ON `sync_replica` (`space_id`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_replica_updated_at` ON `sync_replica` (`updated_at`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_replica_space_epoch_replica` " +
            "ON `sync_replica` (`space_id`, `sync_epoch`, `replica_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_record_head_operation_id` " +
            "ON `sync_record_head` (`operation_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_record_head_dot` " +
            "ON `sync_record_head` (`dot_replica_id`, `dot_counter`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_record_head_writer` " +
            "ON `sync_record_head` (`writer_replica_id`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_record_head_updated_at` " +
            "ON `sync_record_head` (`updated_at`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_replica_sequence` " +
            "ON `sync_outbox` (`space_id`, `sync_epoch`, `replica_id`, `sequence`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_record` " +
            "ON `sync_outbox` (`space_id`, `sync_epoch`, `entity_type`, `entity_id`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_due` " +
            "ON `sync_outbox` (`space_id`, `sync_epoch`, `replica_id`, `state`, `next_attempt_at`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_lease` ON `sync_outbox` (`lease_until`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_conflict_identity` " +
            "ON `sync_conflict` (`space_id`, `sync_epoch`, `entity_type`, `entity_id`, " +
            "`local_operation_id`, `remote_operation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_conflict_state` " +
            "ON `sync_conflict` (`resolution_state`, `updated_at`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_conflict_record` " +
            "ON `sync_conflict` (`space_id`, `sync_epoch`, `entity_type`, `entity_id`)",
    ).forEach(db::execSQL)
}
