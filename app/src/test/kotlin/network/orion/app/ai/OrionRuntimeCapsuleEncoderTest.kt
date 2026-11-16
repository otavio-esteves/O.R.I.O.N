package network.orion.app.ai

import java.util.Locale
import network.orion.ai.api.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic detached values/reflected private seal only. These tests provide no genuine capture proof. */
class OrionRuntimeCapsuleEncoderTest {
    private val pkg = "network.orion.qualification"
    private fun time(value: Long, boot: Int = 3) = OrionRuntimeEventTime(boot, value)
    private fun summary(count: Int): OrionRuntimeLifecycleImportSummary {
        // Only the summary's count is inspected. No forged event is passed to a production source/import.
        val constructor = OrionLifecycleEventSnapshot::class.java.declaredConstructors.single().apply { isAccessible = true }
        val value = constructor.newInstance(true, true, true, false, null, false, List(count) { null },
            emptySet<OrionLifecycleEventGap>(), 0L, 0L, 0L, true, false, true) as OrionLifecycleEventSnapshot
        return OrionRuntimeLifecycleImportSummary(value)
    }
    private fun context(packageName: String = pkg, pid: Int = 10): OrionRuntimeQualifiedContext =
        OrionRuntimeQualifiedContext::class.java.declaredConstructors.single { it.parameterCount == 4 }.apply { isAccessible = true }
            .newInstance(packageName, "$packageName.test", 10000, pid) as OrionRuntimeQualifiedContext
    private fun seal(context: OrionRuntimeQualifiedContext, scalars: OrionRuntimeExportScalars,
        receipt: OrionRuntimeSessionReceipt): OrionRuntimeExportSnapshot =
        OrionCortexRuntimeCaptureSession::class.java.declaredClasses.single { it.simpleName == "ExportSnapshot" }
            .declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }.newInstance(context, scalars, receipt) as OrionRuntimeExportSnapshot
    private fun metadata(sequence: Long = 0, final: Boolean = true, text: Int? = 0, tool: Int? = 0,
        terminal: OrionInferenceTerminal = OrionInferenceTerminal.SUCCEEDED) = OrionLifecycleChunkMetadata(sequence, final,
            if (final) terminal else null, null, if (text == null || tool == null) null else text + tool, text, tool)
    private fun marker(kind: OrionRuntimeMarkerKind, at: Long, id: String? = null,
        terminal: OrionInferenceTerminal? = null, cleanup: OrionRuntimeCleanupResult? = null) =
        OrionRuntimeOperationMarker(kind, time(at), id?.let(::OrionRuntimeTechnicalId),
            terminal?.let { OrionInferenceStatus(OrionInferenceStage.TERMINAL, it, null) },
            if (kind == OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH) 10 else null, cleanup,
            if (kind == OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL) terminal == OrionInferenceTerminal.SUCCEEDED else null)
    private fun rows(): List<OrionRuntimeMemoryRow> = OrionRuntimeMemoryPhase.entries.mapIndexed { index, phase ->
        val lower = 100L + index * 10; val upper = lower + 10
        val state = listOf(OrionRuntimeServiceState.UNLOADED, OrionRuntimeServiceState.LOADING,
            OrionRuntimeServiceState.LOADED, OrionRuntimeServiceState.INFERENCING, OrionRuntimeServiceState.LOADED,
            OrionRuntimeServiceState.UNLOADING, OrionRuntimeServiceState.UNLOADED)[index]
        OrionRuntimeMemoryRow(OrionRuntimePhaseWindow(phase, time(lower), time(upper)), time(lower), time(lower + 1),
            time(lower), time(lower + 1), 10, 11, 100, 200, 300, 1000, true, true, state, true, true,
            OrionRuntimeMemorySource.SELF_DEBUG_MEMORY_INFO_PSS)
    }
    private class Fixture(val pkg: String, val summary: (Int) -> OrionRuntimeLifecycleImportSummary) {
        var contextTokens: Int? = 32; var threads: Int? = 1; var maxTokens: Int? = 8
        var wireTemperature: Float? = 0.1f; var deadline: Long? = 1100
        var inventory: List<String>? = listOf(pkg, "$pkg:ai")
        var chunks = listOf(OrionRuntimeOrderedChunk(OrionRuntimeEventTime(3, 140),
            OrionLifecycleChunkMetadata(0, true, OrionInferenceTerminal.SUCCEEDED, null, 0, 0, 0)))
        var terminal: OrionRuntimeFirstTerminal? = OrionRuntimeFirstTerminal(OrionRuntimeEventTime(3, 140),
            OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.SUCCEEDED, null), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION)
        var markers: List<OrionRuntimeOperationMarker> = emptyList()
        var memory: List<OrionRuntimeMemoryRow> = emptyList()
        var gaps = setOf(OrionRuntimeCaptureGap.UNWIRED_SOURCE)
        var sessionGaps = emptySet<OrionRuntimeSessionGap>()
        var intact = true; var boot = 3; var pid = 10; var aiPid = 11
        var model = OrionRuntimeModelReference("a".repeat(64), 1)
        var finalized = true; var samplingPresent = true; var unfinished = false
        var samplingGaps = emptySet<OrionRuntimeSamplingGap>()
        var mutateSummary: ((OrionRuntimeLifecycleImportSummary) -> Unit)? = null
        fun receipt(): OrionRuntimeSessionReceipt {
            val lifecycle = summary(chunks.size).also { mutateSummary?.invoke(it) }
            val facts = OrionRuntimeCaptureSnapshot(OrionRuntimeCaptureAssociation(model, pid, aiPid, boot, 1, 1,
                OrionRuntimeTechnicalId("LOGICAL_SECRET")), OrionRuntimeEventTime(3, 100), OrionRuntimeEventTime(3, 170),
                markers, chunks, terminal, false, memory, gaps, false, intact, 0, 0, true, false, lifecycle)
            val sampling = if (samplingPresent) OrionRuntimeMemorySamplingReport(127, 127, 127, memory.size, 7, 7,
                samplingGaps, unfinished, if (samplingGaps.isEmpty()) 0 else 1, 0, true, 10) else null
            return OrionRuntimeSessionReceipt(true, true, true, false, finalized, false, true, true,
                emptyList(), facts, lifecycle, sessionGaps, 0, 0, true, sampling)
        }
        fun scalars() = OrionRuntimeExportScalars(contextTokens, threads, maxTokens, wireTemperature, deadline, inventory)
    }
    private fun fixture(): Fixture = Fixture(pkg, ::summary).apply {
        markers = listOf(marker(OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START, 110, "LOAD_SECRET"),
            marker(OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH, 111, "LOAD_SECRET"),
            marker(OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL, 120, "LOAD_SECRET", OrionInferenceTerminal.SUCCEEDED),
            marker(OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH, 130, "TRANSPORT_SECRET"),
            marker(OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH, 150),
            marker(OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP, 160, cleanup = OrionRuntimeCleanupResult.UNLOADED))
        memory = rows()
    }
    private fun encode(f: Fixture = fixture(), context: OrionRuntimeQualifiedContext = context()) =
        OrionRuntimeCapsuleEncoder.encode(seal(context, f.scalars(), f.receipt()))
    private fun json(value: String): Map<String, Any?> = Reader(value).read() as Map<String, Any?>
    private fun obj(value: Any?) = value as Map<String, Any?>
    private fun array(value: Any?) = value as List<Any?>

    @Test fun `closed source fields preserve exact Float zero bytes nullable errors and factor only proven ID`() {
        val encoded = encode(); val text = checkNotNull(encoded.source); val root = json(text)
        assertEquals(setOf("schemaVersion", "kind", "mainIdentity", "session", "candidate", "load", "inference", "unload", "memory"), root.keys)
        assertEquals(1L, root["schemaVersion"]); assertEquals("ORION_MAIN_RUNTIME_CAPTURE", root["kind"])
        assertEquals(setOf("appPackage", "testPackage", "modelSha256", "modelSizeBytes"), obj(root["mainIdentity"]).keys)
        assertEquals(setOf("bootCount", "mainPid", "aiPid", "startedElapsed", "completedElapsed", "maximumSampleAgeMillis", "processInventory"), obj(root["session"]).keys)
        assertEquals(setOf("contextTokens", "threads", "maxTokens", "temperature"), obj(root["candidate"]).keys)
        assertEquals(0.1f.toDouble(), obj(root["candidate"])["temperature"])
        assertEquals(setOf("requestIdSha256", "modelSha256", "startedElapsed", "completedElapsed", "terminal"), obj(root["load"]).keys)
        val inference = obj(root["inference"])
        assertEquals(setOf("requestIdSha256", "transportRequestIdSha256", "modelSha256", "startedElapsed", "terminalElapsed",
            "deadlineElapsed", "inputBytes", "terminal", "terminalSource", "failureCode", "chunks"), inference.keys)
        val chunk = obj(array(inference["chunks"]).single())
        assertEquals(setOf("sequence", "receivedElapsed", "textDeltaBytes", "toolDeltaBytes", "isFinal", "finishReason", "errorCode"), chunk.keys)
        assertEquals(0L, chunk["textDeltaBytes"]); assertEquals(0L, chunk["toolDeltaBytes"])
        assertEquals(true, chunk["isFinal"]); assertNull(chunk["errorCode"]); assertEquals("CALLBACK", inference["terminalSource"])
        assertEquals(OrionRuntimeTechnicalId("LOGICAL_SECRET").sha256ForManualExport(), inference["requestIdSha256"])
        assertFalse(text.contains("LOGICAL_SECRET")); assertFalse(text.contains("LOAD_SECRET")); assertFalse(text.contains("TRANSPORT_SECRET"))
        assertFalse(text.contains("observedTotalMemoryBytes")); assertFalse(text.contains("totalMemoryBytes"))
        assertTrue(text.all { it.code < 128 }); assertTrue(text.length <= 96 * 1024)
        assertEquals(setOf("phase", "startedElapsed", "completedElapsed", "bootCount", "mainPid", "aiPid", "mainSampledElapsed",
            "aiSampledElapsed", "mainPssBytes", "aiPssBytes", "aggregatePssBytes", "mainNativeIncluded", "aiNativeIncluded", "source",
            "serviceState", "nativeRuntimeLoaded", "nativeValidationCompleted"), obj(array(root["memory"]).first()).keys)
        val status = json(encoded.status)
        assertEquals("V1_SOURCE", status["recordState"]); assertEquals("COMPLETE", status["captureIntegrity"])
        assertNull(status["ownerCallInvoked"]); assertEquals(18, status.size)
        for (flag in listOf("sourceAuthenticated", "physicalVerified", "profileApproved", "productionInferenceApproved",
            "runtimeEvidenceV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized")) assertEquals(false, status[flag])
    }

    @Test fun `each missing mandatory candidate deadline inventory sampler or local context refuses without default`() {
        val controls: List<(Fixture) -> Unit> = listOf({ it.contextTokens = null }, { it.threads = null }, { it.maxTokens = null },
            { it.wireTemperature = null }, { it.deadline = null }, { it.inventory = null }, { it.samplingPresent = false },
            { it.wireTemperature = Float.NaN }, { it.wireTemperature = Float.POSITIVE_INFINITY }, { it.contextTokens = 31 },
            { it.threads = 17 }, { it.maxTokens = 4097 }, { it.wireTemperature = 2.1f })
        controls.forEach { change -> val f = fixture(); change(f); val result = encode(f)
            assertNull(result.source); assertEquals(OrionRuntimeExportFailure.CONFIGURATION_UNAVAILABLE, result.failure) }
        assertNull(OrionRuntimeCapsuleEncoder.encode(null).source)
        assertNull(encode(context = context("network.orion")).source)
    }

    @Test fun `disabled configured extra process unknown boot PID model and changed IDs cannot be repaired`() {
        val controls: List<(Fixture) -> Unit> = listOf({ it.inventory = listOf(pkg, "$pkg:ai", "$pkg:voice_probe") },
            { it.boot = 4 }, { it.pid = 12 }, { it.aiPid = 10 }, { it.model = OrionRuntimeModelReference("0".repeat(64), 1) },
            { it.model = OrionRuntimeModelReference("a".repeat(64), 4L * 1024 * 1024 * 1024 + 1) }, { it.finalized = false },
            { it.markers = it.markers.map { existing -> if (existing.kind == OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH)
                marker(OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH, 111, "WRONG_SECRET") else existing } })
        controls.forEach { change -> val f = fixture(); change(f); assertNull(encode(f).source) }
    }

    @Test fun `failed load lacks genuine wire temperature and no load terminal is fabricated`() {
        val failed = fixture().apply {
            markers = markers.take(2) + marker(OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL, 120, "LOAD_SECRET", OrionInferenceTerminal.FAILED)
            chunks = emptyList(); terminal = null; memory = emptyList(); wireTemperature = null
        }
        assertEquals(OrionRuntimeExportFailure.CONFIGURATION_UNAVAILABLE, encode(failed).failure)
        val missing = fixture().apply { markers = markers.filter { it.kind != OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL } }
        assertEquals(OrionRuntimeExportFailure.LOAD_TERMINAL_UNAVAILABLE, encode(missing).failure)
        val replyOnly = fixture().apply { markers = markers.filter { it.kind != OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH } }
        assertEquals(OrionRuntimeExportFailure.LOAD_TERMINAL_UNAVAILABLE, encode(replyOnly).failure)
    }

    @Test fun `local request start is preserved separately from dispatch and only actual endpoint IDs hash`() {
        val root = json(checkNotNull(encode().source))
        assertEquals(110L, obj(root["load"])["startedElapsed"])
        assertNotEquals(111L, obj(root["load"])["startedElapsed"])
        assertEquals(OrionRuntimeTechnicalId("LOAD_SECRET").sha256ForManualExport(), obj(root["load"])["requestIdSha256"])
        assertEquals(OrionRuntimeTechnicalId("TRANSPORT_SECRET").sha256ForManualExport(), obj(root["inference"])["transportRequestIdSha256"])
    }

    @Test fun `callback timeout before deadline remains CALLBACK while MAIN timeout requires genuine expiry`() {
        val callback = fixture().apply {
            chunks = listOf(OrionRuntimeOrderedChunk(time(140), metadata(terminal = OrionInferenceTerminal.TIMED_OUT)))
            terminal = OrionRuntimeFirstTerminal(time(140), OrionInferenceStatus(OrionInferenceStage.TERMINAL,
                OrionInferenceTerminal.TIMED_OUT, null), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION)
        }
        assertEquals("CALLBACK", obj(json(checkNotNull(encode(callback).source))["inference"])["terminalSource"])
        val main = fixture().apply {
            chunks = emptyList(); terminal = OrionRuntimeFirstTerminal(time(140), OrionInferenceStatus(OrionInferenceStage.TERMINAL,
                OrionInferenceTerminal.TIMED_OUT, null), OrionLifecycleTerminalOrigin.DEADLINE_EXPIRED)
        }
        assertEquals(OrionRuntimeExportFailure.CLOCK_DISCONTINUOUS, encode(main).failure)
        main.deadline = 140
        assertEquals("MAIN_LIFECYCLE", obj(json(checkNotNull(encode(main).source))["inference"])["terminalSource"])
    }

    @Test fun `unknown bytes discontinuous sequence terminal mismatch and source loss refuse successful prefix`() {
        val controls: List<(Fixture) -> Unit> = listOf({ it.intact = false },
            { it.chunks = listOf(OrionRuntimeOrderedChunk(time(140), metadata(text = null, tool = null))) },
            { it.chunks = listOf(OrionRuntimeOrderedChunk(time(140), metadata(sequence = 1))) },
            { it.chunks = listOf(OrionRuntimeOrderedChunk(time(140), metadata(final = false))) },
            { it.chunks = listOf(OrionRuntimeOrderedChunk(time(139), metadata())) },
            { it.gaps = it.gaps + OrionRuntimeCaptureGap.CAPACITY },
            { it.mutateSummary = { summary -> summary.javaClass.getDeclaredField("pendingUpdatesAtLeast").apply { isAccessible = true }.setLong(summary, 1) } })
        controls.forEach { change -> val f = fixture(); change(f); assertNull(encode(f).source) }
    }

    @Test fun `required time boot transport identity and contradictory terminal sources refuse`() {
        val controls: List<(Fixture) -> Unit> = listOf(
            { it.deadline = 130 },
            { it.chunks = listOf(OrionRuntimeOrderedChunk(time(140, 4), metadata())) },
            { it.markers = it.markers.map { existing -> if (existing.kind == OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH)
                marker(OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH, 130, "LOGICAL_SECRET") else existing } },
            { it.markers = it.markers.map { existing -> if (existing.kind == OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START)
                marker(OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START, 99, "LOAD_SECRET") else existing } },
            { it.chunks = emptyList(); it.terminal = OrionRuntimeFirstTerminal(time(140),
                OrionInferenceStatus(OrionInferenceStage.TERMINAL, OrionInferenceTerminal.SUCCEEDED, null), OrionLifecycleTerminalOrigin.CANCELLATION) },
            { it.chunks = listOf(OrionRuntimeOrderedChunk(time(140), metadata(terminal = OrionInferenceTerminal.PROCESS_DIED)))
                it.terminal = OrionRuntimeFirstTerminal(time(140), OrionInferenceStatus(OrionInferenceStage.TERMINAL,
                    OrionInferenceTerminal.PROCESS_DIED, null), OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION) })
        controls.forEach { change -> val f = fixture(); change(f); assertNull(encode(f).source) }
    }

    @Test fun `contradictory retained memory is rejected rather than relabeled omitted or repaired`() {
        val f = fixture()
        val valid = f.memory[2]
        f.memory = f.memory.toMutableList().also { rows -> rows[2] = OrionRuntimeMemoryRow(valid.window,
            valid.started, valid.completed, valid.mainSampled, valid.aiSampled, valid.mainPid, valid.aiPid,
            valid.mainPssBytes, valid.aiPssBytes, valid.aggregatePssBytes, valid.observedTotalMemoryBytes,
            true, true, OrionRuntimeServiceState.LOADED, false, false, valid.source) }
        assertNull(encode(f).source)
        f.memory = rows().reversed(); assertNull(encode(f).source)
        f.memory = rows().map { row -> if (row.window.phase == OrionRuntimeMemoryPhase.AFTER_LOAD)
            OrionRuntimeMemoryRow(row.window, row.started, row.completed, row.mainSampled, row.aiSampled,
                row.mainPid, row.aiPid, row.mainPssBytes, row.aiPssBytes, 301, row.observedTotalMemoryBytes,
                true, true, row.serviceState, true, true, row.source) else row }
        assertNull(encode(f).source)
    }

    @Test fun `memory only missed unfinished or rejected rows preserve an explicitly incomplete valid source`() {
        for (phase in OrionRuntimeMemoryPhase.entries) {
            val f = fixture().apply { memory = memory.filter { it.window.phase != phase }; samplingGaps = setOf(OrionRuntimeSamplingGap.PHASE_MISSED) }
            val result = encode(f); assertNotNull(result.source); assertEquals("INCOMPLETE", json(result.status)["captureIntegrity"])
        }
        val unfinished = fixture().apply { memory = emptyList(); unfinished = true; samplingGaps = setOf(OrionRuntimeSamplingGap.UNFINISHED) }
        assertNotNull(encode(unfinished).source)
        val rejected = fixture().apply { memory = emptyList(); gaps = gaps + OrionRuntimeCaptureGap.MEMORY_INVALID }
        assertNotNull(encode(rejected).source)
    }

    @Test fun `unconfirmed unload stays null and memory that requires its bounds is not silently dropped`() {
        val f = fixture().apply {
            markers = markers.filter { it.kind != OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP }
            memory = memory.filter { it.window.phase.ordinal < 5 }; sessionGaps = setOf(OrionRuntimeSessionGap.UNLOAD_UNCONFIRMED)
        }
        val result = encode(f); assertNotNull(result.source); assertNull(json(checkNotNull(result.source))["unload"])
        assertEquals(OrionRuntimeExportFailure.UNLOAD_UNCONFIRMED, result.failure)
        f.memory = rows(); assertNull(encode(f).source)
    }

    @Test fun `real multibyte original counts render unchanged no unicode content or identifiers rendered`() {
        val f = fixture().apply { chunks = listOf(OrionRuntimeOrderedChunk(time(140), metadata(text = 2, tool = 6))) }
        val chunk = obj(array(obj(json(checkNotNull(encode(f).source))["inference"])["chunks"]).single())
        assertEquals(2L, chunk["textDeltaBytes"]); assertEquals(6L, chunk["toolDeltaBytes"])
        assertFalse(checkNotNull(encode(f).source).contains("工具"))
        val id = OrionRuntimeTechnicalId("技术é")
        assertEquals(64, checkNotNull(id.sha256ForManualExport()).length)
        assertFalse(id.toString().contains("技术é"))
    }

    @Test fun `locale cannot change exact widened Float including signed zero`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val f = fixture().apply { wireTemperature = -0.0f }
            assertTrue(checkNotNull(encode(f).source).contains("\"temperature\":-0.0"))
            assertEquals(0.1f.toDouble(), obj(json(checkNotNull(encode().source))["candidate"])["temperature"])
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `bounded writer accepts exactly 96 KiB and fails before append beyond it with ASCII escapes`() {
        val writer = OrionRuntimeAsciiWriter(96 * 1024)
        writer.raw("x".repeat(96 * 1024)); assertEquals(96 * 1024, writer.finish().length)
        assertThrows(OrionRuntimeAsciiWriter.TooLarge::class.java) { writer.raw("x") }
        assertEquals(96 * 1024, writer.finish().length)
        assertEquals("\"\\u00e9\\n\"".replace("\\n", "\\u000a"), OrionRuntimeAsciiWriter(32).quoted("é\n").finish())
    }

    @Test fun `4097 legitimate scalar chunks can exceed inline cap and refuse without clipping or repair`() {
        val f = fixture().apply {
            chunks = List(4097) { index -> OrionRuntimeOrderedChunk(time(140), metadata(index.toLong(), index == 4096)) }
        }
        val result = encode(f)
        assertNull(result.source); assertEquals(OrionRuntimeExportFailure.EXPORT_TOO_LARGE, result.failure)
        assertEquals("NO_V1_RECORD", json(result.status)["recordState"]); assertEquals(4097, f.chunks.size)
    }

    @Test fun `scalar inventory copy is immutable and encoder does not erase foundation sentinel or grant authority`() {
        val raw = mutableListOf(pkg, "$pkg:ai")
        val scalars = OrionRuntimeExportScalars(32, 1, 8, 0f, 1100, raw)
        raw.clear(); assertEquals(2, scalars.processInventory?.size)
        assertThrows(UnsupportedOperationException::class.java) { (scalars.processInventory as MutableList<String>).clear() }
        val receipt = fixture().receipt(); val facts = checkNotNull(receipt.facts)
        assertFalse(facts.wired); assertTrue(OrionRuntimeCaptureGap.UNWIRED_SOURCE in facts.gaps)
        OrionRuntimeCapsuleEncoder.encode(seal(context(), fixture().scalars(), receipt))
        assertFalse(facts.wired); assertTrue(OrionRuntimeCaptureGap.UNWIRED_SOURCE in facts.gaps)
        assertFalse(receipt.runtimeEvidenceV1Complete)
    }

    /** Independent small JSON reader for syntax/types/duplicate-key tests; production uses no parser. */
    private class Reader(private val text: String) {
        private var at = 0
        fun read(): Any? { val result = value(); check(at == text.length); return result }
        private fun value(): Any? = when (text[at]) {
            '{' -> { at++; val map = linkedMapOf<String, Any?>(); if (text[at] != '}') while (true) {
                val key = string(); check(text[at++] == ':'); check(!map.containsKey(key)); map[key] = value()
                if (text[at] != ',') break; at++
            }; check(text[at++] == '}'); map }
            '[' -> { at++; val values = mutableListOf<Any?>(); if (text[at] != ']') while (true) {
                values.add(value()); if (text[at] != ',') break; at++
            }; check(text[at++] == ']'); values }
            '"' -> string()
            'n' -> { check(text.substring(at, at + 4) == "null"); at += 4; null }
            't' -> { check(text.substring(at, at + 4) == "true"); at += 4; true }
            'f' -> { check(text.substring(at, at + 5) == "false"); at += 5; false }
            else -> { val begin = at; while (at < text.length && text[at] in "-+0123456789.eE") at++
                check(at > begin); val token = text.substring(begin, at)
                if (token.contains('.') || token.contains('e', true)) token.toDouble() else token.toLong() }
        }
        private fun string(): String {
            check(text[at++] == '"'); val out = StringBuilder()
            while (text[at] != '"') { val unit = text[at++]; if (unit != '\\') out.append(unit) else when (val escaped = text[at++]) {
                'u' -> { out.append(text.substring(at, at + 4).toInt(16).toChar()); at += 4 }
                '"', '\\', '/' -> out.append(escaped)
                else -> error("Unexpected escape")
            } }; at++; return out.toString()
        }
    }
}
