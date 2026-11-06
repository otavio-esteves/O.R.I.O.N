package network.orion.core.commands.timers

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CreateTimerPreparationTest {
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val key = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private fun command(title: String = "Café", duration: Long = 60_000) = CreateTimer(id, title, duration, key, id)
    private fun ready(c: CreateTimer) = (OrionCreateTimerPreparer.prepare(c) as CreateTimerPreparation.Ready).timer
    private fun reason(c: CreateTimer) = (OrionCreateTimerPreparer.prepare(c) as CreateTimerPreparation.Rejected).reason
    @Test fun canonicalMaterialBindsExactDurationAndTargetAndEscapesTitle() {
        val value = ready(command(" Café \"x\"\\😀 "))
        assertEquals("""{"timerId":"00000000-0000-0000-0000-000000000001","title":"Café \"x\"\\😀","durationMillis":60000}""", value.canonicalNormalizedArguments)
        assertEquals("timer/$id", value.normalizedTarget)
        assertEquals(60_000L, value.durationMillis)
    }
    @Test fun durationBoundRejectsZeroNegativeAndOverflowSizedValues() {
        for (n in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, OrionCreateTimerPreparer.MAX_DURATION_MILLIS + 1))
            assertEquals(CreateTimerRejection.INVALID_DURATION, reason(command(duration = n)))
        assertEquals(1L, ready(command(duration = 1)).durationMillis)
        assertEquals(OrionCreateTimerPreparer.MAX_DURATION_MILLIS, ready(command(duration = OrionCreateTimerPreparer.MAX_DURATION_MILLIS)).durationMillis)
    }
    @Test fun materialChangesDifferWhileReplayAndTraceDoNotChangeCanonicalArguments() {
        val original = command()
        for (c in listOf(original.copy(title = "Água"), original.copy(durationMillis = 60_001), original.copy(timerId = key)))
            assertNotEquals(ready(original).canonicalNormalizedArguments, ready(c).canonicalNormalizedArguments)
        assertEquals(ready(original).canonicalNormalizedArguments,
            ready(original.copy(idempotencyKey = id, correlationId = key)).canonicalNormalizedArguments)
    }
    @Test fun titleNormalizationIsNfcWithEdgeTrimmingOnly() {
        assertEquals(ready(command("é")).canonicalNormalizedArguments, ready(command(" e\u0301 ")).canonicalNormalizedArguments)
        assertNotEquals(ready(command("Timer")).canonicalNormalizedArguments, ready(command("timer")).canonicalNormalizedArguments)
        assertEquals("a  b", ready(command("a  b")).title)
    }
    @Test fun rawAndNormalizedUtf8BudgetsAreEnforcedBeforeAllocationAndAfterNfcExpansion() {
        for (title in listOf("a".repeat(1024), "é".repeat(512), "€".repeat(341)+"a", "😀".repeat(256)))
            assertEquals(title, ready(command(title)).title)
        for (title in listOf("a".repeat(1025), "é".repeat(513), "😀".repeat(257), " ".repeat(1024)+"a", "\u0344".repeat(512)))
            assertEquals(CreateTimerRejection.TITLE_TOO_LARGE, reason(command(title)))
    }
    @Test fun malformedUnicodeControlCharactersAndBlankTitlesFailWithTechnicalReasons() {
        for (title in listOf("\ud800", "x\udc00", "\ud800x")) assertEquals(CreateTimerRejection.INVALID_UNICODE, reason(command(title)))
        for (c in (0..31).map(Int::toChar)+(127..159).map(Int::toChar)+listOf('\u2028','\u2029'))
            assertEquals(CreateTimerRejection.INVALID_TITLE_CHARACTER, reason(command("a${c}b")))
        assertEquals(CreateTimerRejection.BLANK_TITLE, reason(command(" \u00a0")))
    }
    @Test fun invalidCancellationVersionsFailAndNeverAuthorizeExecution() {
        for (v in listOf(-1L,Long.MAX_VALUE)) assertEquals(TimerStatusRejection.INVALID_VERSION,
            (OrionTimerStatusPreparer.prepare(CancelTimer(id,v,key,id)) as TimerStatusPreparation.Rejected).reason)
        val change=(OrionTimerStatusPreparer.prepare(CancelTimer(id,0,key,id)) as TimerStatusPreparation.Ready).change
        assertEquals("timer.cancel",change.skillName)
        assertEquals("""{"timerId":"$id","expectedVersion":0}""",change.canonicalNormalizedArguments)
    }
    @Test fun diagnosticsNeverContainTitleOrCanonicalMaterial() {
        val request=command("sensitive-timer-content")
        val result=OrionCreateTimerPreparer.prepare(request) as CreateTimerPreparation.Ready
        for (value in listOf(request,result,result.timer)) {
            assertFalse(value.toString().contains("sensitive-timer-content"))
            assertFalse(value.toString().contains(result.timer.canonicalNormalizedArguments))
        }
    }
}
