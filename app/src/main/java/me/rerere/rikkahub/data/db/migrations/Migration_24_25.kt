package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds durable model identity and makes generated-media path the idempotency key. */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `GenMediaEntity` ADD COLUMN `model_display_name` TEXT")
        db.execSQL("ALTER TABLE `GenMediaEntity` ADD COLUMN `provider_id` TEXT")

        // Old versions could replay a pending sidecar after the row was already
        // committed. Keep the first row (and therefore its stable gallery id)
        // before enforcing the exactly-once key.
        db.execSQL(
            """
            DELETE FROM `GenMediaEntity`
            WHERE `id` NOT IN (
                SELECT MIN(`id`) FROM `GenMediaEntity` GROUP BY `path`
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_GenMediaEntity_path` " +
                "ON `GenMediaEntity` (`path`)",
        )
    }
}
