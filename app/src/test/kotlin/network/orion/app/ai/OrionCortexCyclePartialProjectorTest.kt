package network.orion.app.ai

import java.time.Instant
import network.orion.ai.api.*
import network.orion.app.resources.*
import network.orion.core.resources.*
import network.orion.core.time.BootSessionId
import network.orion.core.time.MonotonicInstant
import org.junit.Assert.*
import org.junit.Test

/** Mechanical source receipts only. No Android, source capture, model, qualification or workload. */
class OrionCortexCyclePartialProjectorTest {
    private val projector = OrionCortexCyclePartialProjector()
    private val complete = OrionCortexRunResult.Completed(OrionInferenceStatus(
        OrionInferenceStage.TERMINAL, OrionInferenceTerminal.SUCCEEDED, null))
    private val secret = "SECRET_\"/private/model/Δ💣\\raw-error"
    private val endpoint = OrionCycleEndpoint(Any(), 88776655, 44556677)
    private val at = MonotonicInstant(BootSessionId(998877), 123456789)

    private fun receipt(state: OrionCycleState = OrionCycleState.INCOMPLETE,
        source: OrionCycleSource = OrionCycleSource.TURN_ENTERED,
        result: OrionCortexRunResult? = complete, thrown: OrionCycleThrown? = null,
        actual: Boolean? = true, accepted: Boolean? = true,
        stream: OrionCycleStreamSummary? = null, resources: List<OrionCycleResourceFact> = emptyList(),
        native: OrionGenerationMetricsSnapshot? = null, unload: OrionCycleUnloadFact? = null,
        issues: Set<OrionCycleIssue> = emptySet(), dropped: Long = 0, failures: Long = 0,
        accounting: Boolean = true) = OrionCortexCycleReceipt(state, source, result, thrown, secret,
        actual, accepted, stream, resources, unload, native, issues, dropped, failures, accounting)

    private fun governor(limits: Set<OrionResourceObservationLimitation> = emptySet()): OrionCycleResourceFact {
        val snapshot = OrionResourceSnapshotFact(at, OrionResourceModelFact(secret, 100), 90, 0, true,
            OrionProcessImportance.FOREGROUND, false, OrionMemoryPressure.NONE, 1, 2, 3, 4,
            OrionResourceExitFacts(listOf(network.orion.core.exitinfo.ProcessExitSignal(2, Instant.EPOCH)), false),
            1, 128, 8, null, limits)
        val plan = OrionResourcePlanFact(OrionResourceModelFact(secret, 100), 1, 128, 8, 0,
            10000, 20000, 100, 20, 2)
        val decision = OrionGovernorDecisionFact(true, null, plan, MemoryRisk.LOW, 100, at,
            OrionResourceExitFacts(null, false))
        return OrionCycleResourceFact(OrionCycleCallback.RESOURCE, OrionCyclePhase.INFERENCE,
            endpoint, OrionCortexResourceFact.Governor(snapshot, decision))
    }

    private fun restart(memory: Int = 64, resource: Int = 0) = OrionCycleResourceFact(OrionCycleCallback.RESTART,
        OrionCyclePhase.BEFORE_LOAD, endpoint, OrionCortexResourceFact.Restart(null,
            OrionRestartDecisionFact(false, OrionRestartBlock.FALLBACK_EXHAUSTED, null,
                memory, resource, true, false)))

