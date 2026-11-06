package network.orion.app.memories

import java.util.UUID
import network.orion.core.actions.*
import network.orion.core.commands.memories.ValidatedCreateMemory
import network.orion.core.time.WallClock
import network.orion.data.database.OrionDatabase

/** Read-only historical replay proof; it grants no execution authority. Caller owns the writer transaction. */
internal fun orionMemoryWasForgotten(db: OrionDatabase, clock: WallClock, memory: ValidatedCreateMemory): Boolean {
    check(db.inTransaction())
    if (db.memories().forgotten(memory.memoryId.toString()) == 0 || db.memories().find(memory.memoryId.toString()) != null) return false
    val row = db.actions().findByKey("memory.create", memory.idempotencyKey.toString()) ?: return false
    if (row.status != ActionStatus.SUCCEEDED || row.argumentsEnvelope.isNotEmpty() || row.targetEnvelope.isNotEmpty() || !row.requiresConfirmation)
        return false
    val expected = ActionConfirmationBinding(clock).prepare(OrionCreateMemoryActionStore.normalized(UUID.fromString(row.actionId), memory, row.policyVersion))
    return row.normalizedPayloadHash == expected.normalizedPayloadHash && row.confirmationFingerprint == expected.confirmationFingerprint &&
        row.confirmedPayloadHash == expected.normalizedPayloadHash && row.policyVersionAtConfirmation == row.policyVersion &&
        db.actions().auditFor(row.actionId).lastOrNull()?.let { it.fromStatus == ActionStatus.RUNNING && it.toStatus == ActionStatus.SUCCEEDED && it.actionVersion == row.version &&
            it.correlationId == row.correlationId && it.occurredAt == row.executedAt } == true
}

internal class OrionMemoryForgottenReplay : RuntimeException("Memory already forgotten.")
