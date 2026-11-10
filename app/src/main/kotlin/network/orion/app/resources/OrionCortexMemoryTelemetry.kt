package network.orion.app.resources

import android.os.Bundle
import java.util.Collections
import java.util.concurrent.CancellationException
import network.orion.ai.api.OrionCortexWire
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicClock
import network.orion.core.time.MonotonicInstant

/** Untrusted technical fields, validated independently of the Android Bundle decoder. */
internal data class OrionCortexMemoryReceipt(val schemaVersion: Int, val status: String,
    val processName: String, val pid: Int, val bootCount: Int, val sampledElapsed: Long,
    val totalPssBytes: Long, val nativeIncluded: Boolean)

/**
 * MAIN-owned adapter for a response from the currently connected private Cortex
 * Binder, not arbitrary persisted/caller-provided data. It grants no inference
 * authority. The MAIN lifecycle/manifest owner supplies knownProcessNames and
 * inventoryComplete; a service receipt can never attest application inventory.
 *
 * Only the MAIN+AI inventory is supported here. Any other configured/registered
 * auxiliary process requires additional telemetry, so this adapter fails closed.
 * expectedAiPid may be absent only for a newly authenticated connection; once the
 * session PID is pinned, the caller supplies it to reject superseded receipts.
 */
internal class OrionCortexMemoryTelemetry(private val mainProcessName: String,
    private val clock: MonotonicClock, private val maximumAgeMillis: Long) {
    init {
        require(mainProcessName.isNotBlank() && mainProcessName.length <= 240 &&
            ':' !in mainProcessName && mainProcessName.none(Char::isISOControl))
        require(maximumAgeMillis > 0)
    }

    fun read(receipt: Bundle, expectedAiPid: Int?, knownProcessNames: Set<String>,
        inventoryComplete: Boolean): OrionAppMemoryEvidence? = try {
        OrionCortexWire.requireEnvelope(receipt, RECEIPT_KEYS)
        val parsed = OrionCortexMemoryReceipt(
            OrionCortexWire.int(receipt, "schemaVersion"), OrionCortexWire.text(receipt, "status"),
            OrionCortexWire.text(receipt, "processName", 255), OrionCortexWire.int(receipt, "pid"),
            OrionCortexWire.int(receipt, "bootCount"), OrionCortexWire.long(receipt, "sampledElapsed"),
            OrionCortexWire.long(receipt, "totalPssBytes"), OrionCortexWire.boolean(receipt, "nativeIncluded"))
        validateCortexMemoryReceipt(parsed, mainProcessName, clock.monotonicNow(), maximumAgeMillis,
            expectedAiPid, knownProcessNames, inventoryComplete)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
      catch (_: Exception) { null }

    companion object {
        private val RECEIPT_KEYS = setOf("schemaVersion", "status", "processName", "pid", "bootCount",
            "sampledElapsed", "totalPssBytes", "nativeIncluded", "nativeRuntimeLoaded", "nativeValidationCompleted")
    }
}

/** Pure validation: bounds inventory iteration before any set comparison or copy. */
internal fun validateCortexMemoryReceipt(receipt: OrionCortexMemoryReceipt, mainProcessName: String,
    now: MonotonicInstant, maximumAgeMillis: Long, expectedAiPid: Int?, knownProcessNames: Set<String>,
    inventoryComplete: Boolean): OrionAppMemoryEvidence? {
    require(maximumAgeMillis > 0)
    if (!inventoryComplete || mainProcessName.isBlank() || ':' in mainProcessName ||
        receipt.schemaVersion != 1 || receipt.status != "OK" || receipt.processName != "$mainProcessName:ai" ||
        receipt.pid <= 0 || expectedAiPid != null && (expectedAiPid <= 0 || receipt.pid != expectedAiPid) ||
        receipt.bootCount < 0 || receipt.sampledElapsed < 0 || receipt.totalPssBytes <= 0 || !receipt.nativeIncluded) return null
    val inventory = knownProcessNames.take(3)
    if (inventory.size != 2 || inventory.toSet() != setOf(mainProcessName, receipt.processName)) return null
    val at = MonotonicInstant(BootSessionId(receipt.bootCount), receipt.sampledElapsed)
    val age = now.elapsedSince(at)?.toMillis() ?: return null
    if (age >= maximumAgeMillis) return null
    return OrionAppMemoryEvidence(at, true, Collections.unmodifiableSet(inventory.toSet()),
        Collections.unmodifiableList(listOf(OrionProcessMemoryReading(receipt.processName, at, receipt.totalPssBytes, true))))
}
