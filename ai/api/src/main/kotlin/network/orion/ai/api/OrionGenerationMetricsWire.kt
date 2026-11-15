package network.orion.ai.api

import android.os.Bundle
import android.os.Parcel

class OrionGenerationMetricsUnsupportedSchema : IllegalArgumentException("Unsupported metrics schema.")

/** The optional ADR-0003 envelopes are separate from every legacy Cortex envelope. */
object OrionGenerationMetricsWire {
    const val CAPABILITY = "orion_generation_metrics_v1"
    const val MAX_QUERY_BYTES = 1024
    const val MAX_REPLY_BYTES = 8192
    private val base = setOf("schemaVersion", "status")
    private val unavailable = setOf("UNSUPPORTED", "INVALID_QUERY", "NOT_FOUND", "RETIRED", "UNAVAILABLE")
    private val required = base + setOf("requestId", "servicePid", "snapshotSequence", "logicalTerminal", "nativeState",
        "nativeFinish", "slotState", "snapshotState", "partialWorkState", "completedCallStagesMask", "timeState",
        "availableMetricsMask", "metrics")
    private val optional = setOf("invocationId", "bootCount", "capturedElapsedMillis", "wrapperStartedElapsed", "wrapperFinishedElapsed")

    fun status(status: String): Bundle {
        require(status in unavailable)
        return OrionCortexWire.reply(status)
    }

    fun capabilities(supportedMask: Long): Bundle {
        requireMask(supportedMask)
        return OrionCortexWire.reply("OK").apply {
            putString("capability", CAPABILITY); putInt("metricsSchemaVersion", 1)
            putLong("supportedMetricsMask", supportedMask)
            envelope(this, MAX_QUERY_BYTES)
        }
    }

    fun capabilityMask(bundle: Bundle): Long? {
        envelope(bundle, MAX_QUERY_BYTES)
        if (OrionCortexWire.text(bundle, "status") == "UNSUPPORTED") {
            require(bundle.keySet() == base); return null
        }
        require(bundle.keySet() == base + setOf("capability", "metricsSchemaVersion", "supportedMetricsMask"))
        require(OrionCortexWire.text(bundle, "status") == "OK" && OrionCortexWire.text(bundle, "capability") == CAPABILITY)
        require(OrionCortexWire.int(bundle, "metricsSchemaVersion") == 1)
        return OrionCortexWire.long(bundle, "supportedMetricsMask").also(::requireMask)
    }

    fun query(requestId: String): Bundle = Bundle().apply {
        requireGenerationMetricsRequestId(requestId)
        putInt("schemaVersion", 1); putString("requestId", requestId)
        envelope(this, MAX_QUERY_BYTES)
    }

    fun queryId(bundle: Bundle): String {
        envelope(bundle, MAX_QUERY_BYTES, checkSchema = false)
        require(bundle.keySet() == setOf("schemaVersion", "requestId"))
        if (OrionCortexWire.int(bundle, "schemaVersion") != 1) throw OrionGenerationMetricsUnsupportedSchema()
        return OrionCortexWire.text(bundle, "requestId").also(::requireGenerationMetricsRequestId)
    }

    fun snapshot(value: OrionGenerationMetricsSnapshot): Bundle = OrionCortexWire.reply("SNAPSHOT").apply {
        putString("requestId", value.requestId); putInt("servicePid", value.servicePid)
        value.invocationId?.let { putLong("invocationId", it) }
        putLong("snapshotSequence", value.snapshotSequence)
        putString("logicalTerminal", value.logicalTerminal?.name ?: "NONE")
        putString("nativeState", value.nativeState.name); putString("nativeFinish", value.nativeFinish.name)
        putString("slotState", value.slotState.name); putString("snapshotState", value.snapshotState.name)
        putString("partialWorkState", value.partialWorkState.name)
        putInt("completedCallStagesMask", value.completedCallStagesMask); putString("timeState", value.timeState.name)
        value.bootCount?.let { putInt("bootCount", it) }; value.capturedElapsedMillis?.let { putLong("capturedElapsedMillis", it) }
        value.wrapperStartedElapsed?.let { putLong("wrapperStartedElapsed", it) }
        value.wrapperFinishedElapsed?.let { putLong("wrapperFinishedElapsed", it) }
        putLong("availableMetricsMask", value.availableMetricsMask)
        putBundle("metrics", Bundle().apply {
            for (metric in OrionGenerationMetric.entries) value.metric(metric)?.let { putLong(metric.key, it) }
            if (OrionGenerationMetric.entries.any { it.duration && value.metric(it) != null }) putString("clockDomain", "STEADY_CLOCK_NS")
        })
        envelope(this, MAX_REPLY_BYTES)
    }

