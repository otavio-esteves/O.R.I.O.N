package network.orion.app.ai

import network.orion.app.resources.*
import network.orion.core.time.MonotonicInstant

/** Only the original final Owner buffer may supply this manual batch. No evaluation/Clock/RPC/callback here. */
internal object OrionQualificationPolicyObservation {
    const val MAX_BYTES = 8 * 1024
    fun render(batch: OrionCortexResourceFactBatch?, exit: OrionQualificationExitResult? = null, death: OrionQualificationDeathSettlement? = null): String? {
        if (batch == null) return null
        return try {
            if (batch.drainContended) return unavailable(batch, "DRAIN_CONTENDED", exit, death)
            if (batch.facts.size > 64) return unavailable(batch, "CAPTURE_LIMIT", exit, death)
            val out = OrionRuntimeAsciiWriter(MAX_BYTES)
            header(out, batch, "AVAILABLE", null)
            out.raw(",").key("facts").raw("[")
            batch.facts.forEachIndexed { index, fact ->
                if (index != 0) out.raw(",")
                out.raw("{").key("batchIndex").number(index.toLong())
                when (fact) {
                    is OrionCortexResourceFact.Governor -> {
                        val value = fact.decision
                        out.raw(",").key("kind").quoted("GOVERNOR").raw(",").key("inputSample")
                        sample(out, value.sampledAt)
                        out.raw(",").key("block").quoted(value.block?.name).raw(",").key("allowedAsReported").bool(value.allowedAsReported)
                            .raw(",").key("candidateKeepAliveMillis"); number(out, value.plan?.keepAliveMs)
                        out.raw(",").key("candidateModelSha256").quoted(safeModel(value.plan?.model?.sha256))
                    }
                    is OrionCortexResourceFact.Restart -> {
                        val value = fact.decision
                        out.raw(",").key("kind").quoted("RESTART").raw(",").key("inputSample")
                        sample(out, fact.snapshot?.aiExitHistory?.sampledAt)
                        out.raw(",").key("block").quoted(value.block?.name).raw(",").key("eligibleAsReported").bool(value.eligibleAsReported)
                            .raw(",").key("degradedAsReported").bool(value.degradedAsReported)
                            .raw(",").key("preloadAllowedAsReported").bool(value.preloadAllowedAsReported)
                            .raw(",").key("candidateKeepAliveMillis"); number(out, value.candidatePlan?.keepAliveMs)
                        out.raw(",").key("candidateModelSha256").quoted(safeModel(value.candidatePlan?.model?.sha256))
                            .raw(",").key("memoryDeathsAsReported").number(value.memoryDeathsAsReported.toLong())
                            .raw(",").key("excessiveResourceDeathsAsReported").number(value.excessiveResourceDeathsAsReported.toLong())
                    }
                }
                out.raw("}")
            }
            out.raw("],").key("exitObservation"); exitObservation(out, exit)
            out.raw(",").key("deathSettlement"); if (death == null) out.raw("null") else death.write(out)
            out.raw("}").finish()
        } catch (_: OrionRuntimeAsciiWriter.TooLarge) { unavailable(batch, "EXPORT_TOO_LARGE", exit, death) }
          catch (_: Throwable) { unavailable(batch, "EXPORT_UNAVAILABLE", exit, death) }
    }
    private fun safeModel(value: String?): String? {
        require(value == null || value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' } && value.any { it != '0' })
        return value
    }
    private fun number(out: OrionRuntimeAsciiWriter, value: Long?) { if (value == null) out.raw("null") else out.number(value) }
    private fun sample(out: OrionRuntimeAsciiWriter, value: MonotonicInstant?) {
        if (value == null || value.elapsedRealtimeMillis < 0) out.raw("null") else {
            out.raw("{").key("bootCount"); number(out, value.bootSessionId?.bootCount?.toLong())
            out.raw(",").key("elapsedMillis").number(value.elapsedRealtimeMillis).raw("}")
        }
    }
    private fun header(out: OrionRuntimeAsciiWriter, batch: OrionCortexResourceFactBatch, state: String, gap: String?) {
        out.raw("{").key("schemaVersion").number(1).raw(",").key("kind").quoted("ORION_ORIGINAL_RESOURCE_POLICY_FACTS")
            .raw(",").key("state").quoted(state).raw(",").key("gap").quoted(gap)
            .raw(",").key("droppedAtLeast").number(batch.droppedAtLeast.coerceAtLeast(0))
            .raw(",").key("contendedAtLeast").number(batch.contendedAtLeast.coerceAtLeast(0))
            .raw(",").key("recordingFailuresAtLeast").number(batch.recordingFailuresAtLeast.coerceAtLeast(0))
            .raw(",").key("lossAccountingComplete").bool(state == "AVAILABLE" && batch.lossAccountingComplete)
            .raw(",").key("drainContended").bool(batch.drainContended)
    }
    private fun exitObservation(out: OrionRuntimeAsciiWriter, value: OrionQualificationExitResult?) {
        out.raw("{").key("gap").quoted(value?.gap?.name ?: if (value == null) "CONFIGURATION_UNAVAILABLE" else null)
            .raw(",").key("paired").bool(value?.incident != null).raw(",").key("newIncident")
        if (value?.incident == null) out.raw("null") else out.bool(value.incident.newIncident)
        out.raw(",").key("descriptionEvidence").quoted(value?.incident?.record?.descriptionEvidence?.name)
            .raw(",").key("dedicatedReason"); number(out, value?.incident?.record?.dedicatedReason?.toLong())
        out.raw("}")
    }
    private fun unavailable(batch: OrionCortexResourceFactBatch, gap: String, exit: OrionQualificationExitResult?, death: OrionQualificationDeathSettlement?): String {
        val out = OrionRuntimeAsciiWriter(1024)
        header(out, batch, "UNAVAILABLE", gap)
        out.raw(",").key("facts").raw("null").raw(",").key("exitObservation"); exitObservation(out, exit)
        out.raw(",").key("deathSettlement"); if (death == null) out.raw("null") else death.write(out)
        return out.raw("}").finish()
    }
}
