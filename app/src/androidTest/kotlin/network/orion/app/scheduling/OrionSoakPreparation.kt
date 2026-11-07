package network.orion.app.scheduling

import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import network.orion.core.actions.ActionStatus
import network.orion.data.database.OrionDatabase

/** Explicit fixture preparation only. Processes the original durable receipt; never creates consent. */
internal suspend fun awaitOriginalSoakAction(
    db: OrionDatabase,
    actionId: UUID,
    processPending: suspend () -> Int,
    timeoutMillis: Long = 10_000,
) {
    require(timeoutMillis in 1..10_000)
    val original = checkNotNull(db.actions().find(actionId.toString()))
    withTimeout(timeoutMillis) {
        while (true) {
            val current = checkNotNull(db.actions().find(actionId.toString()))
            check(current.normalizedPayloadHash == original.normalizedPayloadHash &&
                current.confirmationFingerprint == original.confirmationFingerprint &&
                current.policyVersion == original.policyVersion &&
                current.argumentsEnvelope.contentEquals(original.argumentsEnvelope) &&
                current.targetEnvelope.contentEquals(original.targetEnvelope)) { "Original fixture binding changed." }
            if (current.status == ActionStatus.SUCCEEDED) {
                check(current.attemptCount == 1 && current.confirmedAt != null &&
                    current.confirmedPayloadHash == original.normalizedPayloadHash &&
                    current.policyVersionAtConfirmation == original.policyVersion)
                return@withTimeout
            }
            check(current.status in setOf(ActionStatus.PENDING, ActionStatus.NEEDS_CONFIRMATION,
                ActionStatus.CONFIRMED, ActionStatus.RUNNING)) { "Original fixture action did not succeed." }
            processPending()
            delay(10)
        }
    }
}