    @Suppress("DEPRECATION")
    fun decodeSnapshot(bundle: Bundle, supportedMask: Long, maxTokens: Int, contextTokens: Int): OrionGenerationMetricsSnapshot? {
        requireMask(supportedMask)
        envelope(bundle, MAX_REPLY_BYTES)
        val status = OrionCortexWire.text(bundle, "status")
        if (status in unavailable) { require(bundle.keySet() == base); return null }
        require(status == "SNAPSHOT" && bundle.keySet().containsAll(required) && bundle.keySet().all { it in required || it in optional })
        val mask = OrionCortexWire.long(bundle, "availableMetricsMask").also(::requireMask)
        require(mask and supportedMask == mask)
        val metrics = bundle.get("metrics")
        require(metrics is Bundle)
        val selected = OrionGenerationMetric.entries.filter { mask and it.bit != 0L }
        val duration = selected.any { it.duration }
        require(metrics.keySet() == selected.map { it.key }.toSet() + if (duration) setOf("clockDomain") else emptySet())
        if (duration) require(OrionCortexWire.text(metrics, "clockDomain") == "STEADY_CLOCK_NS")
        val terminal = OrionCortexWire.text(bundle, "logicalTerminal")
        fun optionalLong(key: String): Long? = if (bundle.containsKey(key)) OrionCortexWire.long(bundle, key) else null
        val value = OrionGenerationMetricsSnapshot(
            OrionCortexWire.text(bundle, "requestId"), OrionCortexWire.int(bundle, "servicePid"),
            optionalLong("invocationId"), OrionCortexWire.long(bundle, "snapshotSequence"),
            if (terminal == "NONE") null else OrionInferenceTerminal.valueOf(terminal),
            OrionGenerationNativeState.valueOf(OrionCortexWire.text(bundle, "nativeState")),
            OrionGenerationNativeFinish.valueOf(OrionCortexWire.text(bundle, "nativeFinish")),
            OrionGenerationSlotState.valueOf(OrionCortexWire.text(bundle, "slotState")),
            OrionGenerationSnapshotState.valueOf(OrionCortexWire.text(bundle, "snapshotState")),
            OrionGenerationPartialWork.valueOf(OrionCortexWire.text(bundle, "partialWorkState")),
            OrionCortexWire.int(bundle, "completedCallStagesMask"),
            OrionGenerationTimeState.valueOf(OrionCortexWire.text(bundle, "timeState")),
            if (bundle.containsKey("bootCount")) OrionCortexWire.int(bundle, "bootCount") else null,
            optionalLong("capturedElapsedMillis"), optionalLong("wrapperStartedElapsed"), optionalLong("wrapperFinishedElapsed"),
            selected.associateWith { OrionCortexWire.long(metrics, it.key) },
        )
        value.requireAdmittedBounds(maxTokens, contextTokens)
        return value
    }

    private fun requireMask(value: Long) { require(value >= 0 && value and OrionGenerationMetric.ALL_MASK.inv() == 0L) }

    private fun envelope(bundle: Bundle, limit: Int, checkSchema: Boolean = true) {
        val parcel = Parcel.obtain()
        try { parcel.writeBundle(bundle); require(parcel.dataSize() <= limit) } finally { parcel.recycle() }
        if (checkSchema) require(OrionCortexWire.int(bundle, "schemaVersion") == 1)
    }
}
