package network.orion.data.events

import network.orion.core.time.WallClock
import network.orion.data.database.DomainEventOutboxEntity
import network.orion.data.database.OrionEventDao
import kotlin.random.Random

enum class DurableDeliveryResult { ACCEPTED, RETRY_LATER, PERMANENT_FAILURE }

/** Consumers must commit local mutation and a dedup receipt atomically; no external Skills here. */
fun interface DurableEventConsumer {
    fun consume(event: DomainEventOutboxEntity): DurableDeliveryResult
}

class EventRetryPolicy(
    val maxAttempts: Int = 5,
    private val baseDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 3_600_000,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    init {
        require(maxAttempts in 1..100 && baseDelayMillis >= 2 && maxDelayMillis >= baseDelayMillis)
    }

    fun nextAttemptAt(now: Long, attempt: Int): Long {
        require(attempt in 1..maxAttempts && now < Long.MAX_VALUE)
        var cap = baseDelayMillis
        repeat(attempt - 1) { cap = if (cap > maxDelayMillis / 2) maxDelayMillis else minOf(maxDelayMillis, cap * 2) }
        val sample = jitter()
        require(sample.isFinite() && sample >= 0.0 && sample < 1.0)
        val floor = maxOf(1, cap / 2)
        val delay = minOf(cap, floor + ((cap - floor).toDouble() * sample).toLong())
        return if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
}

data class EventDispatchReport(val examined: Int, val delivered: Int, val deferred: Int, val deadLettered: Int)

/**
 * Bounded synchronous dispatcher. Reserve commits attempt/backoff before invoking consumers.
 * A process death after a consumer commit may redeliver; the durable receipt prevents duplicate mutation.
 * Unexpected consumer exceptions propagate; the persisted retry schedule survives, with no payload logging.
 */
class OrionEventDispatcher(
    private val events: OrionEventDao,
    private val clock: WallClock,
    consumers: Map<String, List<DurableEventConsumer>>,
    private val retry: EventRetryPolicy = EventRetryPolicy(),
) {
    private val routes = consumers.mapValues { it.value.toList() }.toMap()

    init { require(routes.values.all { it.size in 1..32 }) }

    fun dispatchBatch(limit: Int = 25): EventDispatchReport {
        require(limit in 1..100)
        val now = clock.wallNow().toEpochMilli()
        val due = events.due(now, limit)
        var delivered = 0
        var deferred = 0
        var dead = 0
        for (event in due) {
            val handlers = routes[event.eventType]
            if (event.schemaVersion != 1 || handlers.isNullOrEmpty() || event.attemptCount >= retry.maxAttempts) {
                if (events.deadLetter(event.eventId, event.version)) dead++
                continue
            }
            val claimed = events.reserveAttempt(event.eventId, event.version, now,
                retry.nextAttemptAt(now, event.attemptCount + 1), retry.maxAttempts) ?: continue
            var result = DurableDeliveryResult.ACCEPTED
            for (handler in handlers) {
                result = handler.consume(claimed)
                if (result != DurableDeliveryResult.ACCEPTED) break
            }
            when (result) {
                DurableDeliveryResult.ACCEPTED -> if (events.delivered(claimed.eventId, claimed.version, clock.wallNow().toEpochMilli())) delivered++
                DurableDeliveryResult.PERMANENT_FAILURE -> if (events.deadLetter(claimed.eventId, claimed.version)) dead++
                DurableDeliveryResult.RETRY_LATER -> {
                    events.failed(claimed.eventId, claimed.version, retry.maxAttempts)
                    if (claimed.attemptCount >= retry.maxAttempts) dead++ else deferred++
                }
            }
        }
        return EventDispatchReport(due.size, delivered, deferred, dead)
    }
}
