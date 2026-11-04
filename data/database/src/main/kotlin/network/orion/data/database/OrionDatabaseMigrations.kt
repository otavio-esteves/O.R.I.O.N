package network.orion.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object OrionDatabaseMigrations {
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
