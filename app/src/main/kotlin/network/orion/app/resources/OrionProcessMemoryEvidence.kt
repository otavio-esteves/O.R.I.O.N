package network.orion.app.resources

import network.orion.core.time.MonotonicInstant

/** PSS must include managed/native/shared accounting; Java heap usage is insufficient. */
internal data class OrionProcessMemoryReading(val processName: String, val sampledAt: MonotonicInstant,
    val totalPssBytes: Long?, val nativeIncluded: Boolean)

/** Trusted MAIN/IPC owner attests complete inventory, including all configured auxiliary processes. */
internal data class OrionAppMemoryEvidence(val sampledAt: MonotonicInstant, val complete: Boolean,
    val processNames: Set<String>, val readings: List<OrionProcessMemoryReading>)

internal data class OrionMemoryTotals(val aiBytes: Long?, val aggregateBytes: Long?)

/** Pure bounded aggregation; any missing/stale/incomplete accounting fails closed. */
internal fun aggregateProcessMemory(evidence: OrionAppMemoryEvidence?, main: OrionProcessMemoryReading?,
    mainName: String, aiName: String, now: MonotonicInstant, maximumAgeMillis: Long): OrionMemoryTotals {
    require(maximumAgeMillis > 0)
    val missing = OrionMemoryTotals(null, null)
    fun fresh(at: MonotonicInstant): Boolean = now.elapsedSince(at)?.toMillis()?.let { it < maximumAgeMillis } == true
    if (evidence == null || !evidence.complete || !fresh(evidence.sampledAt) || main == null ||
        main.processName != mainName || !fresh(main.sampledAt) || main.totalPssBytes == null ||
        main.totalPssBytes <= 0 || !main.nativeIncluded) return missing
    val inventory = evidence.processNames.take(MAX_PROCESSES + 1)
    if (inventory.size !in 2..MAX_PROCESSES || mainName !in inventory || aiName !in inventory ||
        inventory.any { it != mainName && !it.startsWith("$mainName:") }) return missing
    val auxiliary = evidence.readings.take(MAX_PROCESSES + 1)
    if (auxiliary.size > MAX_PROCESSES || auxiliary.any { it.processName == mainName } ||
        auxiliary.map { it.processName }.distinct().size != auxiliary.size ||
        auxiliary.map { it.processName }.toSet() + mainName != inventory.toSet()) return missing
    var total = main.totalPssBytes
    var ai: Long? = null
    for (reading in auxiliary) {
        val bytes = reading.totalPssBytes
        if (!fresh(reading.sampledAt) || !reading.nativeIncluded || bytes == null || bytes <= 0 ||
            total > Long.MAX_VALUE - bytes) return missing
        total += bytes
        if (reading.processName == aiName) ai = bytes
    }
    return OrionMemoryTotals(ai, total)
}

private const val MAX_PROCESSES = 16
