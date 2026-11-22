package network.orion.app.sentinel

import android.app.NotificationManager
import android.content.Context
import network.orion.app.notifications.OrionAndroidNotificationPoster
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
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val dao = db.sentinel()
    private val policy = OrionProactivityPolicy()
    private val ingress = DurableIngressCoordinator(
        OrionDurableIngressStore(db, encryption, clock, barrier, LocalIngressConsumer(::consume), SCOPE),
        barrier, readiness, storageAvailable,
    )

    fun enabled(): Boolean = dao.consent()?.enabled == true

    fun personalized(): Boolean = dao.consent()?.personalizationSince != null

    fun setEnabled(enabled: Boolean): Boolean = updatePreference(if (enabled) ENABLE else DISABLE) {
        this.enabled() == enabled
    }

    fun setPersonalized(enabled: Boolean): Boolean = updatePreference(
        if (enabled) PERSONALIZE else RESET_PERSONALIZATION,
    ) { personalized() == enabled }

    private fun updatePreference(type: String, applied: () -> Boolean): Boolean {
        val id = UUID.randomUUID()
        if (submit(id, type, id, SOURCE_SETTINGS) !in accepted) return false
        ingress.drain(25)
        return applied()
    }

    fun receive(id: UUID, kind: OrionProactivityFeedbackKind): Boolean {
        if (kind !in interactions || submit(id, kind.name, key(id, kind.name), SOURCE_ANDROID) !in accepted) return false
        ingress.drain(25)
        val recorded = dao.feedback(id.toString()).any { it.kind == kind.name }
        if (recorded && kind == OrionProactivityFeedbackKind.SNOOZED) reconcileSnoozedNotifications()
        return recorded
    }

    fun maintain(evaluate: Boolean, lastInteraction: Instant?): OrionHealthStatus {
        ingress.drain(25)
        reconcileSnoozedNotifications()
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
                if (dao.prune(before, now.minus(candidateLifetime).toEpochMilli()) > 0) {
                    dao.advanceEvaluationFloor(now.toEpochMilli())
                }
            }
        }
        if (!enabled()) return OrionHealthStatus.HEALTHY
        val resources = observeResources()
        if (!resources.policy.allowsProactivity || !delivery.available()) return OrionHealthStatus.DEGRADED
        if (!evaluate) return OrionHealthStatus.HEALTHY
        if (now.toEpochMilli() < (dao.consent()?.evaluationFloor ?: 0)) return OrionHealthStatus.DEGRADED
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
        val consentFloor = dao.consent()?.personalizationSince
        if (consentFloor != null) {
            val samples = dao.feedbackSamples(consentFloor, now.minus(retention).toEpochMilli(), now.toEpochMilli())
                .map { Instant.ofEpochMilli(it.shownAt) to OrionProactivityFeedbackKind.valueOf(it.outcome) }
            if (OrionProactivityHabits.shouldDefer(now, context.zoneId, samples)) return OrionHealthStatus.HEALTHY
        }
        if (submit(candidate.sourceId, PREPARE, id, SOURCE_HEARTBEAT) !in accepted) return OrionHealthStatus.DEGRADED
        ingress.drain(25)
        val selection = dao.suggestion(id.toString()) ?: return OrionHealthStatus.DEGRADED
        val result = delivery.deliver(id, policyAllows = { version -> stillEligible(selection, version) }, onPosted = {
            // NOTIFICATION_POSTED is the durable event for this same fact.
            check(dao.record(OrionSentinelFeedback(selection.suggestionId, "SHOWN",
                clock.wallNow().toEpochMilli(), null)) != -1L)
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
                    val consent = existing ?: OrionSentinelConsent(enabled = false, version = -1)
                    dao.saveConsent(consent.copy(enabled = enabled, version = consent.version + 1))
                    publish(ref, "SENTINEL_CONSENT_CHANGED", now)
                }
            }
            PERSONALIZE, RESET_PERSONALIZATION -> {
                if (payload.source != SOURCE_SETTINGS || payload.idempotencyKey != ref) return LocalIngressDisposition.REJECTED
                val consent = dao.consent() ?: OrionSentinelConsent(enabled = false, version = 0)
                val enabled = payload.commandType == PERSONALIZE
                if (enabled != (consent.personalizationSince != null)) {
                    val floor = if (enabled) maxOf(now.toEpochMilli(), dao.latestReservationAt() ?: Long.MIN_VALUE)
                        else null
                    dao.saveConsent(consent.copy(personalizationSince = floor))
                    publish(ref, "SENTINEL_PERSONALIZATION_CHANGED", now)
                }
            }
            PREPARE -> {
                val id = key(ref, PREPARE)
                val blockedAfter = now.minus(policy.topicCooldown).toEpochMilli()
                if (payload.source != SOURCE_HEARTBEAT || payload.idempotencyKey != id || dao.reservationCount() >= MAX_RESERVATIONS ||
                    quietHours.contains(now.atZone(zone()).toLocalTime()) || nextTask(now, id)?.taskId != ref.toString() ||
                    db.events().count() >= 10_000 ||
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
        if (shown == null) {
            // An immutable interaction PendingIntent may survive a crash after notify().
            // Accept that interaction without resolving the action or inventing SHOWN.
            if (kind !in interactions || dao.suggestion(id) == null ||
                db.actions().find(id)?.status !in uncertainDelivery) return false
        }
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

    /** Reconcile a derived Android artifact from committed feedback. Repeated cancellation is safe. */
    private fun reconcileSnoozedNotifications() {
        if (!barrier.isOpen()) return
        val manager = notificationManager ?: return
        manager.activeNotifications.filter { entry ->
            entry.id == 0 && entry.notification.channelId == OrionAndroidNotificationPoster.PROACTIVE_CHANNEL
        }.forEach { entry ->
            val tag = entry.tag ?: return@forEach
            if (dao.wasSnoozed(tag) && barrier.isOpen()) manager.cancel(tag, entry.id)
        }
    }

    private fun nextTask(now: Instant, preparing: UUID? = null): OrionSentinelTask? {
        if (now.toEpochMilli() < (dao.consent()?.evaluationFloor ?: 0)) return null
        val oldest = now.minus(candidateLifetime).toEpochMilli()
        val newest = now.minus(Duration.ofDays(1)).toEpochMilli()
        var afterTime = oldest
        var afterId = ""
        while (true) {
            val tasks = dao.openTasks(oldest, newest, afterTime, afterId)
            val last = tasks.lastOrNull() ?: return null
            val candidate = tasks.firstOrNull { task ->
                val id = key(UUID.fromString(task.taskId), PREPARE)
                // Existing ingress owns this stable ID, even if it expired before reserving.
                id == preparing || db.ingress().findByKey(SCOPE, id.toString()) == null
            }
            if (candidate != null) return candidate
            afterTime = last.createdAt
            afterId = last.taskId
        }
    }

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
        private const val PERSONALIZE = "PERSONALIZE"
        private const val RESET_PERSONALIZATION = "RESET_PERSONALIZATION"
        private const val SOURCE_SETTINGS = "orion_sentinel_settings"
        private const val SOURCE_ANDROID = "orion_sentinel_android"
        private const val SOURCE_HEARTBEAT = "orion_heartbeat"
        private const val MAX_RESERVATIONS = 512
        private val accepted = setOf(DurableIngressOutcome.QUEUED, DurableIngressOutcome.DEDUP)
        private val feedbackTimeout = Duration.ofDays(1)
        private val retention = Duration.ofDays(30)
        private val candidateLifetime = Duration.ofDays(7)
        internal val interactions = setOf(OrionProactivityFeedbackKind.OPENED,
            OrionProactivityFeedbackKind.DISMISSED, OrionProactivityFeedbackKind.SNOOZED)
        private val uncertainDelivery = setOf(network.orion.core.actions.ActionStatus.RUNNING,
            network.orion.core.actions.ActionStatus.UNKNOWN, network.orion.core.actions.ActionStatus.MANUAL_REVIEW)

        private fun key(id: UUID, operation: String): UUID =
            UUID.nameUUIDFromBytes("orion_sentinel/v1/$operation/$id".toByteArray(Charsets.UTF_8))
    }
}
