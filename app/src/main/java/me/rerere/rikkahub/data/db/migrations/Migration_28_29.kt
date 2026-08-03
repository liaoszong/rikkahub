package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the additive Room 29 request, attempt, output, tool-permission, and audit ledger. */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        createRequestLedgerTables(db)
        createRequestLedgerIndices(db)
    }
}

private fun createRequestLedgerTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `request_ledger` (
            `request_id` TEXT NOT NULL,
            `intent_key` TEXT NOT NULL,
            `parent_request_id` TEXT,
            `request_kind` TEXT NOT NULL,
            `conversation_id` TEXT,
            `assistant_id` TEXT,
            `message_id` TEXT,
            `part_id` TEXT,
            `legacy_node_id` TEXT,
            `legacy_message_id` TEXT,
            `legacy_request_id` TEXT,
            `workspace_id` TEXT,
            `mcp_server_id` TEXT,
            `credential_ref_id` TEXT,
            `provider_kind` TEXT,
            `provider_id` TEXT,
            `model_id` TEXT,
            `api_surface` TEXT,
            `input_digest` TEXT NOT NULL,
            `capability_snapshot_json` TEXT NOT NULL,
            `resolver_version` INTEGER NOT NULL,
            `tool_catalog_digest` TEXT,
            `approval_state` TEXT NOT NULL,
            `request_state` TEXT NOT NULL,
            `billable_boundary` TEXT NOT NULL,
            `attempt_count` INTEGER NOT NULL DEFAULT 0,
            `active_attempt_id` TEXT,
            `lease_owner` TEXT,
            `lease_until` INTEGER,
            `fencing_epoch` INTEGER NOT NULL DEFAULT 0,
            `state_revision` INTEGER NOT NULL DEFAULT 0,
            `billable_at` INTEGER,
            `dispatch_at` INTEGER,
            `terminal_at` INTEGER,
            `remote_request_id` TEXT,
            `remote_response_id` TEXT,
            `usage_json` TEXT,
            `error_kind` TEXT,
            `error_code` TEXT,
            `error_message` TEXT,
            `unknown_outcome_reason` TEXT,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`request_id`),
            FOREIGN KEY(`parent_request_id`) REFERENCES `request_ledger`(`request_id`)
                ON UPDATE NO ACTION ON DELETE SET NULL,
            FOREIGN KEY(`request_id`, `active_attempt_id`)
                REFERENCES `request_attempt`(`request_id`, `attempt_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `request_attempt` (
            `attempt_id` TEXT NOT NULL,
            `request_id` TEXT NOT NULL,
            `attempt_ordinal` INTEGER NOT NULL,
            `idempotency_key` TEXT NOT NULL,
            `attempt_state` TEXT NOT NULL,
            `billable_boundary` TEXT NOT NULL,
            `transport_kind` TEXT,
            `request_fingerprint` TEXT NOT NULL,
            `owner_replica_id` TEXT,
            `foreground_task_id` TEXT,
            `remote_request_id` TEXT,
            `remote_response_id` TEXT,
            `prepared_at` INTEGER NOT NULL,
            `sent_at` INTEGER,
            `acknowledged_at` INTEGER,
            `first_byte_at` INTEGER,
            `result_received_at` INTEGER,
            `commit_started_at` INTEGER,
            `finished_at` INTEGER,
            `checkpoint_digest` TEXT,
            `error_kind` TEXT,
            `error_code` TEXT,
            `error_message` TEXT,
            `usage_json` TEXT,
            `state_revision` INTEGER NOT NULL DEFAULT 0,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`attempt_id`),
            FOREIGN KEY(`request_id`) REFERENCES `request_ledger`(`request_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `request_output` (
            `output_id` TEXT NOT NULL,
            `request_id` TEXT NOT NULL,
            `attempt_id` TEXT,
            `output_kind` TEXT NOT NULL,
            `ordinal` INTEGER NOT NULL,
            `conversation_id` TEXT,
            `message_id` TEXT,
            `part_id` TEXT,
            `asset_id` TEXT,
            `source_id` TEXT,
            `content_digest` TEXT NOT NULL,
            `committed_at` INTEGER NOT NULL,
            PRIMARY KEY(`output_id`),
            FOREIGN KEY(`request_id`) REFERENCES `request_ledger`(`request_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`request_id`, `attempt_id`)
                REFERENCES `request_attempt`(`request_id`, `attempt_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `tool_permission` (
            `permission_id` TEXT NOT NULL,
            `permission_key` TEXT NOT NULL,
            `source_request_id` TEXT,
            `principal_kind` TEXT NOT NULL,
            `principal_id` TEXT NOT NULL,
            `server_id` TEXT,
            `tool_name` TEXT NOT NULL,
            `action` TEXT NOT NULL,
            `schema_digest` TEXT NOT NULL,
            `decision` TEXT NOT NULL,
            `scope_kind` TEXT NOT NULL,
            `scope_id` TEXT,
            `constraints_json` TEXT NOT NULL,
            `capability_snapshot_json` TEXT NOT NULL,
            `policy_version` INTEGER NOT NULL,
            `reason` TEXT,
            `decided_at` INTEGER NOT NULL,
            `expires_at` INTEGER,
            `revoked_at` INTEGER,
            `state_revision` INTEGER NOT NULL DEFAULT 0,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`permission_id`),
            FOREIGN KEY(`source_request_id`) REFERENCES `request_ledger`(`request_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `tool_invocation` (
            `invocation_id` TEXT NOT NULL,
            `request_id` TEXT NOT NULL,
            `attempt_id` TEXT,
            `provider_tool_call_id` TEXT NOT NULL,
            `server_id` TEXT,
            `tool_name` TEXT NOT NULL,
            `principal_kind` TEXT NOT NULL,
            `principal_id` TEXT NOT NULL,
            `action` TEXT NOT NULL,
            `schema_digest` TEXT NOT NULL,
            `input_digest` TEXT NOT NULL,
            `side_effect_class` TEXT NOT NULL,
            `approval_state` TEXT NOT NULL,
            `execution_state` TEXT NOT NULL,
            `permission_id` TEXT,
            `result_digest` TEXT,
            `error_kind` TEXT,
            `error_code` TEXT,
            `created_at` INTEGER NOT NULL,
            `approved_at` INTEGER,
            `started_at` INTEGER,
            `finished_at` INTEGER,
            `state_revision` INTEGER NOT NULL DEFAULT 0,
            `updated_at` INTEGER NOT NULL,
            PRIMARY KEY(`invocation_id`),
            FOREIGN KEY(`request_id`) REFERENCES `request_ledger`(`request_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`request_id`, `attempt_id`)
                REFERENCES `request_attempt`(`request_id`, `attempt_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`permission_id`) REFERENCES `tool_permission`(`permission_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `request_audit_event` (
            `event_id` TEXT NOT NULL,
            `request_id` TEXT NOT NULL,
            `event_seq` INTEGER NOT NULL,
            `attempt_id` TEXT,
            `invocation_id` TEXT,
            `permission_id` TEXT,
            `event_kind` TEXT NOT NULL,
            `actor_kind` TEXT NOT NULL,
            `actor_id` TEXT,
            `payload_digest` TEXT NOT NULL,
            `payload_json` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`event_id`),
            FOREIGN KEY(`request_id`) REFERENCES `request_ledger`(`request_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`request_id`, `attempt_id`)
                REFERENCES `request_attempt`(`request_id`, `attempt_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`request_id`, `invocation_id`)
                REFERENCES `tool_invocation`(`request_id`, `invocation_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`invocation_id`) REFERENCES `tool_invocation`(`invocation_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`permission_id`) REFERENCES `tool_permission`(`permission_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `tool_audit_event` (
            `event_id` TEXT NOT NULL,
            `request_id` TEXT,
            `invocation_id` TEXT,
            `permission_id` TEXT,
            `event_kind` TEXT NOT NULL,
            `actor_kind` TEXT NOT NULL,
            `actor_id` TEXT,
            `summary` TEXT NOT NULL,
            `payload_digest` TEXT NOT NULL,
            `created_at` INTEGER NOT NULL,
            PRIMARY KEY(`event_id`),
            FOREIGN KEY(`request_id`) REFERENCES `request_ledger`(`request_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`request_id`, `invocation_id`)
                REFERENCES `tool_invocation`(`request_id`, `invocation_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`invocation_id`) REFERENCES `tool_invocation`(`invocation_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(`permission_id`) REFERENCES `tool_permission`(`permission_id`)
                ON UPDATE NO ACTION ON DELETE NO ACTION
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `request_migration_journal` (
            `journal_id` TEXT NOT NULL,
            `source_kind` TEXT NOT NULL,
            `source_id` TEXT NOT NULL,
            `phase` TEXT NOT NULL,
            `source_digest` TEXT,
            `expected_count` INTEGER,
            `migrated_count` INTEGER NOT NULL DEFAULT 0,
            `cursor_json` TEXT,
            `checkpoint_digest` TEXT,
            `legacy_retained` INTEGER NOT NULL DEFAULT 1,
            `attempts` INTEGER NOT NULL DEFAULT 0,
            `last_error_code` TEXT,
            `last_error_detail` TEXT,
            `lease_owner` TEXT,
            `lease_until` INTEGER,
            `fencing_epoch` INTEGER NOT NULL DEFAULT 0,
            `created_at` INTEGER NOT NULL,
            `updated_at` INTEGER NOT NULL,
            `completed_at` INTEGER,
            PRIMARY KEY(`journal_id`)
        )
        """.trimIndent(),
    )
}

private fun createRequestLedgerIndices(db: SupportSQLiteDatabase) {
    listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_ledger_intent_key` ON `request_ledger` (`intent_key`)",
        "CREATE INDEX IF NOT EXISTS `index_request_ledger_parent_request_id` ON `request_ledger` (`parent_request_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_ledger_conversation_id_created_at` ON `request_ledger` (`conversation_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_request_ledger_request_state_updated_at` ON `request_ledger` (`request_state`, `updated_at`)",
        "CREATE INDEX IF NOT EXISTS `index_request_ledger_billable_boundary_updated_at` ON `request_ledger` (`billable_boundary`, `updated_at`)",
        "CREATE INDEX IF NOT EXISTS `index_request_ledger_provider_kind_provider_id_model_id` ON `request_ledger` (`provider_kind`, `provider_id`, `model_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_ledger_request_id_active_attempt_id` ON `request_ledger` (`request_id`, `active_attempt_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_ledger_legacy_request_id` ON `request_ledger` (`legacy_request_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_ledger_remote_request_id` ON `request_ledger` (`remote_request_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_attempt_request_id_attempt_ordinal` ON `request_attempt` (`request_id`, `attempt_ordinal`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_attempt_request_id_attempt_id` ON `request_attempt` (`request_id`, `attempt_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_attempt_idempotency_key` ON `request_attempt` (`idempotency_key`)",
        "CREATE INDEX IF NOT EXISTS `index_request_attempt_request_id_attempt_state` ON `request_attempt` (`request_id`, `attempt_state`)",
        "CREATE INDEX IF NOT EXISTS `index_request_attempt_remote_request_id` ON `request_attempt` (`remote_request_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_attempt_foreground_task_id` ON `request_attempt` (`foreground_task_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_attempt_attempt_state_updated_at` ON `request_attempt` (`attempt_state`, `updated_at`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_output_request_id_output_kind_ordinal` ON `request_output` (`request_id`, `output_kind`, `ordinal`)",
        "CREATE INDEX IF NOT EXISTS `index_request_output_request_id_attempt_id` ON `request_output` (`request_id`, `attempt_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_output_attempt_id` ON `request_output` (`attempt_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_output_conversation_id_message_id_part_id` ON `request_output` (`conversation_id`, `message_id`, `part_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_output_asset_id` ON `request_output` (`asset_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_output_source_id` ON `request_output` (`source_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_invocation_request_id_attempt_id_provider_tool_call_id` ON `tool_invocation` (`request_id`, `attempt_id`, `provider_tool_call_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_invocation_request_id_invocation_id` ON `tool_invocation` (`request_id`, `invocation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_invocation_request_id_attempt_id` ON `tool_invocation` (`request_id`, `attempt_id`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_invocation_attempt_id` ON `tool_invocation` (`attempt_id`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_invocation_server_id_tool_name` ON `tool_invocation` (`server_id`, `tool_name`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_invocation_approval_state_execution_state` ON `tool_invocation` (`approval_state`, `execution_state`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_invocation_permission_id` ON `tool_invocation` (`permission_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_permission_permission_key` ON `tool_permission` (`permission_key`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_permission_source_request_id` ON `tool_permission` (`source_request_id`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_permission_principal_kind_principal_id_decision` ON `tool_permission` (`principal_kind`, `principal_id`, `decision`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_permission_server_id_tool_name_action` ON `tool_permission` (`server_id`, `tool_name`, `action`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_permission_scope_kind_scope_id` ON `tool_permission` (`scope_kind`, `scope_id`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_permission_decision_expires_at` ON `tool_permission` (`decision`, `expires_at`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_audit_event_request_id_event_seq` ON `request_audit_event` (`request_id`, `event_seq`)",
        "CREATE INDEX IF NOT EXISTS `index_request_audit_event_request_id_created_at` ON `request_audit_event` (`request_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_request_audit_event_request_id_attempt_id` ON `request_audit_event` (`request_id`, `attempt_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_audit_event_request_id_invocation_id` ON `request_audit_event` (`request_id`, `invocation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_audit_event_event_kind_created_at` ON `request_audit_event` (`event_kind`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_request_audit_event_attempt_id_created_at` ON `request_audit_event` (`attempt_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_request_audit_event_invocation_id_created_at` ON `request_audit_event` (`invocation_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_request_audit_event_permission_id_created_at` ON `request_audit_event` (`permission_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_audit_event_request_id_created_at` ON `tool_audit_event` (`request_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_audit_event_request_id_invocation_id` ON `tool_audit_event` (`request_id`, `invocation_id`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_audit_event_invocation_id_created_at` ON `tool_audit_event` (`invocation_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_audit_event_permission_id_created_at` ON `tool_audit_event` (`permission_id`, `created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_tool_audit_event_event_kind_created_at` ON `tool_audit_event` (`event_kind`, `created_at`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_migration_journal_source_kind_source_id` ON `request_migration_journal` (`source_kind`, `source_id`)",
        "CREATE INDEX IF NOT EXISTS `index_request_migration_journal_phase_lease_until` ON `request_migration_journal` (`phase`, `lease_until`)",
    ).forEach(db::execSQL)
}
