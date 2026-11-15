package network.orion.app.ai

import network.orion.ai.api.OrionGenerationMetric
import network.orion.app.resources.OrionCortexResourceFact
import network.orion.app.resources.OrionResourceObservationLimitation

/** Manual projection only. No source lookup, observation, clock, evaluation, sink or authority. */
internal class OrionCortexCyclePartialProjector {
    class Projection private constructor(val json: String) {
        /** The closed encoding is ASCII, so its character count is its exact UTF-8 byte count. */
        val byteCount: Int get() = json.length
        override fun toString() = "OrionCortexCyclePartial(bytes=$byteCount, qualification=NOT_QUALIFIED)"

        companion object {
            internal fun from(source: OrionCortexCycleReceipt?): Projection? = try {
                source?.let { Projection(encodeCyclePartial(it)) }
            } catch (_: Throwable) { null }
        }
    }

    fun project(source: OrionCortexCycleReceipt?): Projection? = try {
        Projection.from(source)
    } catch (_: Throwable) { null }

    companion object { const val MAX_BYTES = 8192 }
}

private enum class PartialPresence { ABSENT, PRESENT, UNAVAILABLE }
private enum class PartialKind { COMPLETED, UNAVAILABLE, GOVERNOR, RESTART }
private data class PartialSection(val presence: PartialPresence, val json: String?)

private inline fun partialSection(project: () -> String?): PartialSection = try {
    val value = project()
    PartialSection(if (value == null) PartialPresence.ABSENT else PartialPresence.PRESENT, value)
} catch (_: Throwable) { PartialSection(PartialPresence.UNAVAILABLE, null) }

