package network.orion.app.voice

import java.time.Duration
import java.util.UUID
import network.orion.core.actions.ActionConfirmationBinding
import network.orion.core.actions.IdempotencyKeyPolicy
import network.orion.core.actions.NormalizedActionRequest
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.skills.metadata.reminders.OrionAmbientReminderDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.ActionStoreOutcome
import network.orion.data.actions.ActionReadResult
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.ActionMutation
import network.orion.data.database.OrionDatabase
import network.orion.data.reminders.OrionReminderStore
import network.orion.data.reminders.ReminderWriteResult

/** Called by an authorized MAIN ingress consumer after speaker and consent validation. */
internal class OrionAmbientReminderPreparation(
    private val database: OrionDatabase,
    encryption: OrionFieldEncryption,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
) {
    private val reminders = OrionReminderStore(database, encryption, clock)
    private val actions = OrionActionStore(database, encryption, clock)
    private val binding = ActionConfirmationBinding(clock)
    private val descriptor = OrionAmbientReminderDescriptor.value

    fun prepare(commitment: OrionAmbientCommitment, authorizationRevision: Long, correlationId: UUID): UUID? {
        check(database.inTransaction() && Thread.holdsLock(barrier) && barrier.isOpen())
        val now = clock.wallNow()
        if (authorizationRevision <= 0 || commitment.noticeAt <= now || commitment.startsAt > now.plus(Duration.ofDays(2))) return null

        // Same normalized commitment, including retransmission with a different IPC id, retains one authorization.
        val fields = "${commitment.kind.name}|${commitment.startsAt.toEpochMilli()}|${commitment.zone.id}"
        val key = UUID.nameUUIDFromBytes("orion_ambient_reminder|$fields".toByteArray())
        val target = "orion_ambient_reminder/$authorizationRevision"
        val previous = database.actions().findByKey(descriptor.name, key.toString())
        if (previous != null) {
            val id = UUID.fromString(previous.actionId)
            val action = (actions.read(id) as? ActionReadResult.Ready)?.action ?: return null
            return id.takeIf { action.request.normalizedTarget == target }
        }
        if (database.events().count() >= 9_998) return null
        val id = UUID.randomUUID()
        val request = NormalizedActionRequest(id, descriptor.name, "$id|1|$fields",
            target, descriptor.authorizationLevel, descriptor.argumentSchemaVersion,
            descriptor.sideEffectClass, descriptor.recoveryStrategy, descriptor.idempotencyMode,
            OrionAmbientReminderDescriptor.POLICY_VERSION)
        val registered = actions.register(request, idempotency, key.toString(), correlationId, Sensitivity.SENSITIVE,
            requiresConfirmation = false)
        check(registered.outcome == ActionStoreOutcome.REGISTERED && registered.actionId == id)
        // The exact binding is issued by policy. The user has not confirmed the observation as a fact.
        val receipt = binding.recordConfirmation(request, commitment.startsAt)
        check(actions.confirm(id, 0, receipt, request.policyVersion).outcome == ActionStoreOutcome.APPLIED)
        val title = commitment.inferredTitle.toByteArray()
        try {
            check(reminders.create(id, title, Sensitivity.SENSITIVE, commitment.noticeAt, commitment.zone, correlationId) is ReminderWriteResult.Stored)
        } finally {
            title.fill(0)
        }
        check(database.reminderSchedules().bindAmbientDelivery(id.toString(), id.toString(), now.toEpochMilli()) == ActionMutation.APPLIED)
        return id
    }

    private val idempotency = IdempotencyKeyPolicy("MAIN_AMBIENT", descriptor.name, Duration.ofDays(90),
        descriptor.argumentSchemaVersion, OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)
}
