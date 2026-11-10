package network.orion.app.resources

import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test

class OrionCortexMemoryTelemetryTest {
    private val main = "network.orion.app"
    private val ai = "$main:ai"
    private val now = MonotonicInstant(BootSessionId(7), 1000)
    private val receipt = OrionCortexMemoryReceipt(1, "OK", ai, 123, 7, 999, 20_000, true)
    private fun read(sample: OrionCortexMemoryReceipt = receipt, expectedPid: Int? = 123,
        inventory: Set<String> = setOf(main, ai), complete: Boolean = true, at: MonotonicInstant = now) =
        validateCortexMemoryReceipt(sample, main, at, 100, expectedPid, inventory, complete)

    @Test fun receiptProducesFreshNativeInclusiveAiReadingAndAggregatesWithIndependentMain() {
        val evidence = checkNotNull(read())
        assertEquals(setOf(main, ai), evidence.processNames)
        assertEquals(1, evidence.readings.size)
        assertEquals(ai, evidence.readings.single().processName)
        assertEquals(20_000L, evidence.readings.single().totalPssBytes)
        val totals = aggregateProcessMemory(evidence, OrionProcessMemoryReading(main, now, 10_000, true), main, ai, now, 100)
        assertEquals(OrionMemoryTotals(20_000, 30_000), totals)
        assertNull(aggregateProcessMemory(evidence, null, main, ai, now, 100).aggregateBytes)
    }

    @Test fun incompleteOrExtraInventoryCannotBeSuppliedByReceipt() {
        assertNull(read(complete = false))
        for (names in listOf(emptySet(), setOf(main), setOf(ai), setOf(main, "$main:voice"),
            setOf(main, ai, "$main:voice"), setOf(main, ai, "$main:voice_session"), setOf("foreign.app", ai)))
            assertNull(read(inventory = names))
    }

    @Test fun oversizedInventoryIsRejectedWithBoundedIteration() {
        var visited = 0
        val names = object : AbstractSet<String>() {
            override val size = Int.MAX_VALUE
            override fun iterator(): Iterator<String> = object : Iterator<String> {
                override fun hasNext() = true
                override fun next(): String { check(++visited <= 3); return "$main:extra$visited" }
            }
        }
        assertNull(read(inventory = names))
        assertEquals(3, visited)
    }

    @Test fun statusSchemaProcessAndPidMustMatchCurrentConnection() {
        for (bad in listOf(receipt.copy(schemaVersion = 0), receipt.copy(schemaVersion = 2),
            receipt.copy(status = "UNAVAILABLE"), receipt.copy(processName = main),
            receipt.copy(processName = "foreign.app:ai"), receipt.copy(pid = 0), receipt.copy(pid = -1),
            receipt.copy(pid = 124))) assertNull(read(bad))
        assertNull(read(expectedPid = 0))
        assertNotNull(read(expectedPid = null)) // Authenticated newly bound service can establish its PID.
    }

    @Test fun unknownNativeCoverageAndNonpositivePssFailClosed() {
        assertNull(read(receipt.copy(nativeIncluded = false)))
        assertNull(read(receipt.copy(totalPssBytes = 0)))
        assertNull(read(receipt.copy(totalPssBytes = -1)))
    }

    @Test fun otherMissingBootStaleFutureAndInvalidMonotonicSamplesAreRejected() {
        assertNull(read(receipt.copy(bootCount = -1)))
        assertNull(read(receipt.copy(bootCount = 8)))
        assertNull(read(at = now.copy(bootSessionId = null)))
        assertNull(read(receipt.copy(sampledElapsed = -1)))
        assertNull(read(receipt.copy(sampledElapsed = 1001)))
        assertNull(read(receipt.copy(sampledElapsed = 900)))
        assertNotNull(read(receipt.copy(sampledElapsed = 901)))
    }

    @Test fun validatedInventoryAndReadingsCannotBeMutatedByCaller() {
        val original = mutableSetOf(main, ai)
        val evidence = checkNotNull(read(inventory = original))
        original.clear()
        assertEquals(setOf(main, ai), evidence.processNames)
        try { (evidence.processNames as MutableSet<String>).clear(); fail("Mutable inventory") }
        catch (_: UnsupportedOperationException) { }
        try { (evidence.readings as MutableList<OrionProcessMemoryReading>).clear(); fail("Mutable readings") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun invalidFreshnessConfigurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            validateCortexMemoryReceipt(receipt, main, now, 0, 123, setOf(main, ai), true)
        }
    }
}
