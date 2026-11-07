package network.orion.app.reminderdelivery

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.app.notifications.OrionNotificationInvocationPermit
import network.orion.app.notifications.OrionNotificationPoster
import network.orion.core.actions.*
import network.orion.core.commands.notifications.OrionPostNotificationPreparer
import network.orion.core.commands.notifications.PostNotification
import network.orion.core.commands.notifications.PostNotificationPreparation
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.model.OrionIntent
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.orchestrator.reminders.*
import network.orion.core.skills.metadata.reminders.OrionDeliverReminderDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.*
import network.orion.data.database.*
import network.orion.skills.notifications.NotificationPostResult
import network.orion.skills.api.SkillContext
import network.orion.skills.reminders.*

/** Committed claim precedes platform work, which never runs inside Room or the admission monitor. */
internal class OrionReminderDeliveryRunner(
    private val db: OrionDatabase,
    private val actions: OrionActionStore,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
    private val poster: OrionNotificationPoster,
) {
    /** A received callback fact may be acknowledged after a clock jump; this never authorizes invocation. */
    fun dueReferenceAllowed(reminderId: UUID, generation: Long, actionId: UUID): Boolean {
        val row = matchingRow(reminderId, generation, actionId) ?: return false
        val stored = (actions.read(actionId) as? ActionReadResult.Ready)?.action ?: return false
        if (stored.status != ActionStatus.CONFIRMED || !stored.requiresConfirmation) return false
        val metadata = db.actions().find(actionId.toString()) ?: return false
        if (metadata.idempotencyScope != "reminder.deliver" || metadata.idempotencyKey != actionId.toString()) return false
        val expected = normalized(row, actionId)
        val receipt = stored.confirmation ?: return false
        val binding = ActionConfirmationBinding(clock).prepare(expected)
        val due = Instant.ofEpochMilli(row.scheduledAt)
        return stored.request == expected && receipt.actionId == actionId &&
            receipt.policyVersionAtConfirmation == expected.policyVersion &&
            receipt.normalizedPayloadHash == binding.normalizedPayloadHash &&
            receipt.confirmedPayloadHash == binding.normalizedPayloadHash &&
            receipt.confirmationFingerprint == binding.confirmationFingerprint &&
            receipt.payloadLockedAt == receipt.confirmedAt && receipt.confirmedAt.isBefore(due) &&
            receipt.confirmationExpiresAt == OrionReminderDeliveryPolicy.expiresAt(due)
    }

    fun dueAllowed(reminderId: UUID, generation: Long, actionId: UUID): Boolean {
        if (!dueReferenceAllowed(reminderId, generation, actionId)) return false
        val row = matchingRow(reminderId, generation, actionId) ?: return false
        val stored = (actions.read(actionId) as? ActionReadResult.Ready)?.action ?: return false
        if (stored.status != ActionStatus.CONFIRMED || !stored.requiresConfirmation) return false
        val expected = normalized(row, actionId)
        val receipt = stored.confirmation ?: return false
        return stored.request == expected && !clock.wallNow().isBefore(Instant.ofEpochMilli(row.scheduledAt)) &&
            receipt.confirmationExpiresAt == OrionReminderDeliveryPolicy.expiresAt(Instant.ofEpochMilli(row.scheduledAt)) &&
            ActionConfirmationBinding(clock).validate(expected, receipt) == ConfirmationBindingResult.VALID_BINDING
    }

    fun executeDue(reminderId: UUID, generation: Long): ReminderDeliveryOutcome {
        check(!db.inTransaction() && !Thread.holdsLock(barrier)) { "External delivery must follow committed ingress." }
        if (!barrier.isOpen()) return ReminderDeliveryOutcome.UNAVAILABLE
        val row = db.reminders().find(reminderId.toString()) ?: return ReminderDeliveryOutcome.REJECTED
        if (row.fireGeneration != generation) return ReminderDeliveryOutcome.REJECTED
        val actionId = row.deliveryActionId?.let(UUID::fromString) ?: return ReminderDeliveryOutcome.REJECTED
        val stored = (actions.read(actionId) as? ActionReadResult.Ready)?.action ?: return ReminderDeliveryOutcome.UNAVAILABLE
        when (stored.status) {
            ActionStatus.SUCCEEDED -> return if (row.status == ReminderStatus.FIRED) ReminderDeliveryOutcome.POSTED else ReminderDeliveryOutcome.UNKNOWN
            ActionStatus.UNKNOWN, ActionStatus.MANUAL_REVIEW, ActionStatus.RUNNING -> return ReminderDeliveryOutcome.UNKNOWN
            ActionStatus.CONFIRMED -> Unit
            else -> return ReminderDeliveryOutcome.REJECTED
        }
        if (!dueAllowed(reminderId, generation, actionId)) return ReminderDeliveryOutcome.REJECTED
        if (db.events().count() >= 10_000) return ReminderDeliveryOutcome.UNAVAILABLE
        val actionRow = db.actions().find(actionId.toString()) ?: return ReminderDeliveryOutcome.UNAVAILABLE
        if (actionRow.idempotencyScope != "reminder.deliver" || actionRow.idempotencyKey != actionId.toString())
            return ReminderDeliveryOutcome.REJECTED
        val expected = normalized(row, actionId)
        val originalReceipt = stored.confirmation ?: return ReminderDeliveryOutcome.REJECTED
        val authority = object : ActionExecutionAuthority {
            override fun startupBarrierOpen() = barrier.isOpen()
            override fun descriptorFor(skill: String) = OrionDeliverReminderDescriptor.value.takeIf { it.name == skill }
            override fun currentPolicyVersion() = OrionDeliverReminderDescriptor.POLICY_VERSION
            override fun policyAllows(request: NormalizedActionRequest) = request == expected &&
                matchingRow(reminderId, generation, actionId) != null &&
                !clock.wallNow().isBefore(Instant.ofEpochMilli(row.scheduledAt))
            override fun capabilityAvailable(capability: Capability) = capability == OrionDeliverReminderDescriptor.DELIVERY_CAPABILITY && available()
            override fun permissionGranted(permission: String) = permission == OrionDeliverReminderDescriptor.POST_PERMISSION && poster.permissionGranted()
            override fun userPresent() = false // This descriptor explicitly authorizes a future one-shot background alert.
        }
        val execution = OrionActionExecutionStore(db, actions, clock, authority)
        val claim = barrier.whileOpen {
            execution.claim(actionId, stored.version, "orion_reminder_delivery_main", Duration.ofSeconds(30))
        } as? ActionClaimResult.Claimed ?: return ReminderDeliveryOutcome.UNAVAILABLE
        check(!db.inTransaction() && !Thread.holdsLock(barrier))
        val notification = (OrionPostNotificationPreparer.prepare(PostNotification(actionId,
            OrionReminderDeliveryPolicy.TITLE, OrionReminderDeliveryPolicy.BODY, actionId, reminderId)) as PostNotificationPreparation.Ready).notification
        fun permitted(): Boolean {
            if (db.inTransaction() || Thread.holdsLock(barrier) || !barrier.isOpen()) return false
            val current = db.actions().find(actionId.toString()) ?: return false
            if (current.status != ActionStatus.RUNNING || current.version != claim.version ||
                current.executionId != claim.lease.executionId.toString() || current.claimedBy != claim.lease.claimedBy ||
                current.leaseBootSessionId != claim.lease.bootSessionId.bootCount ||
                current.leaseStartedElapsedRealtime != claim.lease.startedElapsedRealtime ||
                current.leaseExpiresElapsedRealtime != claim.lease.expiresElapsedRealtime) return false
            val bound = (actions.read(actionId) as? ActionReadResult.Ready)?.action ?: return false
            if (bound.request != expected || bound.confirmation != originalReceipt ||
                ActionExecutionValidation.denyReason(bound.request, bound.requiresConfirmation, authority) != null) return false
            // Resample clocks after potentially slow platform/database capability work.
            if (!available() || !poster.permissionGranted() || !barrier.isOpen()) return false
            // Capability reads can cross Binder. Cancellation/snooze or series revocation
            // during that work must invalidate the exact binding before invocation.
            val latestOwner = db.actions().find(actionId.toString()) ?: return false
            if (latestOwner.status != ActionStatus.RUNNING || latestOwner.version != claim.version ||
                latestOwner.executionId != claim.lease.executionId.toString() || latestOwner.claimedBy != claim.lease.claimedBy ||
                latestOwner.leaseBootSessionId != claim.lease.bootSessionId.bootCount ||
                latestOwner.leaseStartedElapsedRealtime != claim.lease.startedElapsedRealtime ||
                latestOwner.leaseExpiresElapsedRealtime != claim.lease.expiresElapsedRealtime ||
                matchingRow(reminderId, generation, actionId) == null) return false
            if (ActionConfirmationBinding(clock).validate(expected, originalReceipt) != ConfirmationBindingResult.VALID_BINDING) return false
            if (claim.lease.statusAt(clock.monotonicNow()) != ActionLeaseStatus.ACTIVE) return false
            return barrier.isOpen() && !db.inTransaction() && !Thread.holdsLock(barrier)
        }
        val skill = OrionDeliverReminderSkill(OrionOwnedReminderDelivery { input, context ->
            if (input.reminderId != reminderId || input.generation != generation || input.scheduledAt.toEpochMilli() != row.scheduledAt ||
                context.actionId != actionId || context.executionId != claim.lease.executionId || context.actionVersion != claim.version)
                return@OrionOwnedReminderDelivery ReminderDeliveryEffectResult.NOT_OWNED
            if (!permitted()) return@OrionOwnedReminderDelivery ReminderDeliveryEffectResult.NOT_INVOKED
            when (poster.post(actionId, notification, OrionNotificationInvocationPermit { permitted() })) {
                NotificationPostResult.POSTED -> ReminderDeliveryEffectResult.POSTED
                NotificationPostResult.NOT_INVOKED -> ReminderDeliveryEffectResult.NOT_INVOKED
                NotificationPostResult.NOT_OWNED -> ReminderDeliveryEffectResult.NOT_OWNED
                NotificationPostResult.UNKNOWN -> ReminderDeliveryEffectResult.UNKNOWN
            }
        })
        val intent = OrionIntent(OrionDeliverReminderDescriptor.value.name, 1.0,
            OrionReminderDeliveryPreparer.prepare(reminderId, generation, Instant.ofEpochMilli(row.scheduledAt)),
            "SCHEDULER", false, actionId)
        val result = try {
            when (skill.executeOwned(intent, SkillContext(actionId, claim.lease.executionId, claim.version))) {
                ReminderDeliveryEffectResult.POSTED -> NotificationPostResult.POSTED
                ReminderDeliveryEffectResult.NOT_INVOKED -> NotificationPostResult.NOT_INVOKED
                ReminderDeliveryEffectResult.NOT_OWNED -> NotificationPostResult.NOT_OWNED
                ReminderDeliveryEffectResult.UNKNOWN -> NotificationPostResult.UNKNOWN
            }
        } catch (cancelled: CancellationException) {
            markUnknown(execution, actionId, claim); throw cancelled
        } catch (interrupted: InterruptedException) {
            markUnknown(execution, actionId, claim); Thread.currentThread().interrupt(); throw interrupted
        } catch (_: Exception) { NotificationPostResult.UNKNOWN }
        return when (result) {
            NotificationPostResult.POSTED -> try {
                db.runInTransaction {
                    check(execution.finish(actionId, claim.version, claim.lease.executionId, ActionStatus.SUCCEEDED) == ActionMutation.APPLIED)
                    val current = matchingRow(reminderId, generation, actionId) ?: error("Reminder changed during delivery.")
                    val at = clock.wallNow().toEpochMilli()
                    val event = DomainEventOutboxEntity(UUID.randomUUID().toString(), current.reminderId, current.version + 1,
                        "REMINDER_FIRED", 1, current.sensitivity, at, actionId.toString())
                    check(db.reminderSchedules().completeDeliveryWithEvent(current.reminderId, current.version, generation,
                        actionId.toString(), at, event) == ActionMutation.APPLIED)
                    OrionReminderSeriesAdvancer(db).advance(current.reminderId, generation, actionId.toString(), at)
                }
                ReminderDeliveryOutcome.POSTED
            } catch (_: Exception) {
                markUnknown(execution, actionId, claim); ReminderDeliveryOutcome.UNKNOWN
            }
            NotificationPostResult.UNKNOWN -> {
                markUnknown(execution, actionId, claim); ReminderDeliveryOutcome.UNKNOWN
            }
            NotificationPostResult.NOT_INVOKED -> {
                execution.finish(actionId, claim.version, claim.lease.executionId, ActionStatus.FAILED)
                ReminderDeliveryOutcome.UNAVAILABLE
            }
            NotificationPostResult.NOT_OWNED -> {
                // An adapter without matching ownership cannot assert absence of an invocation.
                markUnknown(execution, actionId, claim); ReminderDeliveryOutcome.UNKNOWN
            }
        }
    }
    private fun normalized(row: ReminderEntity, actionId: UUID) = OrionReminderDeliveryPolicy.normalized(
        ReminderDeliveryPreview(UUID.fromString(row.reminderId), row.version, row.fireGeneration,
            Instant.ofEpochMilli(row.scheduledAt), actionId, 0, "", OrionDeliverReminderDescriptor.POLICY_VERSION),
    )
    private fun matchingRow(id: UUID, generation: Long, actionId: UUID): ReminderEntity? = db.reminders().find(id.toString())?.takeIf {
        it.status == ReminderStatus.PENDING && it.fireGeneration == generation && it.deliveryActionId == actionId.toString() &&
            OrionReminderSeriesAdvancer(db).referenceAllowed(it.reminderId, generation, actionId.toString(), it.scheduledAt)
    }
    private fun available() = poster.permissionGranted() && poster.availability() == NotificationAvailability.AVAILABLE
    private fun markUnknown(execution: OrionActionExecutionStore, id: UUID, claim: ActionClaimResult.Claimed) {
        execution.finish(id, claim.version, claim.lease.executionId, ActionStatus.UNKNOWN)
    }
}
