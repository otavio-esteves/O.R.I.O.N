package network.orion.core.commands.timers

import java.util.UUID
import network.orion.core.commands.Command

enum class TimerStatusOperation { CANCEL }
enum class TimerStatusResult { QUEUED, REJECTED, UNAVAILABLE }

/** Expected version is domain concurrency data, never authorization or a confirmation flag. */
sealed interface TimerStatusCommand : Command<TimerStatusResult> {
    val timerId: UUID
    val expectedVersion: Long
    val idempotencyKey: UUID
    val correlationId: UUID
    val operation: TimerStatusOperation
}

data class CancelTimer(override val timerId: UUID, override val expectedVersion: Long,
    override val idempotencyKey: UUID, override val correlationId: UUID) : TimerStatusCommand {
    override val operation get() = TimerStatusOperation.CANCEL
    override fun toString() = "CancelTimer(<redacted>)"
}

enum class TimerStatusRejection { INVALID_VERSION }

sealed interface TimerStatusPreparation {
    class Ready internal constructor(val change: ValidatedTimerStatusChange) : TimerStatusPreparation {
        override fun toString() = "TimerStatusPreparation.Ready(<redacted>)"
    }
    data class Rejected(val reason: TimerStatusRejection) : TimerStatusPreparation
}

/** Fixed schema-v1 material: timerId then expectedVersion. Operation is bound by static Skill name. */
class ValidatedTimerStatusChange internal constructor(
    val timerId: UUID, val expectedVersion: Long, val idempotencyKey: UUID,
    val correlationId: UUID, val operation: TimerStatusOperation,
) {
    val skillName get() = when (operation) {
        TimerStatusOperation.CANCEL -> "timer.cancel"
    }
    val argumentSchemaVersion get() = 1
    val normalizedTarget get() = "timer/$timerId"
    val canonicalNormalizedArguments get() = "{\"timerId\":\"$timerId\",\"expectedVersion\":$expectedVersion}"
    override fun toString() = "ValidatedTimerStatusChange(<redacted>)"
}

object OrionTimerStatusPreparer {
    fun prepare(command: TimerStatusCommand): TimerStatusPreparation =
        if (command.expectedVersion < 0 || command.expectedVersion == Long.MAX_VALUE)
            TimerStatusPreparation.Rejected(TimerStatusRejection.INVALID_VERSION)
        else TimerStatusPreparation.Ready(ValidatedTimerStatusChange(command.timerId, command.expectedVersion,
            command.idempotencyKey, command.correlationId, command.operation))
}
