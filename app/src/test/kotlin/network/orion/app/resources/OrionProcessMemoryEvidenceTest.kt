package network.orion.app.resources

import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test

class OrionProcessMemoryEvidenceTest {
    private val mainName = "network.orion"
    private val aiName = "$mainName:ai"
    private val voiceName = "$mainName:voice"
    private val now = MonotonicInstant(BootSessionId(1), 1_000)
    private val main = OrionProcessMemoryReading(mainName, now, 100, true)
    private val ai = OrionProcessMemoryReading(aiName, now, 200, true)
    private val voice = OrionProcessMemoryReading(voiceName, now, 300, true)
    private fun evidence(readings: List<OrionProcessMemoryReading> = listOf(ai, voice)) =
        OrionAppMemoryEvidence(now, true, setOf(mainName, aiName, voiceName), readings)
    private fun totals(evidence: OrionAppMemoryEvidence? = evidence(), main: OrionProcessMemoryReading? = this.main) =
        aggregateProcessMemory(evidence, main, mainName, aiName, now, 100)
    private fun missing(result: OrionMemoryTotals) { assertNull(result.aiBytes); assertNull(result.aggregateBytes) }

    @Test fun `complete native inclusive snapshot sums main ai and all auxiliary processes`() {
        assertEquals(OrionMemoryTotals(200, 600), totals())
        val session = voice.copy(processName = "$mainName:voice_session", totalPssBytes = 400)
        val snapshot = evidence(listOf(ai, voice, session)).let { it.copy(processNames = it.processNames + session.processName) }
        assertEquals(OrionMemoryTotals(200, 1000), totals(snapshot))
    }

    @Test fun `absent telemetry main or ai never becomes a fabricated zero`() {
        missing(totals(null))
        missing(totals(main = null))
        missing(totals(evidence(listOf(voice))))
        missing(totals(evidence(listOf(voice)).copy(processNames = setOf(mainName, voiceName))))
        missing(totals(evidence().copy(complete = false)))
    }

    @Test fun `every process must include native PSS and valid nonzero total`() {
        listOf(ai.copy(nativeIncluded = false), ai.copy(totalPssBytes = null),
            ai.copy(totalPssBytes = -1), ai.copy(totalPssBytes = 0)).forEach { invalid ->
            missing(totals(evidence(listOf(invalid, voice))))
        }
        missing(totals(main = main.copy(nativeIncluded = false)))
        missing(totals(main = main.copy(totalPssBytes = 0)))
    }

    @Test fun `all readings and inventory must have fresh same boot monotonic samples`() {
        listOf(now.copy(elapsedRealtimeMillis = 900), now.copy(elapsedRealtimeMillis = 1001),
            MonotonicInstant(BootSessionId(2), 999), MonotonicInstant(null, 999)).forEach { invalid ->
            missing(totals(evidence().copy(sampledAt = invalid)))
            missing(totals(evidence(listOf(ai.copy(sampledAt = invalid), voice))))
            missing(totals(main = main.copy(sampledAt = invalid)))
        }
        assertEquals(600L, totals(evidence(listOf(ai.copy(sampledAt = now.copy(elapsedRealtimeMillis = 901)), voice))).aggregateBytes)
    }

    @Test fun `inventory must match every unique reading and the exact local main identity`() {
        missing(totals(evidence(listOf(ai, voice, ai))))
        missing(totals(evidence(listOf(main, ai, voice))))
        missing(totals(evidence().copy(processNames = setOf(mainName, aiName))))
        missing(totals(evidence().copy(processNames = setOf(aiName, voiceName))))
        missing(totals(main = main.copy(processName = aiName)))
        val foreign = voice.copy(processName = "other.app:voice")
        missing(totals(evidence(listOf(ai, foreign)).copy(processNames = setOf(mainName, aiName, foreign.processName))))
    }

    @Test fun `summation overflow rejects all totals rather than approving small wrapped memory`() {
        missing(totals(main = main.copy(totalPssBytes = Long.MAX_VALUE)))
        assertEquals(Long.MAX_VALUE, totals(evidence(listOf(ai.copy(totalPssBytes = Long.MAX_VALUE - 400), voice))).aggregateBytes)
    }

    @Test fun `extra process without inventory or native accounting cannot be omitted from aggregate`() {
        val extra = voice.copy(processName = "$mainName:aux", totalPssBytes = 10_000)
        missing(totals(evidence(listOf(ai, voice, extra))))
        missing(totals(evidence().copy(processNames = evidence().processNames + extra.processName)))
    }

    @Test fun `oversized lazy process readings are rejected with bounded iteration`() {
        var reads = 0
        val huge = object : AbstractList<OrionProcessMemoryReading>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): OrionProcessMemoryReading {
                check(++reads <= 17)
                return ai
            }
        }
        missing(totals(evidence(huge)))
        assertEquals(17, reads)
    }

    @Test fun `configuration never accepts a nonpositive freshness window`() {
        assertThrows(IllegalArgumentException::class.java) {
            aggregateProcessMemory(evidence(), main, mainName, aiName, now, 0)
        }
    }
}
