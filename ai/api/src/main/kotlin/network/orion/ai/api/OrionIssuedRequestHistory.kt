package network.orion.ai.api

import java.util.TreeMap
import java.util.UUID

/** A duplicate whose original result has been retired. Never fabricate another terminal result. */
class OrionRetiredRequestException : IllegalStateException("Request already retired; historical result unavailable.")
class OrionRequestHistoryFullException : IllegalStateException("Request history has no terminal entry to retire.")

/**
 * ADR-0002: bounded ephemeral results plus an irreversible retirement watermark.
 * IDs convey identity only. Issue before queueing; admission still requires policy,
 * time, resource and transport checks. Active entries can remain below the watermark
 * until they finish; their exact entry always wins over the retirement check.
 */
class OrionIssuedRequestHistory<T> internal constructor(
    private val capacity: Int,
    private val scope: String,
    private var lastIssued: Long,
    private val terminal: (T) -> Boolean,
) {
    constructor(capacity: Int = 64, terminal: (T) -> Boolean) : this(capacity, UUID.randomUUID().toString(), 0, terminal)

    private val entries = TreeMap<Long, T>()
    private var retiredThrough = 0L
    init { require(capacity in 1..1024 && scope.isNotBlank() && '/' !in scope && lastIssued >= 0) }

    @Synchronized fun issue(): String {
        check(lastIssued < Long.MAX_VALUE) { "Request identity sequence exhausted." }
        lastIssued++
        return "$PREFIX$scope/$lastIssued"
    }

    @Synchronized fun find(id: String): T? {
        val sequence = sequence(id)
        entries[sequence]?.let { return it }
        if (sequence <= retiredThrough) throw OrionRetiredRequestException()
        return null
    }

    @Synchronized fun hasCapacity(): Boolean = entries.size < capacity || entries.values.any(terminal)

    @Synchronized fun put(id: String, entry: T) {
        val sequence = sequence(id)
        check(sequence !in entries) { "Request already registered." }
        if (sequence <= retiredThrough) throw OrionRetiredRequestException()
        if (entries.size >= capacity) {
            val victim = entries.entries.firstOrNull { terminal(it.value) }
                ?: throw OrionRequestHistoryFullException()
            retiredThrough = maxOf(retiredThrough, victim.key)
            entries.entries.removeAll { it.key <= retiredThrough && terminal(it.value) }
        }
        // An old reservation can itself fall below the newly advanced watermark.
        if (sequence <= retiredThrough) throw OrionRetiredRequestException()
        entries[sequence] = entry
    }

    val retainedCount: Int @Synchronized get() = entries.size

    private fun sequence(id: String): Long {
        val prefix = "$PREFIX$scope/"
        require(id.startsWith(prefix)) { "Request identity belongs to another scope." }
        val suffix = id.substring(prefix.length)
        val sequence = suffix.toLongOrNull()
        require(sequence != null && sequence in 1..lastIssued && suffix == sequence.toString()) {
            "Request identity was not issued."
        }
        return sequence
    }

    companion object {
        private const val PREFIX = "orion_request/"
        fun isIssuedIdentity(id: String): Boolean = id.startsWith(PREFIX)
    }
}
