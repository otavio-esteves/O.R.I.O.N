package network.orion.app.sentinel

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CancellationException
import network.orion.app.notifications.OrionAndroidNotificationPoster
import network.orion.app.notifications.OrionNotificationInvocationPermit
import network.orion.app.notifications.OrionNotificationPoster
import network.orion.core.actions.ActionConfirmationBinding
import network.orion.core.actions.ActionExecutionAuthority
import network.orion.core.actions.ActionExecutionValidation
import network.orion.core.actions.ActionLeaseStatus
import network.orion.core.actions.ActionStatus
import network.orion.core.actions.ConfirmationBindingResult
import network.orion.core.actions.IdempotencyKeyPolicy
import network.orion.core.actions.NormalizedActionRequest
import network.orion.core.commands.notifications.OrionPostNotificationPreparer
import network.orion.core.commands.notifications.PostNotification
import network.orion.core.commands.notifications.PostNotificationPreparation
import network.orion.core.commands.notifications.ValidatedPostNotification
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Capability
import network.orion.core.model.OrionIntent
import network.orion.core.model.Sensitivity
import network.orion.core.orchestrator.notifications.NotificationAvailability
import network.orion.core.policy.sentinel.OrionProactiveNotificationPolicy
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.skills.metadata.notifications.OrionPostNotificationDescriptor
import network.orion.core.skills.metadata.notifications.OrionProactiveNotificationDescriptor
import network.orion.core.time.OrionClock
import network.orion.data.actions.ActionClaimResult
import network.orion.data.actions.ActionReadResult
import network.orion.data.actions.ActionStoreOutcome
import network.orion.data.actions.OrionActionExecutionStore
import network.orion.data.actions.OrionActionStore
import network.orion.data.database.ActionMutation
import network.orion.data.database.DomainEventOutboxEntity
import network.orion.data.database.OrionDatabase
import network.orion.skills.api.SkillContext
import network.orion.skills.notifications.NotificationPostResult
import network.orion.skills.notifications.OrionOwnedNotificationPoster
import network.orion.skills.notifications.OrionProactiveNotificationSkill

internal enum class OrionProactiveDeliveryOutcome { POSTED, BLOCKED, UNKNOWN }

