package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds MediaAsset v2 without moving or reading application files. Only relationships
 * already proven by v26 columns are copied; uncertain hashes and message ownership stay
 * explicitly journaled for the post-open reconciler.
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addStableManagedFileIdentity(db)
        extendCompatibilityProjection(db)
        createMediaV2Tables(db)
        backfillProvenMediaGraph(db)
        createMediaV2Indices(db)
    }
}

private fun addStableManagedFileIdentity(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `managed_files` ADD COLUMN `file_id` TEXT NOT NULL DEFAULT ''")
    db.execSQL(
        "UPDATE `managed_files` SET `file_id` = 'legacy-managed-file-' || `id` " +
            "WHERE `file_id` = ''",
    )
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_managed_files_file_id` " +
            "ON `managed_files` (`file_id`)",
    )
}

private fun extendCompatibilityProjection(db: SupportSQLiteDatabase) {
    db.execSQL(
        "ALTER TABLE `GenMediaEntity` ADD COLUMN `media_kind` " +
            "TEXT NOT NULL DEFAULT 'image'",
    )
    db.execSQL(
        "ALTER TABLE `GenMediaEntity` ADD COLUMN `display_name` " +
            "TEXT NOT NULL DEFAULT ''",
    )
    db.execSQL(
        "ALTER TABLE `GenMediaEntity` ADD COLUMN `lifecycle` " +
            "TEXT NOT NULL DEFAULT 'active'",
    )
    db.execSQL(
        "ALTER TABLE `GenMediaEntity` ADD COLUMN `privacy_scope` " +
            "TEXT NOT NULL DEFAULT 'private'",
    )
    db.execSQL(
        "ALTER TABLE `GenMediaEntity` ADD COLUMN `retention_policy` " +
            "TEXT NOT NULL DEFAULT 'library'",
    )
    db.execSQL("ALTER TABLE `GenMediaEntity` ADD COLUMN `deleted_at` INTEGER")
    db.execSQL(
        """
        UPDATE `GenMediaEntity`
        SET `display_name` = COALESCE(
            (SELECT managed.`display_name` FROM `managed_files` AS managed
             WHERE managed.`id` = `GenMediaEntity`.`managed_file_id`),
            `path`
        )
        WHERE `display_name` = ''
        """.trimIndent(),
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_media_kind_lifecycle_create_at` " +
            "ON `GenMediaEntity` (`media_kind`, `lifecycle`, `create_at`)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_retention_policy_lifecycle` " +
            "ON `GenMediaEntity` (`retention_policy`, `lifecycle`)",
    )
}

