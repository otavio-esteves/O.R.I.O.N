package network.orion.app.ai

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.orion.ai.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrionGenerationMetricsWireTest {
    private val id = "orion_request/test/1"
    private fun snapshot() = OrionGenerationMetricsSnapshot(id, 12, 1, 3, OrionInferenceTerminal.SUCCEEDED,
        OrionGenerationNativeState.JNI_RETURNED, OrionGenerationNativeFinish.TOKEN_LIMIT,
        OrionGenerationSlotState.RELEASED, OrionGenerationSnapshotState.FINAL,
        OrionGenerationPartialWork.NONE_OBSERVED, 7, OrionGenerationTimeState.KNOWN_BOOT,
        1, 20, 10, 19, mapOf(OrionGenerationMetric.PROMPT_TOKENS to 3L,
            OrionGenerationMetric.SAMPLED_TOKENS to 1L, OrionGenerationMetric.EOG_TOKENS to 0L,
            OrionGenerationMetric.CALLBACKS_ATTEMPTED to 1L, OrionGenerationMetric.CALLBACKS_RETURNED to 1L,
            OrionGenerationMetric.DECODE_CALLS to 0L, OrionGenerationMetric.DECODE_SUBMITTED to 0L,
            OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 3L,
            OrionGenerationMetric.PREFILL_NANOS to 0L))
    private fun decode(bundle: Bundle) = OrionGenerationMetricsWire.decodeSnapshot(bundle, 0xffff, 1, 32)
    private fun invalid(change: Bundle.() -> Unit) {
        val bundle = OrionGenerationMetricsWire.snapshot(snapshot()).apply(change)
        assertThrows(IllegalArgumentException::class.java) { decode(bundle) }
    }

    @Test fun closedDiscoveryAndDisabledSourceMask() {
        assertEquals(0L, OrionGenerationMetricsWire.capabilityMask(OrionGenerationMetricsWire.capabilities(0)))
        assertEquals(65535L, OrionGenerationMetricsWire.capabilityMask(OrionGenerationMetricsWire.capabilities(65535)))
        assertNull(OrionGenerationMetricsWire.capabilityMask(OrionGenerationMetricsWire.status("UNSUPPORTED")))
        for (change in listOf<Bundle.() -> Unit>(
            { putLong("supportedMetricsMask", 65536) }, { putInt("supportedMetricsMask", 1) },
            { putString("capability", "unexpected") }, { putInt("metricsSchemaVersion", 2) }, { putString("extra", "x") })) {
            assertThrows(IllegalArgumentException::class.java) {
                OrionGenerationMetricsWire.capabilityMask(OrionGenerationMetricsWire.capabilities(1).apply(change))
            }
        }
    }
    @Test fun queryCannotSupplyIdentityAuthorityOrContent() {
        assertEquals(id, OrionGenerationMetricsWire.queryId(OrionGenerationMetricsWire.query(id)))
        for (change in listOf<Bundle.() -> Unit>(
            { putInt("pid", 1) }, { putString("prompt", "x") }, { putInt("bootCount", 1) },
            { putString("requestId", "logical") }, { putString("requestId", " $id") }, { putBoolean("schemaVersion", true) })) {
            assertThrows(IllegalArgumentException::class.java) {
                OrionGenerationMetricsWire.queryId(OrionGenerationMetricsWire.query(id).apply(change))
            }
        }
        assertThrows(OrionGenerationMetricsUnsupportedSchema::class.java) {
            OrionGenerationMetricsWire.queryId(OrionGenerationMetricsWire.query(id).apply { putInt("schemaVersion", 2) })
        }
    }
    @Test fun everyAbsentStatusIsExactlyTwoFields() {
        for (status in listOf("UNSUPPORTED", "INVALID_QUERY", "NOT_FOUND", "RETIRED", "UNAVAILABLE")) {
            val reply = OrionGenerationMetricsWire.status(status)
            assertNull(decode(reply))
            reply.putString("requestId", id)
            assertThrows(IllegalArgumentException::class.java) { decode(reply) }
        }
    }
    @Test fun roundTripPreservesZerosAbsenceTimeAndSequence() {
        val first = checkNotNull(decode(OrionGenerationMetricsWire.snapshot(snapshot())))
        assertEquals(3L, first.snapshotSequence)
        assertEquals(20L, first.capturedElapsedMillis)
        assertEquals(0L, first.metric(OrionGenerationMetric.PREFILL_NANOS))
        assertNull(first.metric(OrionGenerationMetric.DECODE_NANOS))
    }
    @Test fun snapshotHeaderRejectsUnknownMissingAndWrongPrimitiveTypes() {
        invalid { putString("payload", "forbidden") }
        invalid { remove("slotState") }
        invalid { putBoolean("snapshotSequence", true) }
        invalid { putLong("servicePid", 12) }
        invalid { putString("logicalTerminal", "OTHER") }
        invalid { putInt("completedCallStagesMask", 8) }
        invalid { putString("snapshotState", "FINAL"); putString("slotState", "OWNED") }
    }
    @Test fun maskAndNestedFieldsAgreeExactly() {
        invalid { putLong("availableMetricsMask", 1L shl 16) }
        invalid { putLong("availableMetricsMask", 0) }
        invalid { getBundle("metrics")!!.putLong("unlisted", 1) }
        invalid { getBundle("metrics")!!.putInt("sampledTokens", 1) }
        invalid { getBundle("metrics")!!.remove("sampledTokens") }
        assertThrows(IllegalArgumentException::class.java) {
            OrionGenerationMetricsWire.decodeSnapshot(OrionGenerationMetricsWire.snapshot(snapshot()), 0, 1, 32)
        }
    }
    @Test fun durationClockIsExplicitAndNotAnElapsedTimestamp() {
        invalid { getBundle("metrics")!!.remove("clockDomain") }
        invalid { getBundle("metrics")!!.putString("clockDomain", "ELAPSED_REALTIME") }
        invalid { getBundle("metrics")!!.putLong("prefillApiWallNanos", -1) }
        invalid { putString("timeState", "UNAVAILABLE") }
        invalid { putLong("wrapperFinishedElapsed", 9) }
        invalid { putLong("wrapperFinishedElapsed", 21) }
    }
    @Test fun actualRequestLimitAndCountRelationsAreEnforced() {
        invalid { getBundle("metrics")!!.putLong("sampledTokens", 2) }
        invalid { getBundle("metrics")!!.putLong("promptTokens", 32) }
        invalid { getBundle("metrics")!!.putLong("eogTokens", 1) }
        invalid { getBundle("metrics")!!.putLong("callbacksReturnedNormally", 2) }
        invalid { getBundle("metrics")!!.putLong("decodeCalls", 1); getBundle("metrics")!!.putLong("decodeSubmittedTokens", 1) }
    }
    @Test fun totalParcelLimitIncludesNestedPayloadAndDiscovery() {
        invalid { getBundle("metrics")!!.putString("clockDomain", "x".repeat(10000)) }
        assertThrows(IllegalArgumentException::class.java) {
            OrionGenerationMetricsWire.capabilityMask(OrionGenerationMetricsWire.capabilities(0).apply {
                putString("capability", "x".repeat(2000))
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionGenerationMetricsWire.queryId(OrionGenerationMetricsWire.query(id).apply { putString("requestId", "x".repeat(2000)) })
        }
    }
    @Test fun completedStagesRequireOperandsAndTheirNativeControlFlow() {
        fun Bundle.removeMetric(metric: OrionGenerationMetric) {
            getBundle("metrics")!!.remove(metric.key)
            putLong("availableMetricsMask", getLong("availableMetricsMask") and metric.bit.inv())
        }
        invalid { removeMetric(OrionGenerationMetric.PROMPT_TOKENS) }
        invalid { removeMetric(OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS) }
        invalid { putInt("completedCallStagesMask", 2) }
        invalid { putInt("completedCallStagesMask", 5) }
        invalid { getBundle("metrics")!!.putLong("prefillTokensInSuccessfulCalls", 2) }
        invalid { putString("nativeFinish", "CANCELLED") }
        invalid {
            putString("nativeState", "RUNNING"); putString("nativeFinish", "NONE")
            putString("snapshotState", "UPDATING"); putString("slotState", "OWNED")
        }
        val partial = OrionGenerationMetricsWire.snapshot(snapshot()).apply {
            putInt("completedCallStagesMask", 0)
            removeMetric(OrionGenerationMetric.PROMPT_TOKENS)
            removeMetric(OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS)
        }
        val value = checkNotNull(decode(partial))
        assertEquals(0, value.completedCallStagesMask)
        assertNull(value.metric(OrionGenerationMetric.PROMPT_TOKENS))
    }

    @Test fun measuredPrefillCannotHaveTokensWithoutEnteredOrSuccessfulCalls() {
        for (counts in listOf(
            mapOf(OrionGenerationMetric.PREFILL_CALLS to 0L, OrionGenerationMetric.PREFILL_SUBMITTED to 3L),
            mapOf(OrionGenerationMetric.PREFILL_SUCCEEDED to 1L, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 0L),
            mapOf(OrionGenerationMetric.PREFILL_SUCCEEDED to 0L, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 3L),
            mapOf(OrionGenerationMetric.PREFILL_SUCCEEDED to 2L, OrionGenerationMetric.PREFILL_SUCCESSFUL_TOKENS to 1L))) {
            invalid {
                putInt("completedCallStagesMask", 0)
                val metrics = getBundle("metrics")!!
                var mask = getLong("availableMetricsMask")
                for ((metric, value) in counts) { metrics.putLong(metric.key, value); mask = mask or metric.bit }
                putLong("availableMetricsMask", mask)
            }
        }
    }

    @Test fun queryAndSnapshotIdsRequireCanonicalPositiveSequenceButDoNotAuthenticateScope() {
        for (value in listOf("orion_request/x/001", "orion_request/x/0", "orion_request/x/-1",
            "orion_request/x/+1", "orion_request/x/NaN", "orion_request/x/9223372036854775808",
            "orion_request/", "orion_request/x", "orion_request/x/", "orion_request//1",
            "orion_request/ /1", "orion_request/x/y/1", "orion_request/x/1/2",
            " orion_request/x/1", "orion_request/x/1 ", "orion_request/x\n/1",
            "orion_request/" + "x".repeat(128) + "/1")) {
            val query = Bundle().apply { putInt("schemaVersion", 1); putString("requestId", value) }
            assertThrows(IllegalArgumentException::class.java) { OrionGenerationMetricsWire.queryId(query) }
            assertThrows(IllegalArgumentException::class.java) { OrionGenerationMetricsWire.query(value) }
            invalid { putString("requestId", value) }
        }
        for (value in listOf("orion_request/foreign-opaque-scope/1", "orion_request/x/${Long.MAX_VALUE}")) {
            assertEquals(value, OrionGenerationMetricsWire.queryId(OrionGenerationMetricsWire.query(value)))
            val snapshot = OrionGenerationMetricsWire.snapshot(snapshot()).apply { putString("requestId", value) }
            assertEquals(value, checkNotNull(decode(snapshot)).requestId)
        }
    }

}
