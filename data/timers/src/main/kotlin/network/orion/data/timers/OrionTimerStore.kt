package network.orion.data.timers

import java.util.UUID
import network.orion.core.model.Sensitivity
import network.orion.core.security.*
import network.orion.core.time.OrionClock
import network.orion.data.database.*

sealed interface TimerCreationResult {
    data class Created(val timerId: UUID) : TimerCreationResult
    data class Blocked(val reason: FieldCryptoFailure) : TimerCreationResult
}

/** MAIN's storage adapter; owned Skill callers have already validated the input and authorization. */
class OrionTimerStore(private val database: OrionDatabase, private val encryption: OrionFieldEncryption, private val clock: OrionClock) {
    fun create(timerId: UUID, canonicalContent: ByteArray, durationMillis: Long, sensitivity: Sensitivity, correlationId: UUID): TimerCreationResult {
        val encrypted = when (val result = encryption.encrypt(canonicalContent, context(timerId))) {
            is FieldEncryptionResult.Encrypted -> FieldEnvelopeCodec.encode(result.envelope)
            is FieldEncryptionResult.Failure -> return TimerCreationResult.Blocked(result.reason)
        }
        val now = clock.wallNow().toEpochMilli()
        val monotonic = clock.monotonicNow()
        val boot = checkNotNull(monotonic.bootSessionId)
        val deadline = Math.addExact(now, durationMillis)
        Math.addExact(monotonic.elapsedRealtimeMillis, durationMillis)
        database.timers().createWithEvent(TimerEntity(timerId.toString(), encrypted, sensitivity, durationMillis,
            now, deadline, boot.bootCount, monotonic.elapsedRealtimeMillis, now, now),
            DomainEventOutboxEntity(UUID.randomUUID().toString(), timerId.toString(), 0, "TIMER_CREATED", 1,
                sensitivity, now, correlationId.toString()))
        return TimerCreationResult.Created(timerId)
    }

    fun readContent(timerId: UUID): FieldDecryptionResult? {
        val timer = database.timers().find(timerId.toString()) ?: return null
        val decoded = FieldEnvelopeCodec.decode(timer.contentEnvelope)
        if (decoded !is FieldEnvelopeDecodingResult.Decoded) return FieldDecryptionResult.Failure(FieldCryptoFailure.UNSUPPORTED_ENVELOPE)
        return encryption.decrypt(decoded.envelope, context(timerId))
    }

    fun changeStatus(timerId: UUID, expectedVersion: Long, status: TimerStatus, correlationId: UUID): ActionMutation {
        val current = database.timers().find(timerId.toString()) ?: return ActionMutation.NOT_FOUND
        if (current.version != expectedVersion) return ActionMutation.CONFLICT
        val now = clock.wallNow().toEpochMilli()
        require(status == TimerStatus.CANCELLED)
        return database.timers().changeStatusWithEvent(timerId.toString(), expectedVersion, status, now,
            DomainEventOutboxEntity(UUID.randomUUID().toString(), timerId.toString(), expectedVersion + 1,
                "TIMER_CANCELLED", 1, current.sensitivity, now, correlationId.toString()))
    }
    private fun context(timerId: UUID) = FieldEncryptionContext("timer/$timerId", "content", 1)
}
