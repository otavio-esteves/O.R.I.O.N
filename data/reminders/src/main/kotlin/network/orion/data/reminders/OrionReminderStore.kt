package network.orion.data.reminders

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.WallClock
import network.orion.data.database.*

sealed interface ReminderWriteResult {
    data class Stored(val id: UUID) : ReminderWriteResult
    data class Blocked(val reason: FieldCryptoFailure) : ReminderWriteResult
    data class Rejected(val mutation: ActionMutation) : ReminderWriteResult
}

/** Main-owned F2 storage. Call off UI; trusted callers validate policy before mutation. No OS scheduling. */
class OrionReminderStore(private val database: OrionDatabase, private val encryption: OrionFieldEncryption,
    private val clock: WallClock) {
    fun create(id: UUID, content: ByteArray, sensitivity: Sensitivity, scheduledAt: Instant,
        zone: ZoneId, correlationId: UUID): ReminderWriteResult {
        val encrypted = encrypt(id, content)
        if (encrypted is FieldEncryptionResult.Failure) return ReminderWriteResult.Blocked(encrypted.reason)
        val at = clock.wallNow().toEpochMilli()
        database.records().createReminderWithEvent(ReminderEntity(id.toString(),
            FieldEnvelopeCodec.encode((encrypted as FieldEncryptionResult.Encrypted).envelope), sensitivity,
            scheduledAt.toEpochMilli(), zone.id, at, at), event(id, 0, "REMINDER_CREATED", sensitivity, at, correlationId))
        return ReminderWriteResult.Stored(id)
    }

    fun update(id: UUID, expectedVersion: Long, content: ByteArray, sensitivity: Sensitivity,
        scheduledAt: Instant, zone: ZoneId, correlationId: UUID): ReminderWriteResult {
        require(expectedVersion in 0 until Long.MAX_VALUE)
        val encrypted = encrypt(id, content)
        if (encrypted is FieldEncryptionResult.Failure) return ReminderWriteResult.Blocked(encrypted.reason)
        val at = clock.wallNow().toEpochMilli()
        val outcome = database.records().updateReminderWithEvent(id.toString(), expectedVersion,
            FieldEnvelopeCodec.encode((encrypted as FieldEncryptionResult.Encrypted).envelope), sensitivity,
            scheduledAt.toEpochMilli(), zone.id, at, event(id, expectedVersion + 1, "REMINDER_UPDATED", sensitivity, at, correlationId))
        return if (outcome == ActionMutation.APPLIED) ReminderWriteResult.Stored(id) else ReminderWriteResult.Rejected(outcome)
    }

    fun cancel(id: UUID, expectedVersion: Long, correlationId: UUID): ActionMutation {
        require(expectedVersion in 0 until Long.MAX_VALUE)
        val row = database.records().reminder(id.toString()) ?: return ActionMutation.NOT_FOUND
        val at = clock.wallNow().toEpochMilli()
        return database.records().cancelReminderWithEvent(id.toString(), expectedVersion, at,
            event(id, expectedVersion + 1, "REMINDER_CANCELLED", row.sensitivity, at, correlationId))
    }

    fun readContent(id: UUID): FieldDecryptionResult? {
        val row = database.records().reminder(id.toString()) ?: return null
        val envelope = FieldEnvelopeCodec.decode(row.contentEnvelope)
        return if (envelope is FieldEnvelopeDecodingResult.Decoded) encryption.decrypt(envelope.envelope, context(id))
            else FieldDecryptionResult.Failure(FieldCryptoFailure.UNSUPPORTED_ENVELOPE)
    }

    /** Stable ID cursor; bounded metadata pages contain no raw user content. */
    fun list(afterId: UUID? = null, limit: Int = 50): List<ReminderEntity> = database.records().reminders(afterId?.toString() ?: "", limit)

    private fun context(id: UUID) = FieldEncryptionContext("reminder/$id", "content", 1)
    private fun encrypt(id: UUID, content: ByteArray) = encryption.encrypt(content, context(id))
    private fun event(id: UUID, version: Long, type: String, sensitivity: Sensitivity, at: Long, correlation: UUID) =
        DomainEventOutboxEntity(UUID.randomUUID().toString(), id.toString(), version, type, 1, sensitivity, at, correlation.toString())
}
