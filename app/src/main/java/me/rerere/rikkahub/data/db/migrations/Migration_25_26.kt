package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Evolves the existing generated-media gallery into MediaAsset v1 without creating a
 * second source of truth. The historical table name and integer ids are retained so
 * old paging, backups and references continue to work.
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ManagedFile is the file identity authority. SQL cannot inspect file contents,
        // so legacy rows are registered with conservative metadata and marked for the
        // post-open reconciler to hydrate from disk.
        db.execSQL(
            """
            INSERT OR IGNORE INTO `managed_files` (
                `folder`, `relative_path`, `display_name`, `mime_type`, `size_bytes`,
                `created_at`, `updated_at`
            )
            SELECT
                CASE
                    WHEN instr(`path`, '/') > 0 THEN substr(`path`, 1, instr(`path`, '/') - 1)
                    ELSE 'images'
                END,
                `path`,
                CASE
                    WHEN instr(`path`, '/') > 0 THEN substr(`path`, instr(`path`, '/') + 1)
                    ELSE `path`
                END,
                CASE
                    WHEN lower(`path`) LIKE '%.png' THEN 'image/png'
                    WHEN lower(`path`) LIKE '%.jpg' OR lower(`path`) LIKE '%.jpeg' THEN 'image/jpeg'
                    WHEN lower(`path`) LIKE '%.webp' THEN 'image/webp'
                    WHEN lower(`path`) LIKE '%.gif' THEN 'image/gif'
                    ELSE 'application/octet-stream'
                END,
                0,
                `create_at`,
                `create_at`
            FROM `GenMediaEntity`
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `GenMediaEntity_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `path` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `model_display_name` TEXT,
                `provider_id` TEXT,
                `prompt` TEXT NOT NULL,
                `create_at` INTEGER NOT NULL,
                `type` TEXT NOT NULL DEFAULT 'image_generation',
                `source_paths` TEXT,
                `asset_id` TEXT NOT NULL,
                `managed_file_id` INTEGER,
                `origin` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL,
                `size_bytes` INTEGER NOT NULL,
                `width` INTEGER,
                `height` INTEGER,
                `sha256` TEXT,
                `storage_state` TEXT NOT NULL,
                `visibility` TEXT NOT NULL,
                `conversation_id` TEXT,
                `message_node_id` TEXT,
                `tool_call_id` TEXT,
                `parent_asset_id` TEXT,
                `updated_at` INTEGER NOT NULL,
                `hidden_at` INTEGER,
                `metadata_version` INTEGER NOT NULL,
                FOREIGN KEY(`managed_file_id`) REFERENCES `managed_files`(`id`)
                    ON UPDATE CASCADE ON DELETE SET NULL,
                FOREIGN KEY(`parent_asset_id`) REFERENCES `GenMediaEntity_new`(`asset_id`)
                    ON UPDATE CASCADE ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        // SQLite requires the referenced parent key to be unique before any DML
        // against a self-referencing foreign key. Index names are database-global,
        // so Room's final index name can already be used on the temporary table.
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_GenMediaEntity_asset_id` " +
                "ON `GenMediaEntity_new` (`asset_id`)",
        )

        db.execSQL(
            """
            INSERT INTO `GenMediaEntity_new` (
                `id`, `path`, `model_id`, `model_display_name`, `provider_id`, `prompt`,
                `create_at`, `type`, `source_paths`, `asset_id`, `managed_file_id`, `origin`,
                `mime_type`, `size_bytes`, `width`, `height`, `sha256`, `storage_state`,
                `visibility`, `conversation_id`, `message_node_id`, `tool_call_id`,
                `parent_asset_id`, `updated_at`, `hidden_at`, `metadata_version`
            )
            SELECT
                media.`id`,
                media.`path`,
                media.`model_id`,
                media.`model_display_name`,
                media.`provider_id`,
                media.`prompt`,
                media.`create_at`,
                media.`type`,
                media.`source_paths`,
                'legacy-genmedia-' || media.`id`,
                managed.`id`,
                CASE
                    WHEN media.`type` = 'image_edit' THEN 'ai_edited'
                    ELSE 'ai_generated'
                END,
                managed.`mime_type`,
                managed.`size_bytes`,
                NULL,
                NULL,
                NULL,
                'needs_metadata',
                'visible',
                NULL,
                NULL,
                NULL,
                NULL,
                media.`create_at`,
                NULL,
                1
            FROM `GenMediaEntity` AS media
            LEFT JOIN `managed_files` AS managed ON managed.`relative_path` = media.`path`
            """.trimIndent(),
        )

        // Chat-integrated image generation already wrote durable managed files before
        // MediaAsset v1 existed. Bring those files into the same gallery even though a
        // SQL migration cannot safely reconstruct provider/prompt/message context from
        // serialized conversation payloads.
        db.execSQL(
            """
            INSERT INTO `GenMediaEntity_new` (
                `path`, `model_id`, `model_display_name`, `provider_id`, `prompt`,
                `create_at`, `type`, `source_paths`, `asset_id`, `managed_file_id`, `origin`,
                `mime_type`, `size_bytes`, `width`, `height`, `sha256`, `storage_state`,
                `visibility`, `conversation_id`, `message_node_id`, `tool_call_id`,
                `parent_asset_id`, `updated_at`, `hidden_at`, `metadata_version`
            )
            SELECT
                managed.`relative_path`,
                'legacy-chat-image',
                NULL,
                NULL,
                '',
                managed.`created_at`,
                'image_generation',
                NULL,
                'legacy-chat-file-' || managed.`id`,
                managed.`id`,
                'ai_generated',
                managed.`mime_type`,
                managed.`size_bytes`,
                NULL,
                NULL,
                NULL,
                'needs_metadata',
                'visible',
                NULL,
                NULL,
                NULL,
                NULL,
                managed.`updated_at`,
                NULL,
                1
            FROM `managed_files` AS managed
            WHERE managed.`folder` = 'chat_generated_images'
              AND NOT EXISTS (
                  SELECT 1 FROM `GenMediaEntity_new` AS media
                  WHERE media.`path` = managed.`relative_path`
              )
            """.trimIndent(),
        )

        // Preserve the first historical edit parent when the old source_paths
        // points at another generated-media row. Additional references remain in
        // source_paths; v1 models one direct parent and therefore forms a version chain.
        db.execSQL(
            """
            UPDATE `GenMediaEntity_new` AS child
            SET `parent_asset_id` = (
                SELECT parent.`asset_id`
                FROM `GenMediaEntity_new` AS parent
                WHERE parent.`path` = CASE
                    WHEN instr(child.`source_paths`, char(10)) > 0
                        THEN substr(child.`source_paths`, 1, instr(child.`source_paths`, char(10)) - 1)
                    ELSE child.`source_paths`
                END
                AND parent.`id` <> child.`id`
                LIMIT 1
            )
            WHERE child.`type` = 'image_edit' AND child.`source_paths` IS NOT NULL
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE `GenMediaEntity`")
        db.execSQL("ALTER TABLE `GenMediaEntity_new` RENAME TO `GenMediaEntity`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_GenMediaEntity_path` " +
                "ON `GenMediaEntity` (`path`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_GenMediaEntity_asset_id` " +
                "ON `GenMediaEntity` (`asset_id`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_GenMediaEntity_managed_file_id` " +
                "ON `GenMediaEntity` (`managed_file_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_parent_asset_id` " +
                "ON `GenMediaEntity` (`parent_asset_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_conversation_id` " +
                "ON `GenMediaEntity` (`conversation_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_tool_call_id` " +
                "ON `GenMediaEntity` (`tool_call_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_visibility_create_at` " +
                "ON `GenMediaEntity` (`visibility`, `create_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_GenMediaEntity_storage_state` " +
                "ON `GenMediaEntity` (`storage_state`)",
        )
    }
}
