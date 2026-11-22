package network.orion.core.sentinel

import java.time.Instant
import java.time.ZoneId

/** Estimate reception in local four-hour slots using one outcome per shown suggestion. */
object OrionProactivityHabits {
    fun shouldDefer(
        now: Instant,
        zone: ZoneId,
        samples: List<Pair<Instant, OrionProactivityFeedbackKind>>,
    ): Boolean {
        val slot = now.atZone(zone).hour / HOURS_PER_SLOT
        val weights = samples.asSequence()
            .filter { (shownAt, _) -> shownAt <= now && shownAt.atZone(zone).hour / HOURS_PER_SLOT == slot }
            .mapNotNull { (_, outcome) -> weight(outcome) }
            .toList()
        return weights.size >= MIN_SAMPLES && weights.sum() <= -weights.size
    }

    private fun weight(outcome: OrionProactivityFeedbackKind): Int? = when (outcome) {
        OrionProactivityFeedbackKind.COMPLETED -> 3
        OrionProactivityFeedbackKind.OPENED -> 2
        OrionProactivityFeedbackKind.DISMISSED -> -3
        OrionProactivityFeedbackKind.SNOOZED, OrionProactivityFeedbackKind.IGNORED -> -1
        OrionProactivityFeedbackKind.SHOWN -> null
    }

    private const val HOURS_PER_SLOT = 4
    private const val MIN_SAMPLES = 3
}