private fun createMediaV2Tables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_blob` (
            `blob_id` TEXT NOT NULL,
            `sha256` TEXT,
            `mime_type` TEXT NOT NULL,
            `size_bytes` INTEGER NOT NULL,
            `width` INTEGER,
            `height` INTEGER,
            `duration_ms` INTEGER,
            `storage_state` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            `verified_at` INTEGER,
            PRIMARY KEY(`blob_id`)
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_asset_blob` (
            `asset_id` TEXT NOT NULL,
            `blob_id` TEXT NOT NULL,
            `role` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`asset_id`, `blob_id`, `role`),
            FOREIGN KEY(`asset_id`) REFERENCES `GenMediaEntity`(`asset_id`)
                ON UPDATE CASCADE ON DELETE CASCADE,
            FOREIGN KEY(`blob_id`) REFERENCES `media_blob`(`blob_id`)
                ON UPDATE CASCADE ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_replica` (
            `replica_id` TEXT NOT NULL,
            `blob_id` TEXT NOT NULL,
            `kind` TEXT NOT NULL,
            `managed_file_id` TEXT,
            `remote_locator` TEXT,
            `etag` TEXT,
            `state` TEXT NOT NULL,
            `encrypted` INTEGER NOT NULL,
            `verified_at` INTEGER,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`replica_id`),
            FOREIGN KEY(`blob_id`) REFERENCES `media_blob`(`blob_id`)
                ON UPDATE CASCADE ON DELETE NO ACTION,
            FOREIGN KEY(`managed_file_id`) REFERENCES `managed_files`(`file_id`)
                ON UPDATE CASCADE ON DELETE SET NULL
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_relation` (
            `relation_id` TEXT NOT NULL,
            `asset_id` TEXT NOT NULL,
            `related_asset_id` TEXT NOT NULL,
            `relation_kind` TEXT NOT NULL,
            `ordinal` INTEGER NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`relation_id`),
            FOREIGN KEY(`asset_id`) REFERENCES `GenMediaEntity`(`asset_id`)
                ON UPDATE CASCADE ON DELETE CASCADE,
            FOREIGN KEY(`related_asset_id`) REFERENCES `GenMediaEntity`(`asset_id`)
                ON UPDATE CASCADE ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `message_media_ref` (
            `ref_id` TEXT NOT NULL,
            `owner_key` TEXT NOT NULL,
            `asset_id` TEXT NOT NULL,
            `conversation_id` TEXT,
            `message_node_id` TEXT,
            `message_id` TEXT,
            `part_id` TEXT,
            `tool_call_id` TEXT,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`ref_id`),
            FOREIGN KEY(`asset_id`) REFERENCES `GenMediaEntity`(`asset_id`)
                ON UPDATE CASCADE ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `media_migration_journal` (
            `journal_id` TEXT NOT NULL,
            `scope_kind` TEXT NOT NULL,
            `scope_key` TEXT NOT NULL,
            `stage` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `detail` TEXT,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`journal_id`)
        )
        """.trimIndent(),
    )
}

private fun backfillProvenMediaGraph(db: SupportSQLiteDatabase) {
    val validSha = "sha256 IS NOT NULL AND length(sha256) = 64 " +
        "AND lower(sha256) NOT GLOB '*[^0-9a-f]*'"
    val blobId = "CASE WHEN $validSha THEN 'sha256:' || lower(sha256) " +
        "ELSE 'legacy-media-blob-' || asset_id END"
    val blobState = "CASE storage_state " +
        "WHEN 'available' THEN 'available' WHEN 'missing' THEN 'missing' " +
        "WHEN 'corrupt' THEN 'corrupt' ELSE 'staging' END"

    db.execSQL(
        """
        INSERT OR IGNORE INTO `media_blob` (
            `blob_id`, `sha256`, `mime_type`, `size_bytes`, `width`, `height`,
            `duration_ms`, `storage_state`, `created_at`, `verified_at`
        )
        SELECT
            'sha256:' || lower(sha256),
            lower(sha256),
            MIN(mime_type),
            MAX(size_bytes),
            MAX(width),
            MAX(height),
            NULL,
            CASE
                WHEN MAX(CASE WHEN storage_state = 'available' THEN 1 ELSE 0 END) = 1 THEN 'available'
                WHEN MAX(CASE WHEN storage_state = 'corrupt' THEN 1 ELSE 0 END) = 1 THEN 'corrupt'
                WHEN MAX(CASE WHEN storage_state = 'missing' THEN 1 ELSE 0 END) = 1 THEN 'missing'
                ELSE 'staging'
            END,
            MIN(create_at),
            MAX(CASE WHEN storage_state = 'available' THEN updated_at ELSE NULL END)
        FROM `GenMediaEntity`
        WHERE $validSha
        GROUP BY lower(sha256)
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT OR IGNORE INTO `media_blob` (
            `blob_id`, `sha256`, `mime_type`, `size_bytes`, `width`, `height`,
            `duration_ms`, `storage_state`, `created_at`, `verified_at`
        )
        SELECT
            'legacy-media-blob-' || asset_id,
            NULL,
            mime_type,
            size_bytes,
            width,
            height,
            NULL,
            $blobState,
            create_at,
            NULL
        FROM `GenMediaEntity`
        WHERE NOT ($validSha)
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT OR IGNORE INTO `media_asset_blob` (`asset_id`, `blob_id`, `role`, `created_at`)
        SELECT asset_id, $blobId, 'original', updated_at
        FROM `GenMediaEntity`
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT OR IGNORE INTO `media_replica` (
            `replica_id`, `blob_id`, `kind`, `managed_file_id`, `remote_locator`,
            `etag`, `state`, `encrypted`, `verified_at`, `created_at`, `updated_at`
        )
        SELECT
            'legacy-media-replica-' || managed.file_id,
            $blobId,
            'local_managed',
            managed.file_id,
            NULL,
            NULL,
            $blobState,
            0,
            CASE WHEN $validSha AND media.storage_state = 'available' THEN media.updated_at ELSE NULL END,
            managed.created_at,
            managed.updated_at
        FROM `GenMediaEntity` AS media
        INNER JOIN `managed_files` AS managed ON managed.id = media.managed_file_id
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT OR IGNORE INTO `media_relation` (
            `relation_id`, `asset_id`, `related_asset_id`, `relation_kind`, `ordinal`, `created_at`
        )
        SELECT
            'legacy-media-relation-' || child.asset_id || '-edit-of',
            child.asset_id,
            child.parent_asset_id,
            'edit_of',
            0,
            child.updated_at
        FROM `GenMediaEntity` AS child
        INNER JOIN `GenMediaEntity` AS parent ON parent.asset_id = child.parent_asset_id
        WHERE child.parent_asset_id IS NOT NULL AND child.parent_asset_id <> child.asset_id
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT OR IGNORE INTO `message_media_ref` (
            `ref_id`, `owner_key`, `asset_id`, `conversation_id`, `message_node_id`,
            `message_id`, `part_id`, `tool_call_id`, `created_at`
        )
        SELECT
            'legacy-media-ref-' || asset_id,
            'legacy-v1|' || COALESCE(conversation_id, '') || '|' ||
                COALESCE(message_node_id, '') || '|' || COALESCE(tool_call_id, ''),
            asset_id,
            conversation_id,
            message_node_id,
            NULL,
            NULL,
            tool_call_id,
            updated_at
        FROM `GenMediaEntity`
        WHERE conversation_id IS NOT NULL OR message_node_id IS NOT NULL OR tool_call_id IS NOT NULL
        """.trimIndent(),
    )

    db.execSQL(
        """
        INSERT OR IGNORE INTO `media_migration_journal` (
            `journal_id`, `scope_kind`, `scope_key`, `stage`, `state`, `detail`, `updated_at`
        )
        SELECT
            'legacy-media-journal-' || asset_id || '-blob',
            'asset',
            asset_id,
            'blob_backfill',
            CASE WHEN $validSha THEN 'complete' ELSE 'pending' END,
            CASE WHEN $validSha THEN NULL ELSE 'sha256_verification_required' END,
            updated_at
        FROM `GenMediaEntity`
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT OR IGNORE INTO `media_migration_journal` (
            `journal_id`, `scope_kind`, `scope_key`, `stage`, `state`, `detail`, `updated_at`
        )
        SELECT
            'legacy-media-journal-' || asset_id || '-reference',
            'asset',
            asset_id,
            'reference_backfill',
            CASE WHEN conversation_id IS NOT NULL OR message_node_id IS NOT NULL OR tool_call_id IS NOT NULL
                THEN 'complete' ELSE 'pending' END,
            CASE WHEN conversation_id IS NOT NULL OR message_node_id IS NOT NULL OR tool_call_id IS NOT NULL
                THEN NULL ELSE 'message_scan_required' END,
            updated_at
        FROM `GenMediaEntity`
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT OR IGNORE INTO `media_migration_journal` (
            `journal_id`, `scope_kind`, `scope_key`, `stage`, `state`, `detail`, `updated_at`
        )
        SELECT
            'legacy-media-journal-' || file_id || '-relocation',
            'file',
            file_id,
            'file_relocation',
            'pending',
            'lazy_verification_and_relocation_required',
            updated_at
        FROM `managed_files`
        """.trimIndent(),
    )
}

private fun createMediaV2Indices(db: SupportSQLiteDatabase) {
    val statements = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_blob_sha256` ON `media_blob` (`sha256`)",
        "CREATE INDEX IF NOT EXISTS `index_media_blob_storage_state` ON `media_blob` (`storage_state`)",
        "CREATE INDEX IF NOT EXISTS `index_media_asset_blob_blob_id` ON `media_asset_blob` (`blob_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_asset_blob_asset_id_role` " +
            "ON `media_asset_blob` (`asset_id`, `role`)",
        "CREATE INDEX IF NOT EXISTS `index_media_replica_blob_id` ON `media_replica` (`blob_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_replica_managed_file_id` " +
            "ON `media_replica` (`managed_file_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_replica_remote_locator` " +
            "ON `media_replica` (`remote_locator`)",
        "CREATE INDEX IF NOT EXISTS `index_media_replica_kind_state` " +
            "ON `media_replica` (`kind`, `state`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_relation_asset_id_relation_kind_ordinal` " +
            "ON `media_relation` (`asset_id`, `relation_kind`, `ordinal`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_relation_asset_id_related_asset_id_relation_kind` " +
            "ON `media_relation` (`asset_id`, `related_asset_id`, `relation_kind`)",
        "CREATE INDEX IF NOT EXISTS `index_media_relation_related_asset_id` " +
            "ON `media_relation` (`related_asset_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_media_ref_owner_key_asset_id` " +
            "ON `message_media_ref` (`owner_key`, `asset_id`)",
        "CREATE INDEX IF NOT EXISTS `index_message_media_ref_asset_id` " +
            "ON `message_media_ref` (`asset_id`)",
        "CREATE INDEX IF NOT EXISTS `index_message_media_ref_conversation_id` " +
            "ON `message_media_ref` (`conversation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_message_media_ref_message_node_id` " +
            "ON `message_media_ref` (`message_node_id`)",
        "CREATE INDEX IF NOT EXISTS `index_message_media_ref_message_id` " +
            "ON `message_media_ref` (`message_id`)",
        "CREATE INDEX IF NOT EXISTS `index_message_media_ref_tool_call_id` " +
            "ON `message_media_ref` (`tool_call_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_media_migration_journal_scope_kind_scope_key_stage` " +
            "ON `media_migration_journal` (`scope_kind`, `scope_key`, `stage`)",
        "CREATE INDEX IF NOT EXISTS `index_media_migration_journal_state_updated_at` " +
            "ON `media_migration_journal` (`state`, `updated_at`)",
    )
    statements.forEach(db::execSQL)
}
