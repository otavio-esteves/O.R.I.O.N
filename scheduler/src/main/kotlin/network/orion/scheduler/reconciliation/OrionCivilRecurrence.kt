package network.orion.scheduler.reconciliation

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure civil recurrence progression, supplied by the validated domain rule.
 * Return null when the rule ends; every returned candidate must be strictly later
 * in local civil time. No platform scheduler or wall clock is consulted here.
 */
fun interface RecurrenceRule {
    fun nextAfter(previousLocalDateTime: LocalDateTime): LocalDateTime?
}

enum class CivilTimeDecision {
    NORMAL,
    GAP_FIRST_VALID_TIME,
    OVERLAP_PREVIOUS_OFFSET,
    OVERLAP_EARLIER_OFFSET,
}

/** Selected scheduling metadata for the future durable reminder audit, without user content. */
data class CivilOccurrence(
    val requestedLocalDateTime: LocalDateTime,
    val resolvedLocalDateTime: LocalDateTime,
    val zoneId: ZoneId,
    val offset: ZoneOffset,
    val instant: Instant,
    val decision: CivilTimeDecision,
)

sealed interface CivilOccurrenceSearch {
    data class Found(val occurrence: CivilOccurrence) : CivilOccurrenceSearch
    data object RuleEnded : CivilOccurrenceSearch
    data object SearchLimitReached : CivilOccurrenceSearch
}

/**
 * Architecture §§38–39: resolve each civil occurrence separately. GAP uses the
 * transition's first valid local instant, rather than preserving minutes inside
 * the gap. OVERLAP preserves the previous occurrence's offset when still valid,
 * otherwise takes the earlier offset. The caller persists the returned decision
 * together with its reminder; this preparatory component grants no action authority.
 */
object OrionCivilRecurrence {
    const val MAX_CANDIDATES = 10_000

    fun resolve(
        localDateTime: LocalDateTime,
        zoneId: ZoneId,
        previousOffset: ZoneOffset? = null,
    ): CivilOccurrence {
        val rules = zoneId.rules
        val offsets = rules.getValidOffsets(localDateTime)
        val resolved: LocalDateTime
        val offset: ZoneOffset
        val decision: CivilTimeDecision
        when (offsets.size) {
            0 -> {
                val transition = checkNotNull(rules.getTransition(localDateTime))
                check(transition.isGap)
                resolved = transition.dateTimeAfter
                offset = transition.offsetAfter
                decision = CivilTimeDecision.GAP_FIRST_VALID_TIME
            }
            1 -> {
                resolved = localDateTime
                offset = offsets.single()
                decision = CivilTimeDecision.NORMAL
            }
            else -> {
                resolved = localDateTime
                if (previousOffset != null && previousOffset in offsets) {
                    offset = previousOffset
                    decision = CivilTimeDecision.OVERLAP_PREVIOUS_OFFSET
                } else {
                    offset = offsets.first()
                    decision = CivilTimeDecision.OVERLAP_EARLIER_OFFSET
                }
            }
        }
        return CivilOccurrence(localDateTime, resolved, zoneId, offset, resolved.toInstant(offset), decision)
    }

    /**
     * Same-zone convenience for an existing civil occurrence. For timezone
     * changes use the typed overload with the actual previous occurrence so its
     * historical instant is never reinterpreted in the new zone.
     */
    fun nextOccurrence(
        previousLocalDateTime: LocalDateTime,
        zoneId: ZoneId,
        rule: RecurrenceRule,
        after: Instant,
        previousOffset: ZoneOffset? = null,
        maxCandidates: Int = 366,
    ): CivilOccurrenceSearch = nextOccurrence(
        resolve(previousLocalDateTime, zoneId, previousOffset), zoneId, rule, after, maxCandidates,
    )

    /**
     * Searches strictly after both [previousOccurrence]'s actual instant and [after].
     * Explicit [after] is sampled by the caller through OrionClock when needed;
     * jumps in wall time never become a duration or an unbounded catch-up loop.
     * The previous actual occurrence's offset remains authoritative throughout
     * the search: skipped candidates were never delivered occurrences.
     */
    fun nextOccurrence(
        previousOccurrence: CivilOccurrence,
        zoneId: ZoneId,
        rule: RecurrenceRule,
        after: Instant,
        maxCandidates: Int = 366,
    ): CivilOccurrenceSearch {
        require(maxCandidates in 1..MAX_CANDIDATES) { "Candidate limit outside supported bounds." }
        val cutoff = maxOf(after, previousOccurrence.instant)
        var previous = previousOccurrence.requestedLocalDateTime
        repeat(maxCandidates) {
            val candidate = rule.nextAfter(previous) ?: return CivilOccurrenceSearch.RuleEnded
            require(candidate > previous) { "Civil recurrence must advance strictly." }
            val occurrence = resolve(candidate, zoneId, previousOccurrence.offset)
            if (occurrence.instant > cutoff) return CivilOccurrenceSearch.Found(occurrence)
            previous = candidate
        }
        return CivilOccurrenceSearch.SearchLimitReached
    }
}
