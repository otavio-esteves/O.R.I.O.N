package network.orion.app.sentinel

import android.content.Context
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import network.orion.app.resources.OrionAndroidResourceProfileObserver
import network.orion.app.resources.OrionAndroidResourceProfileSnapshot
import network.orion.core.health.CoreReadiness
import network.orion.core.health.OrionHealthStatus
import network.orion.core.ingress.DurableIngressCoordinator
import network.orion.core.ingress.DurableIngressOutcome
import network.orion.core.ingress.DurableIngressRequest
import network.orion.core.ingress.StartupBarrier
import network.orion.core.model.Sensitivity
import network.orion.core.sentinel.*
import network.orion.core.security.OrionFieldEncryption
import network.orion.core.time.OrionClock
import network.orion.data.database.*
import network.orion.data.repository.LocalIngressConsumer
import network.orion.data.repository.LocalIngressDisposition
import network.orion.data.repository.LocalIngressPayload
import network.orion.data.repository.OrionDurableIngressStore

/** Serialized by the MAIN runtime. Room writes are admitted by the common operational barrier. */
internal class OrionSentinelRuntime(
    context: Context,
    private val db: OrionDatabase,
    encryption: OrionFieldEncryption,
    private val clock: OrionClock,
    private val barrier: StartupBarrier,
    readiness: () -> CoreReadiness,
    storageAvailable: () -> Boolean,
    private val health: () -> OrionHealthStatus,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val observeResources: () -> OrionAndroidResourceProfileSnapshot = OrionAndroidResourceProfileObserver(context)::observe,
    private val delivery: OrionProactiveDelivery = OrionProactiveDelivery(context, db, encryption, clock, barrier),
    private val quietHours: OrionQuietHours = OrionQuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0)),
) {
    private val dao = db.sentinel()
    private val policy = OrionProactivityPolicy()
    private val ingress = DurableIngressCoordinator(
        OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer(::consume), SCOPE),
        barrier, readiness, storageAvailable,
    )

    fun enabled(): Boolean = dao.consent()?.enabled == true

    fun setEnabled(enabled: Boolean): Boolean {
        val id = UUID.randomUUID()
        val type = if (enabled) ENABLE else DISABLE
        if (submit(id, type, id, SOURCE_SETTINGS) !in accepted) return false
        ingress.drain(25)
        return this.enabled() == enabled
    }

    fun receive(id: UUID, kind: OrionProactivityFeedbackKind): Boolean {
        if (kind !in interactions || submit(id, kind.name, key(id, kind.name), SOURCE_ANDROID) !in accepted) return false
        ingress.drain(25)
        return dao.feedback(id.toString()).any { it.kind == kind.name }
    }

    fun maintain(evaluate: Boolean, lastInteraction: Instant?): OrionHealthStatus {
        ingress.drain(25)
        delivery.discardExpired()
        val now = clock.wallNow()
        barrier.whileOpen {
            db.runInTransaction {
                dao.needsFeedback(now.minus(feedbackTimeout).toEpochMilli()).forEach { suggestion ->
                    val task = db.tasks().find(suggestion.taskId)
                    if (task?.status == TaskStatus.COMPLETED) {
                        record(suggestion.suggestionId, OrionProactivityFeedbackKind.COMPLETED, Instant.ofEpochMilli(task.updatedAt))
                    } else record(suggestion.suggestionId, OrionProactivityFeedbackKind.IGNORED, now)
                }
                val before = now.minus(retention).toEpochMilli()
                dao.pruneFeedback(before)
                dao.prune(before)
            }
        }
        if (!enabled()) return OrionHealthStatus.HEALTHY
        val resources = observeResources()
        if (!resources.policy.allowsProactivity || !delivery.available()) return OrionHealthStatus.DEGRADED
        if (!evaluate) return OrionHealthStatus.HEALTHY
        if (dao.reservationCount() >= MAX_RESERVATIONS) return OrionHealthStatus.DEGRADED
        val task = nextTask(now) ?: return OrionHealthStatus.HEALTHY
        val id = key(UUID.fromString(task.taskId), PREPARE)
        val lastWindow = dao.recent(now.minus(retention).toEpochMilli())
        val feedback = lastWindow.flatMap { dao.feedback(it.suggestionId) }
        val dismissed = latest(feedback, OrionProactivityFeedbackKind.DISMISSED)
        val snoozed = latest(feedback, OrionProactivityFeedbackKind.SNOOZED)?.plus(feedbackTimeout)
        val last = lastWindow.firstOrNull()?.reservedAt?.let(Instant::ofEpochMilli)
        val context = OrionContextSnapshot(
            now, zone(), resources.batteryPercent, resources.charging,
            resources.thermalStatus?.let { OrionThermalState.entries.getOrNull(it) },
            resources.screenInteractive, quietHours, 0, health(), lastInteraction,
            dao.remindersDueSoon(now.toEpochMilli(), now.plus(feedbackTimeout).toEpochMilli()),
        )
        val age = Duration.between(Instant.ofEpochMilli(task.createdAt), now)
        val candidate = OrionProactivityCandidate(UUID.fromString(task.taskId), OrionProactivityTopic.OPEN_TASKS,
            urgency = (75 + age.toHours() / 4).coerceAtMost(100).toInt(), priority = 80,
            deadline = null, importance = 85, contextRelevance = if (context.upcomingReminders == 0) 90 else 70)
        val history = OrionProactivityHistory(false, last, last,
            lastWindow.count { it.reservedAt > now.minus(policy.notificationWindow).toEpochMilli() }, dismissed, snoozed)
        if (policy.evaluate(context, candidate, history) !is OrionProactivityDecision.Proposal) return OrionHealthStatus.HEALTHY
        if (submit(candidate.sourceId, PREPARE, id, SOURCE_HEARTBEAT) !in accepted) return OrionHealthStatus.DEGRADED
        ingress.drain(25)
        val selection = dao.suggestion(id.toString()) ?: return OrionHealthStatus.DEGRADED
        val result = delivery.deliver(id, policyAllows = { version -> stillEligible(selection, version) }, onPosted = {
            record(selection.suggestionId, OrionProactivityFeedbackKind.SHOWN, clock.wallNow())
        })
        return when (result) {
            OrionProactiveDeliveryOutcome.POSTED -> OrionHealthStatus.HEALTHY
            OrionProactiveDeliveryOutcome.BLOCKED -> {
                delivery.discard(id)
                OrionHealthStatus.DEGRADED
            }
            OrionProactiveDeliveryOutcome.UNKNOWN -> OrionHealthStatus.DEGRADED
        }
    }

    private fun consume(payload: LocalIngressPayload): LocalIngressDisposition {
        val ref = payload.referenceId ?: return LocalIngressDisposition.REJECTED
        if (payload.scope != SCOPE || payload.schemaVersion != 1 || payload.sensitivity != Sensitivity.PERSONAL)
            return LocalIngressDisposition.REJECTED
        val now = clock.wallNow()
        when (payload.commandType) {
            ENABLE, DISABLE -> {
                val enabled = payload.commandType == ENABLE
                if (payload.source != SOURCE_SETTINGS || payload.idempotencyKey != ref) return LocalIngressDisposition.REJECTED
                val existing = dao.consent()
                if (existing?.enabled != enabled) {
                    val version = (existing?.version ?: -1) + 1
                    dao.saveConsent(OrionSentinelConsent(enabled = enabled, version = version))
                    publish(ref, "SENTINEL_CONSENT_CHANGED", now)
                }
            }
            PREPARE -> {
                val id = key(ref, PREPARE)
                val blockedAfter = now.minus(policy.topicCooldown).toEpochMilli()
                if (payload.source != SOURCE_HEARTBEAT || payload.idempotencyKey != id || dao.reservationCount() >= MAX_RESERVATIONS ||
                    quietHours.contains(now.atZone(zone()).toLocalTime()) || nextTask(now)?.taskId != ref.toString() ||
                    dao.recent(blockedAfter + 1).isNotEmpty())
                    return LocalIngressDisposition.REJECTED
                val consentVersion = dao.consent()?.takeIf { it.enabled }?.version
                if (!delivery.prepare(id, consentVersion)) return LocalIngressDisposition.REJECTED
                dao.reserve(OrionSentinelSuggestion(id.toString(), ref.toString(), now.toEpochMilli()))
            }
            else -> {
                val kind = interactions.singleOrNull { it.name == payload.commandType }
                    ?: return LocalIngressDisposition.REJECTED
                if (payload.source != SOURCE_ANDROID || payload.idempotencyKey != key(ref, kind.name))
                    return LocalIngressDisposition.REJECTED
                if (!record(ref.toString(), kind, Instant.ofEpochMilli(payload.receivedAt))) return LocalIngressDisposition.REJECTED
            }
        }
        return LocalIngressDisposition.CONSUMED
    }

    private fun stillEligible(suggestion: OrionSentinelSuggestion, consentVersion: Long): Boolean {
        val now = clock.wallNow()
        val task = db.tasks().find(suggestion.taskId)
        return barrier.isOpen() && dao.consent()?.let { it.enabled && it.version == consentVersion } == true &&
            !quietHours.contains(now.atZone(zone()).toLocalTime()) &&
            now.toEpochMilli() >= suggestion.reservedAt && now.toEpochMilli() - suggestion.reservedAt < 60_000 &&
            task?.status == TaskStatus.OPEN && task.sensitivity in setOf(Sensitivity.PUBLIC, Sensitivity.PERSONAL) &&
            observeResources().policy.allowsProactivity && barrier.isOpen()
    }

    private fun record(id: String, kind: OrionProactivityFeedbackKind, at: Instant): Boolean {
        val feedback = dao.feedback(id)
        val shown = latest(feedback, OrionProactivityFeedbackKind.SHOWN)
        if (kind != OrionProactivityFeedbackKind.SHOWN && shown == null) return false
        val event = OrionProactivityFeedback.from(kind, at, shown)
        if (dao.record(OrionSentinelFeedback(id, kind.name, at.toEpochMilli(), event.timeToAction?.toMillis())) != -1L) {
            publish(key(UUID.fromString(id), kind.name), "SENTINEL_FEEDBACK_RECORDED", at)
        }
        return true
    }

    private fun publish(id: UUID, type: String, at: Instant) {
        val reference = id.toString()
        db.events().append(DomainEventOutboxEntity(reference, reference, 0, type, 1,
            Sensitivity.PERSONAL, at.toEpochMilli(), reference))
    }

    private fun nextTask(now: Instant) = dao.nextOpenTask(
        now.minus(Duration.ofDays(7)).toEpochMilli(), now.minus(Duration.ofDays(1)).toEpochMilli())

    private fun submit(reference: UUID, type: String, id: UUID, source: String) = ingress.submit(
        DurableIngressRequest(UUID.randomUUID(), SCOPE, id, source, type, 1, Sensitivity.PERSONAL,
            true, reference, null, (if (type == PREPARE) Duration.ofMinutes(1) else retention).toMillis(),
            Duration.ofDays(90).toMillis(), correlationId = id),
    )

    private fun latest(feedback: List<OrionSentinelFeedback>, kind: OrionProactivityFeedbackKind): Instant? =
        feedback.filter { it.kind == kind.name }.maxOfOrNull { it.occurredAt }?.let(Instant::ofEpochMilli)

    companion object {
        private const val SCOPE = "sentinel"
        private const val PREPARE = "PREPARE"
        private const val ENABLE = "ENABLE"
        private const val DISABLE = "DISABLE"
        private const val SOURCE_SETTINGS = "orion_sentinel_settings"
        private const val SOURCE_ANDROID = "orion_sentinel_android"
        private const val SOURCE_HEARTBEAT = "orion_heartbeat"
        private const val MAX_RESERVATIONS = 512
        private val accepted = setOf(DurableIngressOutcome.QUEUED, DurableIngressOutcome.DEDUP)
        private val feedbackTimeout = Duration.ofDays(1)
        private val retention = Duration.ofDays(30)
        internal val interactions = setOf(OrionProactivityFeedbackKind.OPENED,
            OrionProactivityFeedbackKind.DISMISSED, OrionProactivityFeedbackKind.SNOOZED)

        private fun key(id: UUID, operation: String): UUID =
            UUID.nameUUIDFromBytes("orion_sentinel/v1/$operation/$id".toByteArray(Charsets.UTF_8))
    }
}