/** Owns the exact action and bounded notification effect for a reserved Sentinel suggestion. */
internal class OrionProactiveDelivery(
    private val db: OrionDatabase,
    encryption: OrionFieldEncryption,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
    private val poster: OrionNotificationPoster,
) {
    constructor(context: Context, db: OrionDatabase, encryption: OrionFieldEncryption, clock: OrionClock,
        barrier: StartupBarrier) : this(db, encryption, clock, barrier, androidPoster(context, clock))

    private val actions = OrionActionStore(db, encryption, clock)
    private val binding = ActionConfirmationBinding(clock)
    private val descriptor = OrionProactiveNotificationDescriptor.value
    private val policyVersion = OrionProactiveNotificationDescriptor.POLICY_VERSION
    private val idempotency = IdempotencyKeyPolicy(OrionProactiveNotificationDescriptor.SOURCE, descriptor.name,
        Duration.ofDays(90), descriptor.argumentSchemaVersion, OrionActionStore.IDEMPOTENCY_UNIQUE_CONSTRAINT)

    fun available(): Boolean =
        poster.permissionGranted() && poster.availability() == NotificationAvailability.AVAILABLE

    /** Trusted caller has evaluated eligibility and reserved this ID in its admitted ingress transaction. */
    fun prepare(id: UUID, enabledConsentVersion: Long?): Boolean {
        check(db.inTransaction() && Thread.holdsLock(barrier))
        val consentVersion = enabledConsentVersion ?: return false
        if (!OrionProactiveNotificationPolicy.allows(consentVersion, barrier.isOpen())) return false
        val expected = normalized(notification(id), consentVersion)
        val result = actions.register(expected, idempotency, id.toString(), id, Sensitivity.PERSONAL,
            requiresConfirmation = false)
        if (result.actionId != id) return false
        if (result.outcome == ActionStoreOutcome.DEDUP) {
            val stored = (actions.read(id) as? ActionReadResult.Ready)?.action ?: return false
            return stored.request == expected && !stored.requiresConfirmation && stored.status == ActionStatus.CONFIRMED
        }
        check(result.outcome == ActionStoreOutcome.REGISTERED)
        // This exact binding records the policy's immutable payload. This Skill
        // never claims that a person confirmed the notification individually.
        val policyBinding = binding.recordConfirmation(expected, clock.wallNow().plus(AUTHORIZATION_LIFETIME))
        check(actions.confirm(id, 0, policyBinding, policyVersion).outcome == ActionStoreOutcome.APPLIED)
        return true
    }

    /** A proven unclaimed draft may be terminalized without touching unrelated actions or uncertain effects. */
    fun discard(id: UUID): Boolean = barrier.whileOpen {
        val row = db.actions().find(id.toString()) ?: return@whileOpen false
        row.skill == descriptor.name && row.idempotencyScope == descriptor.name && row.idempotencyKey == id.toString() &&
            !row.requiresConfirmation && row.status == ActionStatus.CONFIRMED &&
            actions.cancel(id, row.version).outcome == ActionStoreOutcome.APPLIED
    } ?: false

    /** Bounded expiry sweep. A matching CAS keeps an action newly claimed by another caller intact. */
    fun discardExpired(): Int {
        val expired = db.actions().expiredProactiveActions(clock.wallNow().toEpochMilli(), 100)
        return expired.count { actions.cancel(UUID.fromString(it.actionId), it.version).outcome == ActionStoreOutcome.APPLIED }
    }

    fun deliver(id: UUID, policyAllows: (consentVersion: Long) -> Boolean, onPosted: () -> Unit): OrionProactiveDeliveryOutcome {
        check(!db.inTransaction() && !Thread.holdsLock(barrier))
        val payload = notification(id)
        val stored = (actions.read(id) as? ActionReadResult.Ready)?.action ?: return OrionProactiveDeliveryOutcome.BLOCKED
        val consentVersion = stored.request.normalizedTarget.takeIf { it.startsWith(CONSENT_TARGET) }
            ?.removePrefix(CONSENT_TARGET)?.toLongOrNull()?.takeIf { it >= 0 }
            ?: return OrionProactiveDeliveryOutcome.BLOCKED
        val expected = normalized(payload, consentVersion)
        val row = db.actions().find(id.toString()) ?: return OrionProactiveDeliveryOutcome.BLOCKED
        if (stored.request != expected || stored.requiresConfirmation || row.idempotencyScope != descriptor.name ||
            row.idempotencyKey != id.toString()) return OrionProactiveDeliveryOutcome.BLOCKED
        when (stored.status) {
            ActionStatus.SUCCEEDED -> return OrionProactiveDeliveryOutcome.POSTED
            ActionStatus.RUNNING, ActionStatus.UNKNOWN, ActionStatus.MANUAL_REVIEW -> return OrionProactiveDeliveryOutcome.UNKNOWN
            ActionStatus.CONFIRMED -> Unit
            else -> return OrionProactiveDeliveryOutcome.BLOCKED
        }
        val receipt = stored.confirmation ?: return OrionProactiveDeliveryOutcome.BLOCKED
        if (db.events().count() >= 10_000) return OrionProactiveDeliveryOutcome.BLOCKED
        fun currentlyEligible() = policyAllows(consentVersion) &&
            OrionProactiveNotificationPolicy.allows(consentVersion, barrier.isOpen())
        // The claim takes the barrier monitor and Room writer. Sample platform
        // evidence first, then revalidate the live evidence at invocation.
        val eligibleAtClaim = currentlyEligible()
        val availableAtClaim = available()
        val permissionAtClaim = poster.permissionGranted()
        val claimAuthority = authority(expected, { eligibleAtClaim }, { availableAtClaim }, { permissionAtClaim })
        val liveAuthority = authority(expected, ::currentlyEligible, ::available, poster::permissionGranted)
        val execution = OrionActionExecutionStore(db, actions, clock, claimAuthority)
        val claim = barrier.whileOpen { execution.claim(id, stored.version, "orion_sentinel_main", EXECUTION_LEASE) }
            as? ActionClaimResult.Claimed ?: return OrionProactiveDeliveryOutcome.BLOCKED
        val owner = SkillContext(id, claim.lease.executionId, claim.version)

        fun permitsInvocation(): Boolean {
            if (db.inTransaction() || Thread.holdsLock(barrier) || !barrier.isOpen()) return false
            val bound = (actions.read(id) as? ActionReadResult.Ready)?.action ?: return false
            if (bound.request != expected || bound.confirmation != receipt || bound.requiresConfirmation ||
                ActionExecutionValidation.denyReason(expected, false, liveAuthority) != null ||
                !currentlyEligible()) return false
            val current = db.actions().find(id.toString()) ?: return false
            if (current.status != ActionStatus.RUNNING || current.version != owner.actionVersion ||
                current.executionId != owner.executionId.toString() || current.claimedBy != claim.lease.claimedBy ||
                current.leaseBootSessionId != claim.lease.bootSessionId.bootCount ||
                current.leaseStartedElapsedRealtime != claim.lease.startedElapsedRealtime ||
                current.leaseExpiresElapsedRealtime != claim.lease.expiresElapsedRealtime) return false
            return binding.validate(expected, receipt) == ConfirmationBindingResult.VALID_BINDING &&
                claim.lease.statusAt(clock.monotonicNow()) == ActionLeaseStatus.ACTIVE && barrier.isOpen()
        }
        val skill = OrionProactiveNotificationSkill(OrionOwnedNotificationPoster { input, caller ->
            when {
                caller != owner || input.canonicalNormalizedArguments != expected.canonicalNormalizedArguments ->
                    NotificationPostResult.NOT_OWNED
                !permitsInvocation() -> NotificationPostResult.NOT_INVOKED
                else -> poster.post(id, input, OrionNotificationInvocationPermit(::permitsInvocation))
            }
        })
        fun finish(status: ActionStatus) = execution.finish(id, claim.version, claim.lease.executionId, status)
        val intent = OrionIntent(descriptor.name, 1.0, payload, OrionProactiveNotificationDescriptor.SOURCE, false, id)
        val result = try {
            skill.executeOwned(intent, owner)
        } catch (cancelled: CancellationException) {
            finish(ActionStatus.UNKNOWN)
            throw cancelled
        } catch (interrupted: InterruptedException) {
            finish(ActionStatus.UNKNOWN)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            NotificationPostResult.UNKNOWN
        }
        return when (result) {
            NotificationPostResult.POSTED -> try {
                db.runInTransaction {
                    check(finish(ActionStatus.SUCCEEDED) == ActionMutation.APPLIED)
                    db.events().append(DomainEventOutboxEntity(UUID.randomUUID().toString(), id.toString(),
                        claim.version + 1, "NOTIFICATION_POSTED", 1, Sensitivity.PERSONAL,
                        clock.wallNow().toEpochMilli(), id.toString()))
                    onPosted()
                }
                OrionProactiveDeliveryOutcome.POSTED
            } catch (_: Exception) {
                finish(ActionStatus.UNKNOWN)
                OrionProactiveDeliveryOutcome.UNKNOWN
            }
            NotificationPostResult.NOT_INVOKED -> if (finish(ActionStatus.FAILED) == ActionMutation.APPLIED)
                OrionProactiveDeliveryOutcome.BLOCKED else OrionProactiveDeliveryOutcome.UNKNOWN
            NotificationPostResult.UNKNOWN, NotificationPostResult.NOT_OWNED -> {
                finish(ActionStatus.UNKNOWN)
                OrionProactiveDeliveryOutcome.UNKNOWN
            }
        }
    }

    private fun authority(expected: NormalizedActionRequest, currentlyEligible: () -> Boolean,
        platformAvailable: () -> Boolean, platformPermissionGranted: () -> Boolean) = object : ActionExecutionAuthority {
        override fun startupBarrierOpen() = barrier.isOpen()
        override fun descriptorFor(skill: String) = descriptor.takeIf { it.name == skill }
        override fun currentPolicyVersion() = policyVersion
        override fun policyAllows(request: NormalizedActionRequest) = request == expected && currentlyEligible()
        override fun capabilityAvailable(capability: Capability) =
            capability == OrionProactiveNotificationDescriptor.capability && platformAvailable()
        override fun permissionGranted(permission: String) =
            permission == OrionPostNotificationDescriptor.POST_PERMISSION && platformPermissionGranted()
        override fun userPresent() = false
    }

    private fun normalized(payload: ValidatedPostNotification, consentVersion: Long) = NormalizedActionRequest(
        payload.requestId, descriptor.name, payload.canonicalNormalizedArguments,
        CONSENT_TARGET + consentVersion, descriptor.authorizationLevel, descriptor.argumentSchemaVersion,
        descriptor.sideEffectClass, descriptor.recoveryStrategy, descriptor.idempotencyMode, policyVersion,
    )

    private fun notification(id: UUID): ValidatedPostNotification =
        (OrionPostNotificationPreparer.prepare(PostNotification(id, OrionProactiveNotificationDescriptor.TITLE,
            OrionProactiveNotificationDescriptor.BODY, id, id)) as PostNotificationPreparation.Ready).notification

    companion object {
        private const val CONSENT_TARGET = OrionAndroidNotificationPoster.PROACTIVE_CHANNEL + "/consent/"
        private val AUTHORIZATION_LIFETIME = Duration.ofMinutes(5)
        private val EXECUTION_LEASE = Duration.ofSeconds(30)

        private fun androidPoster(context: Context, clock: OrionClock): OrionNotificationPoster =
            OrionAndroidNotificationPoster(context, clock, OrionAndroidNotificationPoster.PROACTIVE_CHANNEL) { id ->
                val app = context.applicationContext
                fun feedback(kind: String): PendingIntent {
                    val intent = Intent("network.orion.SENTINEL_FEEDBACK", Uri.parse("orion://sentinel/$id/$kind"))
                    return if (kind == "OPENED") {
                        intent.setClassName(app, "network.orion.app.sentinel.OrionSentinelOpenActivity")
                        PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    } else {
                        intent.setClassName(app, "network.orion.app.sentinel.OrionSentinelFeedbackReceiver")
                        PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    }
                }
                setContentIntent(feedback("OPENED"))
                setDeleteIntent(feedback("DISMISSED"))
                setAutoCancel(true)
                addAction(Notification.Action.Builder(null, "Pausar por 24 h", feedback("SNOOZED")).build())
            }
    }
}
