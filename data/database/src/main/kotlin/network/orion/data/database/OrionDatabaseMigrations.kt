package network.orion.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object OrionDatabaseMigrations {
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_tasks` (
                `taskId` TEXT NOT NULL, `contentEnvelope` BLOB NOT NULL, `sensitivity` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL,
                `version` INTEGER NOT NULL, PRIMARY KEY(`taskId`))""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_tasks_status_updatedAt` ON `orion_tasks` (`status`, `updatedAt`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_reminders` (
                `reminderId` TEXT NOT NULL, `contentEnvelope` BLOB NOT NULL, `sensitivity` TEXT NOT NULL,
                `scheduledAt` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `precision` TEXT NOT NULL,
                `version` INTEGER NOT NULL, PRIMARY KEY(`reminderId`))""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_reminders_status_scheduledAt` ON `orion_reminders` (`status`, `scheduledAt`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_memories` (
                `memoryId` TEXT NOT NULL, `contentEnvelope` BLOB NOT NULL, `sensitivity` TEXT NOT NULL,
                `origin` TEXT NOT NULL, `confidence` REAL NOT NULL, `userConfirmed` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL,
                `supersedesId` TEXT, `version` INTEGER NOT NULL, PRIMARY KEY(`memoryId`))""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_memories_status_updatedAt` ON `orion_memories` (`status`, `updatedAt`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_memory_claims` (
                `claimId` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `statementEnvelope` BLOB NOT NULL,
                `sensitivity` TEXT NOT NULL, `origin` TEXT NOT NULL, `confidence` REAL NOT NULL,
                `userConfirmed` INTEGER NOT NULL, `status` TEXT NOT NULL, `validFrom` INTEGER NOT NULL,
                `validUntil` INTEGER, `version` INTEGER NOT NULL, PRIMARY KEY(`claimId`),
                FOREIGN KEY(`memoryId`) REFERENCES `orion_memories`(`memoryId`) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_memory_claims_memoryId` ON `orion_memory_claims` (`memoryId`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_audit` (
                `auditId` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `aggregateVersion` INTEGER NOT NULL,
                `eventCode` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `correlationId` TEXT NOT NULL,
                `preserveUntilReview` INTEGER NOT NULL, PRIMARY KEY(`auditId`))""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_audit_occurredAt` ON `orion_audit` (`occurredAt`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_technical_events` (
                `technicalEventId` TEXT NOT NULL, `component` TEXT NOT NULL, `eventCode` TEXT NOT NULL,
                `occurredAt` INTEGER NOT NULL, `correlationId` TEXT NOT NULL, PRIMARY KEY(`technicalEventId`))""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_technical_events_occurredAt` ON `orion_technical_events` (`occurredAt`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_domain_event_outbox` (
                `eventId` TEXT NOT NULL, `aggregateId` TEXT NOT NULL, `aggregateVersion` INTEGER NOT NULL,
                `eventType` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `payloadSensitivity` TEXT NOT NULL,
                `occurredAt` INTEGER NOT NULL, `correlationId` TEXT NOT NULL, `status` TEXT NOT NULL,
                `attemptCount` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, `deliveredAt` INTEGER,
                `version` INTEGER NOT NULL, PRIMARY KEY(`eventId`))""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_domain_event_outbox_status_nextAttemptAt` ON `orion_domain_event_outbox` (`status`, `nextAttemptAt`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_event_consumer_receipts` (
                `eventId` TEXT NOT NULL, `consumerId` TEXT NOT NULL, `consumedAt` INTEGER NOT NULL,
                PRIMARY KEY(`eventId`, `consumerId`),
                FOREIGN KEY(`eventId`) REFERENCES `orion_domain_event_outbox`(`eventId`) ON UPDATE NO ACTION ON DELETE CASCADE)""".trimIndent())
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_ingress_envelopes` (
                `ingressId` TEXT NOT NULL, `idempotencyScope` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL,
                `canonicalPayloadHash` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `commandType` TEXT NOT NULL,
                `source` TEXT NOT NULL, `priority` INTEGER NOT NULL, `correlationId` TEXT NOT NULL,
                `payloadSensitivity` TEXT NOT NULL, `payloadEnvelope` BLOB NOT NULL,
                `receivedAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `idempotencyRetainUntil` INTEGER NOT NULL,
                `status` TEXT NOT NULL, `version` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                `attemptCount` INTEGER NOT NULL, `lastErrorCode` TEXT, PRIMARY KEY(`ingressId`))""".trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_orion_ingress_envelopes_idempotencyScope_idempotencyKey` ON `orion_ingress_envelopes` (`idempotencyScope`, `idempotencyKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_ingress_envelopes_status_receivedAt` ON `orion_ingress_envelopes` (`status`, `receivedAt`)")
        }
    }

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_action_requests` (
                `actionId` TEXT NOT NULL, `skill` TEXT NOT NULL, `argumentSchemaVersion` INTEGER NOT NULL,
                `argumentsEnvelope` BLOB NOT NULL, `targetEnvelope` BLOB NOT NULL, `payloadSensitivity` TEXT NOT NULL,
                `authorizationLevel` TEXT NOT NULL, `sideEffectClass` TEXT NOT NULL, `recoveryStrategy` TEXT NOT NULL,
                `idempotencyMode` TEXT NOT NULL, `policyVersion` TEXT NOT NULL, `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `idempotencyScope` TEXT NOT NULL,
                `idempotencyKey` TEXT NOT NULL, `idempotencyKeyIssuer` TEXT NOT NULL,
                `canonicalPayloadSchemaVersion` INTEGER NOT NULL, `idempotencyRetainUntil` INTEGER NOT NULL,
                `correlationId` TEXT NOT NULL, `requiresConfirmation` INTEGER NOT NULL,
                `normalizedPayloadHash` TEXT NOT NULL, `confirmationFingerprint` TEXT NOT NULL,
                `version` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `lastError` TEXT,
                `confirmedAt` INTEGER, `confirmationExpiresAt` INTEGER, `confirmedPayloadHash` TEXT,
                `policyVersionAtConfirmation` TEXT, `payloadLockedAt` INTEGER, `executionStartedAt` INTEGER,
                `executedAt` INTEGER, `executionToken` TEXT, `executionId` TEXT, `claimedBy` TEXT,
                `leaseBootSessionId` INTEGER, `leaseStartedElapsedRealtime` INTEGER,
                `leaseExpiresElapsedRealtime` INTEGER, `leaseWallStartedAt` INTEGER,
                PRIMARY KEY(`actionId`))""".trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_orion_action_requests_idempotencyScope_idempotencyKey` ON `orion_action_requests` (`idempotencyScope`, `idempotencyKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_action_requests_status_updatedAt` ON `orion_action_requests` (`status`, `updatedAt`)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS `orion_action_audit` (
                `auditId` TEXT NOT NULL, `actionId` TEXT NOT NULL, `actionVersion` INTEGER NOT NULL,
                `fromStatus` TEXT, `toStatus` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL,
                `correlationId` TEXT NOT NULL, PRIMARY KEY(`auditId`),
                FOREIGN KEY(`actionId`) REFERENCES `orion_action_requests`(`actionId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""".trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_orion_action_audit_actionId` ON `orion_action_audit` (`actionId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_orion_action_audit_actionId_actionVersion` ON `orion_action_audit` (`actionId`, `actionVersion`)")
        }
    }
}
