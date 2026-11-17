package network.orion.app.ai

import network.orion.core.exitinfo.ProcessExitKind
import org.junit.Assert.*
import org.junit.Test

/** Synthetic scalar/kernel/endpoint fixtures only. No claim that Android emitted an incident. */
class OrionQualificationExitCorrelationTest {
    private val pkg = "network.orion.qualification"
    private val client = Any(); private val connection = Any(); private val binder = Any()
    private fun endpoint(epoch: Long = 1) = OrionQualificationEndpointKey(client, connection, binder, epoch)
    private fun pair(at: Long, boot: Int = 3, offset: Long = 1000000) = OrionQualificationTimePair(boot, at, offset + at, offset + at + 1)
    private fun window(start: Long, end: Long, boot: Int = 3, offset: Long = 1000000) =
        OrionQualificationObservationWindow(pair(start, boot, offset), pair(end, boot, offset))
    private fun reading(at: Long, pid: Int = 2, boot: Int = 3) = OrionRuntimeAiMemoryReading(pid, boot, at, 100, true, true, true, OrionRuntimeServiceState.UNLOADED)
    private fun live(start: Long, end: Long, sampled: Long, key: OrionQualificationEndpointKey = endpoint(),
        process: OrionQualificationProcWitness = OrionQualificationProcWitness(2, 10000, 99), original: OrionRuntimeAiMemoryReading = reading(sampled), baseline: OrionQualificationExitHistory = OrionQualificationExitHistory(emptyList(), false, null)) =
        OrionQualificationLiveReceipt(key, original, process, window(start, end), baseline)
    private fun source(maxDelay: Long = 10000) = OrionQualificationExitCorrelation(pkg, 10000, 1,
        OrionQualificationExitConfiguration(10, 1000, maxDelay))
    private fun exit(wall: Long = 1000250, pid: Int = 2, uid: Int = 10000, name: String = "$pkg:ai", reason: Int = 13,
        description: String? = "MemoryLimiter:AnonSwap SECRET_DESCRIPTION", dedicated: Int? = null) = checkNotNull(
        OrionQualificationExitSanitizer.record(pid, uid, uid, name, wall, reason, description, dedicated))
    private val absent = OrionQualificationProcObservation(OrionQualificationProcState.ABSENT)
    private fun paired(maxDelay: Long = 10000): OrionQualificationExitCorrelation = source(maxDelay).also {
        assertEquals(OrionQualificationExitGap.LIVE_UNPAIRED, it.live(live(100, 120, 110)))
        assertNull(it.live(live(200, 220, 210)))
    }
    @Test fun actualFieldsPairOnlyAfterTwoDistinctSameInstanceLiveReceiptsAndDeduplicateRequeries() {
        val source = paired()
        val first = source.history(window(300, 320), listOf(exit()), absent)
        assertNull(first.gap); val incident = checkNotNull(first.incident)
        assertEquals("MEMORY_LIMITER", incident.classification); assertEquals(3, incident.windowBootCount)
        assertEquals(99L, incident.oldProcess.kernelStartTicks); assertTrue(incident.newIncident)
        assertFalse(incident.sourceAuthenticated); assertFalse(incident.memoryLimiterVerified)
        val again = checkNotNull(source.history(window(400, 420), listOf(exit()), absent).incident)
        assertFalse(again.newIncident); assertEquals(incident.firstObservedOrdinal, again.firstObservedOrdinal)
        assertEquals(incident.identitySha256(), again.identitySha256())
    }
    @Test fun independentMillisecondQuantizationDoesNotInvalidateAContinuousClockWindow() {
        val source = source()
        // A real constant offset of 900.5 ms permits these independently quantized readings.
        // Both wall reads bracket the existing monotonic sample; no tolerance or new sample is supplied.
        val observed = OrionQualificationObservationWindow(
            OrionQualificationTimePair(3, 100, 1001, 1001),
            OrionQualificationTimePair(3, 200, 1100, 1100))
        val receipt = OrionQualificationLiveReceipt(endpoint(), reading(150), OrionQualificationProcWitness(2, 10000, 99),
            observed, OrionQualificationExitHistory(emptyList(), false, null))
        assertEquals(OrionQualificationExitGap.LIVE_UNPAIRED, source.live(receipt))
    }
    @Test fun firstKillBeforePairingNeverAttributesEvenAnExactLimiterReason() {
        val source = source(); source.live(live(100, 120, 110))
        val result = source.history(window(300, 320), listOf(exit(reason = 117, description = null, dedicated = 117)), absent)
        assertNull(result.incident); assertEquals(OrionQualificationExitGap.LIVE_UNPAIRED, result.gap)
    }
    @Test fun reusedReceiptIsNotTwoActualHealthObservations() {
        val source = source(); val original = reading(210)
        source.live(live(200, 210, 210, original = original))
        assertEquals(OrionQualificationExitGap.LIVE_UNPAIRED, source.live(live(210, 220, 210, original = original)))
        assertNull(source.history(window(300, 320), listOf(exit()), absent).incident)
    }
    @Test fun changedEndpointUidStartTicksOrHealthBootInvalidatesPairRatherThanRepairingIt() {
        val changes = listOf(live(200, 220, 210, key = endpoint(2)),
            live(200, 220, 210, process = OrionQualificationProcWitness(2, 10001, 99)),
            live(200, 220, 210, process = OrionQualificationProcWitness(2, 10000, 100)),
            live(200, 220, 210, original = reading(210, boot = 4)))
        changes.forEach { changed -> val source = source(); source.live(live(100, 120, 110))
            assertNotNull(source.live(changed)); assertNull(source.history(window(300, 320), listOf(exit()), absent).incident) }
    }
    @Test fun processAliveReusedOrUnreadableIsNotAnAttributedDeath() {
        for (proc in listOf(OrionQualificationProcObservation(OrionQualificationProcState.PRESENT, OrionQualificationProcWitness(2, 10000, 99)),
            OrionQualificationProcObservation(OrionQualificationProcState.PRESENT, OrionQualificationProcWitness(2, 10000, 100)),
            OrionQualificationProcObservation(OrionQualificationProcState.UNAVAILABLE)))
            assertNull(paired().history(window(300, 320), listOf(exit()), proc).incident)
    }
    @Test fun clockRollbackForwardBootChangeUnknownAndStaleWindowRefuseWithoutInventingExitBoot() {
        for (window in listOf(window(300, 320, offset = 999000), window(300, 320, offset = 1001000), window(300, 320, boot = 4))) {
            val source = paired(); assertNull(source.history(window, listOf(exit()), absent).incident)
            assertNull(source.history(window(400, 420), listOf(exit()), absent).incident) // no repair after discontinuity
        }
        val unknown = paired(); unknown.unavailableClock(); assertNull(unknown.history(window(300, 320), listOf(exit()), absent).incident)
        assertEquals(OrionQualificationExitGap.LIVE_STALE, paired(maxDelay = 100).history(window(300, 320), listOf(exit()), absent).gap)
        assertFalse(OrionQualificationExitRecord::class.java.declaredFields.any { it.name.contains("boot", true) })
    }
    @Test fun historyUnavailableTruncatedAndAmbiguousWindowNeverChooseALimiterPrefix() {
        assertEquals(OrionQualificationExitGap.HISTORY_UNAVAILABLE, paired().history(window(300, 320), null, absent).gap)
        assertEquals(OrionQualificationExitGap.HISTORY_TRUNCATED, paired().history(window(300, 320), null, absent, truncated = true).gap)
        assertEquals(OrionQualificationExitGap.HISTORY_TRUNCATED, paired().history(window(300, 320), List(65) { exit() }, absent).gap)
        assertEquals(OrionQualificationExitGap.AMBIGUOUS, paired().history(window(300, 320), listOf(exit(), exit(wall = 1000260, reason = 3)), absent).gap)
        assertNotNull(paired().history(window(300, 320), listOf(exit(), exit()), absent).incident)
        assertEquals(OrionQualificationExitGap.AMBIGUOUS, paired().history(window(300, 320), listOf(exit(), exit(description = null)), absent).gap)
    }
    @Test fun wrongPidUidNameOrHistoricalTimeDoesNotMatchTheLiveInstance() {
        for (record in listOf(exit(pid = 3), exit(uid = 10001), exit(name = pkg), exit(wall = 1000100), exit(wall = 1000400)))
            assertNull(paired().history(window(300, 320), listOf(record), absent).incident)
    }
    @Test fun classificationChangeDoesNotCreateANewIncidentAndSameProcessCannotDieTwice() {
        val source = paired()
        val first = checkNotNull(source.history(window(300, 320), listOf(exit(description = null)), absent).incident)
        assertEquals("UNCLASSIFIED", first.classification)
        val upgraded = checkNotNull(source.history(window(400, 420), listOf(exit()), absent).incident)
        assertFalse(upgraded.newIncident); assertEquals(first.identitySha256(), upgraded.identitySha256())
        assertEquals(OrionQualificationExitGap.AMBIGUOUS, source.history(window(500, 520), listOf(exit(wall = 1000450)), absent).gap)
    }
    @Test fun historyAlreadyPresentWhileAliveNeverBecomesANewIncidentEvenIfItsWallTimeFitsLaterWindow() {
        val source = source()
        val old = exit()
        source.live(live(100, 120, 110, baseline = OrionQualificationExitHistory(listOf(old), false, null)))
        assertNull(source.live(live(200, 220, 210)))
        assertEquals(OrionQualificationExitGap.NO_MATCH, source.history(window(300, 320), listOf(exit(description = null)), absent).gap)
        for (history in listOf(OrionQualificationExitHistory(null, false, null), OrionQualificationExitHistory(null, true, null))) {
            val unavailable = source(); unavailable.live(live(100, 120, 110))
            assertNotNull(unavailable.live(live(200, 220, 210, baseline = history)))
            assertNull(unavailable.history(window(300, 320), listOf(exit()), absent).incident)
        }
    }
    @Test fun baselineCapacityAndSlowLiveCheckpointAreExplicitGapsRatherThanAcceptedPrefixes() {
        val source = source()
        source.live(live(100, 120, 110, baseline = OrionQualificationExitHistory(List(64) { exit(wall = it.toLong()) }, false, null)))
        assertEquals(OrionQualificationExitGap.CAPACITY, source.live(live(200, 220, 210, baseline = OrionQualificationExitHistory(listOf(exit(wall = 999)), false, null))))
        assertNull(source.history(window(300, 320), listOf(exit()), absent).incident)
        assertEquals(OrionQualificationExitGap.LIVE_STALE, source().live(live(100, 1200, 110)))
    }
    @Test fun generalKillsLowMemoryCrashesAndWrongMarkersAreNotMemoryLimiter() {
        for (reason in listOf(2, 3, 4, 5, 9)) assertNotEquals(ProcessExitKind.MEMORY_LIMITER, exit(reason = reason).signal.kind)
        assertNotEquals(ProcessExitKind.MEMORY_LIMITER, exit(description = "memorylimiter:anonswap").signal.kind)
        assertEquals(ProcessExitKind.MEMORY_LIMITER, exit().signal.kind)
        assertEquals(ProcessExitKind.MEMORY_LIMITER, exit(reason = 117, description = null, dedicated = 117).signal.kind)
        val tooLong = exit(description = "MemoryLimiter:AnonSwap" + "x".repeat(4096))
        assertEquals(OrionQualificationDescriptionEvidence.UNAVAILABLE, tooLong.descriptionEvidence)
        assertNotEquals(ProcessExitKind.MEMORY_LIMITER, tooLong.signal.kind) // never clipped into a match
        assertFalse(exit().toString().contains("SECRET_DESCRIPTION"))
        assertTrue(OrionQualificationExitRecord::class.java.declaredFields.none { it.type == String::class.java && it.name != "processName" })
    }
    @Test fun procParsersRetainOnlyActualUidAndRawKernelTicksWithUnknownOnMalformedOrOverflow() {
        val fields = MutableList(20) { "0" }; fields[0] = "S"; fields[19] = "123456"
        assertEquals(123456L, OrionQualificationProcParser.startTicks("2 (name with ) bracket) " + fields.joinToString(" "), 2))
        assertNull(OrionQualificationProcParser.startTicks("3 (name) " + fields.joinToString(" "), 2))
        fields[19] = "18446744073709551615"
        assertNull(OrionQualificationProcParser.startTicks("2 (name) " + fields.joinToString(" "), 2))
        assertEquals(10000, OrionQualificationProcParser.uid("Name:\tai\nUid:\t10000\t10000\t10000\t10000\n"))
        assertNull(OrionQualificationProcParser.uid("Uid:\t10000\t10001\t10000\t10000\n"))
        assertNull(OrionQualificationProcParser.uid("Uid:\t10000\t10000\n"))
    }
}