    private fun metrics(): OrionGenerationMetricsSnapshot {
        val values = mapOf(
            OrionGenerationMetric.PROMPT_TOKENS to 4L, OrionGenerationMetric.SAMPLED_TOKENS to 2L,
            OrionGenerationMetric.EOG_TOKENS to 0L, OrionGenerationMetric.CALLBACKS_ATTEMPTED to 2L,
            OrionGenerationMetric.CALLBACKS_RETURNED to 2L, OrionGenerationMetric.PREFILL_CALLS to 1L,
            OrionGenerationMetric.PREFILL_SUCCEEDED to 1L, OrionGenerationMetric.PREFILL_SUBMITTED to 4L,
            OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 4L, OrionGenerationMetric.DECODE_CALLS to 1L,
            OrionGenerationMetric.DECODE_SUCCEEDED to 1L, OrionGenerationMetric.DECODE_SUBMITTED to 1L,
            OrionGenerationMetric.DECODE_SUCCESSFUL_TOKENS to 1L, OrionGenerationMetric.TOKENIZATION_NANOS to 7L,
            OrionGenerationMetric.PREFILL_NANOS to 8L, OrionGenerationMetric.DECODE_NANOS to 0L)
        return OrionGenerationMetricsSnapshot("orion_request/SECRET_CALLER_ID/1", 44556677, 77665544, 66554433,
            OrionInferenceTerminal.SUCCEEDED, OrionGenerationNativeState.JNI_RETURNED,
            OrionGenerationNativeFinish.TOKEN_LIMIT, OrionGenerationSlotState.RELEASED,
            OrionGenerationSnapshotState.FINAL, OrionGenerationPartialWork.NONE_OBSERVED, 7,
            OrionGenerationTimeState.KNOWN_BOOT, 998877, 123456789, 123456780, 123456785, values)
    }

    private fun projection(source: OrionCortexCycleReceipt) = checkNotNull(projector.project(source))
    private fun fields(source: OrionCortexCycleReceipt) = parse(projection(source).json)
    private fun section(fields: Map<String, Any?>, name: String): Map<String, Any?> = obj(fields[name]).also {
        assertEquals(setOf("presence", "value"), it.keys)
    }
    @Suppress("UNCHECKED_CAST") private fun obj(value: Any?) = value as Map<String, Any?>

    @Test fun `no attempt cannot serialize caller content model identifiers or manufacture optional facts`() {
        val result = OrionCortexRunResult.Unavailable(OrionCortexUnavailable.NO_QUALIFIED_PROFILE)
        val source = receipt(OrionCycleState.NO_ATTEMPT, OrionCycleSource.NO_ATTEMPT, result, actual = false, accepted = false)
        val output = projection(source)
        val fields = parse(output.json)
        assertEquals(setOf("format", "schemaVersion", "qualification", "retainedSnapshot", "sourceAuthenticated",
            "physicalVerified", "profileApproved", "productionInferenceApproved", "runtimeProtocolV1Complete",
            "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized", "identifiersRetained", "timestampsRetained",
            "sourceStateAsReported", "sourceAsReported", "throwClassificationAsReported", "actualTurnEnteredAsReported",
            "inferenceAcceptedAsReported", "outcome", "stream", "resources", "native", "unload", "sourceIssueMask", "observerLoss"), fields.keys)
        assertEquals("ORION_MAIN_CYCLE_PARTIAL", fields["format"]); assertEquals(1L, fields["schemaVersion"])
        assertEquals("NO_ATTEMPT", fields["sourceStateAsReported"])
        assertEquals("NO_QUALIFIED_PROFILE", obj(section(fields, "outcome")["value"])["reason"])
        for (name in listOf("stream", "native", "unload")) {
            assertEquals("ABSENT", section(fields, name)["presence"]); assertNull(section(fields, name)["value"])
        }
        assertEquals(emptyList<Any?>(), section(fields, "resources")["value"])
        assertFalse(output.json.contains("SECRET")); assertFalse(output.json.contains("private/model"))
        assertFalse(output.toString().contains(secret))
        assertSame(result, source.originalResult); assertEquals(secret, source.trustedModelSha256)
    }

    @Test fun `completed does not synthesize native finality and thrown class never contains descriptions`() {
        val rawFailure = AssertionError(secret)
        val fields = fields(receipt(thrown = OrionCycleThrown.OTHER))
        assertEquals("COMPLETED", obj(section(fields, "outcome")["value"])["kind"])
        assertEquals("SUCCEEDED", obj(section(fields, "outcome")["value"])["terminal"])
        assertEquals("ABSENT", section(fields, "native")["presence"])
        assertEquals("OTHER", fields["throwClassificationAsReported"])
        val json = projection(receipt(result = null, thrown = OrionCycleThrown.OTHER)).json
        assertFalse(json.contains("JNI_RETURNED")); assertFalse(json.contains("FINAL"))
        assertFalse(json.contains("AssertionError")); assertFalse(json.contains("SECRET"))
        assertEquals(secret, rawFailure.message) // No Throwable is accepted by the projector API.
    }

