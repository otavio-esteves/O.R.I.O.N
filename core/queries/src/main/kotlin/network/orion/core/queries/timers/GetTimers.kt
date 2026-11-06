package network.orion.core.queries.timers

import java.util.Collections
import java.util.UUID
import network.orion.core.queries.Query

/** Query never writes expiry, drains ingress, or schedules delivery. */
data class GetTimers(val after: UUID? = null, val limit: Int = 50) : Query<TimerPage> {
    init { require(limit in 1..100) }
}
data class GetTimerStatus(val timerId: UUID) : Query<TimerSummary?>
enum class TimerState { ACTIVE, ELAPSED, CANCELLED }
enum class TimerContentState { READABLE, UNREADABLE, KEY_UNAVAILABLE }
enum class TimerClockBasis { MONOTONIC, WALL_FALLBACK, UNAVAILABLE }

class TimerSummary(val timerId: UUID, val title: String?, val state: TimerState, val version: Long,
    val contentState: TimerContentState, val durationMillis: Long, val remainingMillis: Long?, val clockBasis: TimerClockBasis) {
    init {
        require(version >= 0 && durationMillis > 0)
        require((title != null) == (contentState == TimerContentState.READABLE))
        require(remainingMillis == null || remainingMillis in 0..durationMillis)
    }
    override fun toString() = "TimerSummary(<redacted>)"
}
class TimerPage(items: List<TimerSummary>, val next: UUID?) {
    val items: List<TimerSummary> = Collections.unmodifiableList(ArrayList(items))
    init { require(items.size <= 100); require(next == null || items.lastOrNull()?.timerId == next) }
    override fun toString() = "TimerPage(<redacted>)"
}
fun interface TimerQuery { fun get(request: GetTimers): TimerPage }

/** Pure arithmetic for injected snapshots; no system-clock or Android access. */
object OrionTimerRemaining {
    data class Reading(val remainingMillis: Long?, val basis: TimerClockBasis)
    fun read(durationMillis: Long, deadlineWallAt: Long, startBoot: Int, startElapsed: Long,
        currentBoot: Int?, nowElapsed: Long, nowWall: Long): Reading {
        require(durationMillis > 0 && startBoot >= 0 && startElapsed >= 0 && nowElapsed >= 0)
        if (currentBoot == startBoot) {
            if (nowElapsed < startElapsed) return Reading(null, TimerClockBasis.UNAVAILABLE)
            return Reading((durationMillis - (nowElapsed - startElapsed)).coerceAtLeast(0), TimerClockBasis.MONOTONIC)
        }
        val remaining = if (nowWall >= deadlineWallAt) 0 else try {
            Math.subtractExact(deadlineWallAt, nowWall).coerceAtMost(durationMillis)
        } catch (_: ArithmeticException) { durationMillis }
        return Reading(remaining, TimerClockBasis.WALL_FALLBACK)
    }
}