/** Only literals, enum names and primitive values are accepted; no caller string is encoded. */
private class PartialJson {
    private val value = StringBuilder(256)
    fun literal(text: String): PartialJson {
        require(text.length <= OrionCortexCyclePartialProjector.MAX_BYTES - value.length)
        require(text.all { it.code in 0x20..0x7e })
        value.append(text)
        return this
    }
    fun enum(value: Enum<*>?): PartialJson {
        if (value == null) return literal("null")
        val name = value.name
        require(name.isNotEmpty() && name.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' })
        return literal("\"").literal(name).literal("\"")
    }
    fun number(value: Long): PartialJson { require(value >= 0); return literal(value.toString()) }
    fun boolean(value: Boolean?): PartialJson = literal(when (value) { true -> "true"; false -> "false"; null -> "null" })
    fun section(key: String, section: PartialSection): PartialJson = literal(",\"$key\":{\"presence\":")
        .enum(section.presence).literal(",\"value\":").literal(section.json ?: "null").literal("}")
    fun finish(): String = value.toString()
}

private fun encodeCyclePartial(source: OrionCortexCycleReceipt): String {
    val outcome = partialSection {
        when (val result = source.originalResult) {
            null -> null
            is OrionCortexRunResult.Unavailable -> PartialJson().literal("{\"kind\":").enum(PartialKind.UNAVAILABLE)
                .literal(",\"reason\":").enum(result.reason).literal("}").finish()
            is OrionCortexRunResult.Completed -> PartialJson().literal("{\"kind\":").enum(PartialKind.COMPLETED)
                .literal(",\"stage\":").enum(result.status.stage).literal(",\"terminal\":").enum(result.status.terminal)
                .literal(",\"failure\":").enum(result.status.failure).literal("}").finish()
        }
    }
    val stream = partialSection {
        source.stream?.let { PartialJson().literal("{\"acceptedChunkObservationsAtLeast\":")
            .number(it.acceptedChunkObservationsAtLeast).literal(",\"callbackAttemptsAtLeast\":").number(it.callbackAttemptsAtLeast)
            .literal(",\"callbacksReturnedNormallyAtLeast\":").number(it.callbacksReturnedNormallyAtLeast).literal("}").finish() }
    }
    val resources = partialSection {
        val bounded = boundedPartialValues(source.resources, 12)
        val json = PartialJson().literal("[")
        for ((index, fact) in bounded.withIndex()) {
            if (index != 0) json.literal(",")
            val snapshot = fact.fact.snapshot
            val limits = snapshot?.limitations?.let(::partialResourceMask)
            json.literal("{\"callback\":").enum(fact.callback).literal(",\"phase\":").enum(fact.phase)
                .literal(",\"endpointPresent\":").boolean(fact.endpoint != null)
                .literal(",\"snapshotPresent\":").boolean(snapshot != null).literal(",\"snapshotLimitationsMask\":")
            if (limits == null) json.literal("null") else json.number(limits)
            when (val decision = fact.fact) {
                is OrionCortexResourceFact.Governor -> {
                    val data = decision.decision
                    json.literal(",\"kind\":").enum(PartialKind.GOVERNOR)
                        .literal(",\"allowedAsReported\":").boolean(data.allowedAsReported)
                        .literal(",\"block\":").enum(data.block).literal(",\"memoryRisk\":").enum(data.memoryRisk)
                }
                is OrionCortexResourceFact.Restart -> {
                    val data = decision.decision
                    require(data.memoryDeathsAsReported in 0..64 && data.excessiveResourceDeathsAsReported in 0..64)
                    require(data.memoryDeathsAsReported <= 64 - data.excessiveResourceDeathsAsReported)
                    json.literal(",\"kind\":").enum(PartialKind.RESTART)
                        .literal(",\"eligibleAsReported\":").boolean(data.eligibleAsReported)
                        .literal(",\"block\":").enum(data.block)
                        .literal(",\"memoryDeathsAsReported\":").number(data.memoryDeathsAsReported.toLong())
                        .literal(",\"resourceDeathsAsReported\":").number(data.excessiveResourceDeathsAsReported.toLong())
                        .literal(",\"degradedAsReported\":").boolean(data.degradedAsReported)
                        .literal(",\"preloadAllowedAsReported\":").boolean(data.preloadAllowedAsReported)
                }
            }
            json.literal("}")
        }
        json.literal("]").finish()
    }
    val native = partialSection { source.nativeMetrics?.let { snapshot ->
        require(OrionGenerationMetric.entries.size == 16)
        val mask = snapshot.availableMetricsMask
        require(mask >= 0 && mask and OrionGenerationMetric.ALL_MASK.inv() == 0L)
        require(snapshot.completedCallStagesMask and 7.inv() == 0)
        val metrics = PartialJson().literal("{")
        var selected = 0
        var duration = false
        for (metric in OrionGenerationMetric.entries) {
            val count = snapshot.metric(metric)
            require((count != null) == (mask and metric.bit != 0L))
            if (count == null) continue
            require(count >= 0)
            if (!metric.duration) require(count <= when (metric) {
                OrionGenerationMetric.EOG_TOKENS -> 1L
                OrionGenerationMetric.PROMPT_TOKENS, OrionGenerationMetric.PREFILL_CALLS,
                OrionGenerationMetric.PREFILL_SUCCEEDED, OrionGenerationMetric.PREFILL_SUBMITTED,
                OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS -> 32768L
                else -> 4096L
            })
            if (metric == OrionGenerationMetric.PROMPT_TOKENS) require(count > 0)
            if (selected++ != 0) metrics.literal(",")
            metrics.literal("\"").literal(partialMetricKey(metric)).literal("\":").number(count)
            duration = duration || metric.duration
        }
        metrics.literal("}")
        PartialJson().literal("{\"logicalTerminalAsReported\":").enum(snapshot.logicalTerminal)
            .literal(",\"nativeStateAsReported\":").enum(snapshot.nativeState)
            .literal(",\"nativeFinishAsReported\":").enum(snapshot.nativeFinish)
            .literal(",\"slotStateAsReported\":").enum(snapshot.slotState)
            .literal(",\"snapshotStateAsReported\":").enum(snapshot.snapshotState)
            .literal(",\"partialWorkAsReported\":").enum(snapshot.partialWorkState)
            .literal(",\"completedCallStagesMask\":").number(snapshot.completedCallStagesMask.toLong())
            .literal(",\"timeStateAsReported\":").enum(snapshot.timeState)
            .literal(",\"availableMetricsMask\":").number(mask)
            .literal(",\"apiWallClockDomain\":").literal(if (duration) "\"STEADY_CLOCK_NS\"" else "null")
            .literal(",\"metrics\":").literal(metrics.finish()).literal("}").finish()
    } }
    val unload = partialSection { source.unload?.let { PartialJson().literal("{\"endpointPresent\":")
        .boolean(it.endpoint != null).literal(",\"deadlineReferencePresent\":").boolean(it.deadline != null)
        .literal(",\"pollReferencePresent\":").boolean(it.lastPollAt != null)
        .literal(",\"noEndpointAsReported\":").boolean(it.noEndpoint)
        .literal(",\"unloadedReplyAsReported\":").boolean(it.unloadedReply)
        .literal(",\"validUnloadedReplyAsReported\":").boolean(it.validUnloadedReply)
        .literal(",\"failure\":").enum(it.failure).literal("}").finish() } }
    val issues = partialSection { PartialJson().number(partialIssueMask(source.issues)).finish() }
    val loss = partialSection { PartialJson().literal("{\"observerGlobalDroppedAtLeast\":").number(source.droppedAtLeast)
        .literal(",\"observerGlobalRecordingFailuresAtLeast\":").number(source.recordingFailuresAtLeast)
        .literal(",\"lossAccountingCompleteAsReported\":").boolean(source.lossAccountingComplete).literal("}").finish() }

    return PartialJson().literal("{\"format\":\"ORION_MAIN_CYCLE_PARTIAL\",\"schemaVersion\":1,\"qualification\":\"NOT_QUALIFIED\",\"retainedSnapshot\":true," +
        "\"sourceAuthenticated\":false,\"physicalVerified\":false,\"profileApproved\":false,\"productionInferenceApproved\":false," +
        "\"runtimeProtocolV1Complete\":false,\"fullF5GateClosed\":false,\"fullSoakGateClosed\":false,\"f6Authorized\":false," +
        "\"identifiersRetained\":false,\"timestampsRetained\":false,\"sourceStateAsReported\":")
        .enum(source.state).literal(",\"sourceAsReported\":").enum(source.source)
        .literal(",\"throwClassificationAsReported\":").enum(source.thrown)
        .literal(",\"actualTurnEnteredAsReported\":").boolean(source.actualTurnEntered)
        .literal(",\"inferenceAcceptedAsReported\":").boolean(source.inferenceAccepted)
        .section("outcome", outcome).section("stream", stream).section("resources", resources).section("native", native)
        .section("unload", unload).section("sourceIssueMask", issues).section("observerLoss", loss).literal("}").finish()
}

/** Bound iteration itself, preserving zero mask vs unavailable; no assumptions about completeness. */
private fun enumMask(values: Set<Enum<*>>, expected: List<Enum<*>>): Long {
    val count = expected.size
    require(count in 1..62)
    val bounded = boundedPartialValues(values, count)
    var mask = 0L
    for (value in bounded) {
        val index = expected.indexOfFirst { it === value }
        require(index >= 0)
        val bit = 1L shl index
        require(mask and bit == 0L)
        mask = mask or bit
    }
    return mask
}

/** Do not trust Collection.size/toList shortcuts for an optional malformed collection. */
private fun <T> boundedPartialValues(values: Iterable<T>, maximum: Int): List<T> {
    val copy = ArrayList<T>(maximum + 1)
    val iterator = values.iterator()
    while (copy.size <= maximum && iterator.hasNext()) copy.add(iterator.next())
    require(copy.size <= maximum)
    return copy
}

/** Fixed schema1 positions, independent of a future internal source enum reordering. */
private fun partialIssueMask(values: Set<OrionCycleIssue>): Long = enumMask(values, listOf(
    OrionCycleIssue.PENDING, OrionCycleIssue.LOSS, OrionCycleIssue.NO_ACTUAL_TURN,
    OrionCycleIssue.RESOURCE_FACTS_MISSING, OrionCycleIssue.METRICS_UNAVAILABLE,
    OrionCycleIssue.UNLOAD_FACTS_MISSING, OrionCycleIssue.POST_REPLY_TIME_UNAVAILABLE,
    OrionCycleIssue.ENDPOINT_UNAVAILABLE))

private fun partialResourceMask(values: Set<OrionResourceObservationLimitation>): Long = enumMask(values, listOf(
    OrionResourceObservationLimitation.MEMORY_TELEMETRY_UNAVAILABLE,
    OrionResourceObservationLimitation.EXIT_HISTORY_UNAVAILABLE,
    OrionResourceObservationLimitation.EXIT_HISTORY_TRUNCATED,
    OrionResourceObservationLimitation.BATTERY_UNAVAILABLE,
    OrionResourceObservationLimitation.POWER_UNAVAILABLE,
    OrionResourceObservationLimitation.SYSTEM_MEMORY_UNAVAILABLE,
    OrionResourceObservationLimitation.PROCESS_STATE_UNAVAILABLE))

/** This format owns its key set; no source/header string can become a JSON field. */
private fun partialMetricKey(metric: OrionGenerationMetric): String = when (metric) {
    OrionGenerationMetric.PROMPT_TOKENS -> "promptTokens"
    OrionGenerationMetric.SAMPLED_TOKENS -> "sampledTokens"
    OrionGenerationMetric.EOG_TOKENS -> "eogTokens"
    OrionGenerationMetric.CALLBACKS_ATTEMPTED -> "callbacksAttempted"
    OrionGenerationMetric.CALLBACKS_RETURNED -> "callbacksReturnedNormally"
    OrionGenerationMetric.PREFILL_CALLS -> "prefillCalls"
    OrionGenerationMetric.PREFILL_SUCCEEDED -> "prefillSuccessfulCalls"
    OrionGenerationMetric.PREFILL_SUBMITTED -> "prefillSubmittedTokens"
    OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS -> "prefillTokensInSuccessfulCalls"
    OrionGenerationMetric.DECODE_CALLS -> "decodeCalls"
    OrionGenerationMetric.DECODE_SUCCEEDED -> "decodeSuccessfulCalls"
    OrionGenerationMetric.DECODE_SUBMITTED -> "decodeSubmittedTokens"
    OrionGenerationMetric.DECODE_SUCCESSFUL_TOKENS -> "decodeTokensInSuccessfulCalls"
    OrionGenerationMetric.TOKENIZATION_NANOS -> "tokenizationApiWallNanos"
    OrionGenerationMetric.PREFILL_NANOS -> "prefillApiWallNanos"
    OrionGenerationMetric.DECODE_NANOS -> "decodeApiWallNanos"
}