    @Test fun `actual native fields retain measured zero and omit every identity and timestamp`() {
        val native = metrics()
        val source = receipt(native = native, resources = listOf(governor()),
            unload = OrionCycleUnloadFact(endpoint, at.deadlineAfter(5000), at, false, true, true, null))
        val output = projection(source)
        val fields = parse(output.json)
        val projected = obj(section(fields, "native")["value"])
        assertEquals("JNI_RETURNED", projected["nativeStateAsReported"])
        assertEquals("FINAL", projected["snapshotStateAsReported"])
        assertEquals("KNOWN_BOOT", projected["timeStateAsReported"])
        assertEquals("STEADY_CLOCK_NS", projected["apiWallClockDomain"])
        assertEquals(65535L, projected["availableMetricsMask"])
        assertEquals(0L, obj(projected["metrics"])["decodeApiWallNanos"])
        for (forbidden in listOf("requestId", "servicePid", "invocationId", "snapshotSequence", "bootCount",
                "capturedElapsedMillis", "wrapperStartedElapsed", "wrapperFinishedElapsed", "sha256", "SECRET",
                "88776655", "44556677", "77665544", "66554433", "998877", "123456789", "123456780", "123456785"))
            assertFalse(forbidden, output.json.contains(forbidden))
        assertSame(native, source.nativeMetrics)
        assertEquals("orion_request/SECRET_CALLER_ID/1", native.requestId)
    }

    @Test fun `unknown source stays only reported unknown and missing time is never filled`() {
        val unknown = OrionGenerationMetricsSnapshot("orion_request/opaque/1", 123, null, 1, null,
            OrionGenerationNativeState.UNKNOWN, OrionGenerationNativeFinish.UNKNOWN, OrionGenerationSlotState.UNKNOWN,
            OrionGenerationSnapshotState.UNKNOWN, OrionGenerationPartialWork.UNKNOWN, 0, OrionGenerationTimeState.UNAVAILABLE)
        val native = obj(section(fields(receipt(native = unknown, actual = null, accepted = null)), "native")["value"])
        assertEquals("UNKNOWN", native["nativeStateAsReported"]); assertEquals("UNKNOWN", native["snapshotStateAsReported"])
        assertEquals("UNAVAILABLE", native["timeStateAsReported"]); assertNull(native["apiWallClockDomain"])
        assertEquals(emptyMap<String, Any?>(), native["metrics"])
        val missing = fields(receipt(actual = null, accepted = null))
        assertNull(missing["actualTurnEnteredAsReported"]); assertNull(missing["inferenceAcceptedAsReported"])
        assertEquals("ABSENT", section(missing, "native")["presence"])
    }

    @Test fun `all approval provenance and completion flags remain false for final native source`() {
        val fields = fields(receipt(native = metrics()))
        assertEquals("NOT_QUALIFIED", fields["qualification"]); assertEquals(true, fields["retainedSnapshot"])
        for (name in listOf("sourceAuthenticated", "physicalVerified", "profileApproved", "productionInferenceApproved",
                "runtimeProtocolV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized",
                "identifiersRetained", "timestampsRetained")) assertEquals(name, false, fields[name])
    }

    @Test fun `lossy stream lower bounds remain independent and observer counts stay global`() {
        val fields = fields(receipt(stream = OrionCycleStreamSummary(0, 0, 7),
            issues = setOf(OrionCycleIssue.LOSS), dropped = 11, failures = 3, accounting = false))
        assertEquals("PRESENT", section(fields, "stream")["presence"])
        assertEquals(7L, obj(section(fields, "stream")["value"])["callbacksReturnedNormallyAtLeast"])
        val loss = obj(section(fields, "observerLoss")["value"])
        assertEquals(11L, loss["observerGlobalDroppedAtLeast"])
        assertEquals(3L, loss["observerGlobalRecordingFailuresAtLeast"])
        assertEquals(false, loss["lossAccountingCompleteAsReported"])
    }

    @Test fun `negative optional counts become unavailable without replacing original outcome`() {
        val fields = fields(receipt(stream = OrionCycleStreamSummary(-1, 0, 0), dropped = -1))
        for (name in listOf("stream", "observerLoss")) {
            assertEquals("UNAVAILABLE", section(fields, name)["presence"]); assertNull(section(fields, name)["value"])
        }
        assertEquals("SUCCEEDED", obj(section(fields, "outcome")["value"])["terminal"])
    }

    @Test fun `resource count bounds are enforced before walking an oversized source`() {
        val item = governor()
        for (size in 0..13) {
            val resource = section(fields(receipt(resources = List(size) { item })), "resources")
            assertEquals(if (size <= 12) "PRESENT" else "UNAVAILABLE", resource["presence"])
            if (size <= 12) assertEquals(size, (resource["value"] as List<*>).size) else assertNull(resource["value"])
        }
        var visited = 0
        val oversized = object : AbstractList<OrionCycleResourceFact>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int) = item.also { check(++visited <= 13) }
        }
        assertEquals("UNAVAILABLE", section(fields(receipt(resources = oversized)), "resources")["presence"])
        assertEquals(13, visited)
    }

    @Test fun `foreign enums cannot masquerade as source issues or snapshot limitations by ordinal`() {
        @Suppress("UNCHECKED_CAST")
        val badIssues = setOf(OrionCycleThrown.CANCELLED) as Set<OrionCycleIssue>
        @Suppress("UNCHECKED_CAST")
        val badLimits = setOf(OrionCycleIssue.PENDING) as Set<OrionResourceObservationLimitation>
        val fields = fields(receipt(issues = badIssues, resources = listOf(governor(badLimits))))
        assertEquals("UNAVAILABLE", section(fields, "sourceIssueMask")["presence"])
        assertEquals("UNAVAILABLE", section(fields, "resources")["presence"])
    }

    @Test fun `a dishonest optional collection size cannot bypass the bounded iterator`() {
        var visited = 0
        val source = object : List<OrionCycleResourceFact> by emptyList() {
            override fun iterator(): Iterator<OrionCycleResourceFact> = object : Iterator<OrionCycleResourceFact> {
                override fun hasNext() = true
                override fun next() = governor().also { check(++visited <= 13) }
            }
        }
        assertEquals(0, source.size)
        assertEquals("UNAVAILABLE", section(fields(receipt(resources = source)), "resources")["presence"])
        assertEquals(13, visited)
    }

    @Test fun `faulty source collections are contained and do not have their descriptions serialized`() {
        val broken = object : AbstractList<OrionCycleResourceFact>() {
            override val size: Int get() = throw AssertionError(secret)
            override fun get(index: Int): OrionCycleResourceFact = error("Unreachable")
        }
        val output = projection(receipt(resources = broken))
        assertEquals("UNAVAILABLE", section(parse(output.json), "resources")["presence"])
        assertFalse(output.json.contains("SECRET")); assertFalse(output.json.contains("AssertionError"))
    }

    @Test fun `out of range restart evidence is unavailable rather than clipped or approved`() {
        for (invalid in listOf(restart(-1), restart(65), restart(64, 1)))
            assertEquals("UNAVAILABLE", section(fields(receipt(resources = listOf(invalid))), "resources")["presence"])
        val valid = section(fields(receipt(resources = listOf(restart(64)))), "resources")
        assertEquals("PRESENT", valid["presence"])
        assertEquals(64L, obj((valid["value"] as List<*>).single())["memoryDeathsAsReported"])
    }

    @Test fun `malformed native optional frame does not become a default zero measurement`() {
        val native = metrics()
        val values = native.javaClass.getDeclaredField("values").apply { isAccessible = true }
        values.set(native, mapOf(OrionGenerationMetric.SAMPLED_TOKENS to -1L))
        val fields = fields(receipt(native = native))
        assertEquals("UNAVAILABLE", section(fields, "native")["presence"])
        assertNull(section(fields, "native")["value"])
        assertEquals("SUCCEEDED", obj(section(fields, "outcome")["value"])["terminal"])
    }

    @Test fun `detached output is deterministic and never mutates or retains source containers`() {
        val resources = mutableListOf(governor()); val issues = mutableSetOf(OrionCycleIssue.LOSS)
        val source = receipt(resources = resources, issues = issues)
        val output = projection(source); val original = output.json
        repeat(20) { assertEquals(original, projection(source).json) }
        assertEquals(1, resources.size); assertEquals(setOf(OrionCycleIssue.LOSS), issues)
        resources.clear(); issues.clear()
        assertSame(original, output.json); assertNotEquals(original, projection(source).json)
        assertEquals(1, (section(parse(output.json), "resources")["value"] as List<*>).size)
    }

    @Test fun `maximum bounded source is closed JSON ASCII within exact UTF8 byte ceiling`() {
        val source = receipt(stream = OrionCycleStreamSummary(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
            resources = List(12) { restart(32, 32) }, native = metrics(),
            unload = OrionCycleUnloadFact(endpoint, at.deadlineAfter(5000), at, false, true, true, null),
            issues = OrionCycleIssue.entries.toSet(), dropped = Long.MAX_VALUE, failures = Long.MAX_VALUE)
        val output = projection(source)
        assertTrue(output.json.all { it.code in 0x20..0x7e })
        assertEquals(output.json.toByteArray(Charsets.UTF_8).size, output.byteCount)
        assertTrue(output.byteCount <= OrionCortexCyclePartialProjector.MAX_BYTES)
        val fields = parse(output.json)
        assertEquals(12, (section(fields, "resources")["value"] as List<*>).size)
        assertEquals(16, obj(obj(section(fields, "native")["value"])["metrics"]).size)
        assertEquals(Long.MAX_VALUE, obj(section(fields, "stream")["value"])["callbackAttemptsAtLeast"])
    }

    @Test fun `absent source stays unavailable rather than a synthetic unknown receipt`() {
        assertNull(projector.project(null))
    }

    /** Independent parser for the closed JSON primitive subset; checks syntax and duplicate keys. */
    private fun parse(json: String): Map<String, Any?> = obj(Json(json).read())
    private class Json(private val input: String) {
        private var at = 0
        fun read(): Any? { val value = value(); check(at == input.length); return value }
        private fun value(): Any? = when (input[at]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> { val start = at; while (at < input.length && input[at].isDigit()) at++
                check(at > start); val token = input.substring(start, at)
                check(token == "0" || !token.startsWith('0')); token.toLong() }
        }
        private fun objectValue(): Map<String, Any?> {
            val result = linkedMapOf<String, Any?>(); at++
            if (take('}')) return result
            do { val key = string(); check(take(':') && !result.containsKey(key)); result[key] = value() } while (take(','))
            check(take('}')); return result
        }
        private fun arrayValue(): List<Any?> {
            val result = mutableListOf<Any?>(); at++
            if (take(']')) return result
            do { result.add(value()) } while (take(','))
            check(take(']')); return result
        }
        private fun string(): String {
            check(take('"')); val start = at
            while (at < input.length && input[at] != '"') { check(input[at].code in 0x20..0x7e && input[at] != '\\'); at++ }
            val result = input.substring(start, at); check(take('"')); return result
        }
        private fun literal(text: String, result: Any?): Any? { check(input.startsWith(text, at)); at += text.length; return result }
        private fun take(character: Char): Boolean = (at < input.length && input[at] == character).also { if (it) at++ }
    }
}
