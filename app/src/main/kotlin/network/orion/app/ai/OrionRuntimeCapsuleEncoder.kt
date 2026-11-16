package network.orion.app.ai

import network.orion.ai.api.OrionInferenceStage
import network.orion.ai.api.OrionInferenceTerminal
import network.orion.ai.api.OrionLifecycleTerminalOrigin

internal enum class OrionRuntimeExportFailure { CONFIGURATION_UNAVAILABLE, SOURCE_UNAVAILABLE, IDENTITY_UNAVAILABLE,
    IDENTITY_CHANGED, CLOCK_UNAVAILABLE, CLOCK_DISCONTINUOUS, LOAD_TERMINAL_UNAVAILABLE, INFERENCE_EVENT_UNAVAILABLE,
    UNLOAD_UNCONFIRMED, PHASE_MISSED, CAPTURE_LOSS, CAPTURE_LIMIT, EXPORT_TOO_LARGE, FRAMING_INVALID, TRANSPORT_UNAVAILABLE }

/** Component output only. A source here is not proof of a safe enclosing framework transaction. */
internal class OrionRuntimeEncodedCapsule internal constructor(val source: String?, val status: String,
    val failure: OrionRuntimeExportFailure?) {
    override fun toString() = "OrionRuntimeEncodedCapsule(sourceAvailable=${source != null}, qualified=false)"
}

/** Bounded ASCII writer: the bound is checked before each append, not after building an unbounded JSON. */
internal class OrionRuntimeAsciiWriter(private val limit: Int) {
    private val text = StringBuilder(minOf(limit, 4096))
    internal class TooLarge : RuntimeException()
    fun raw(value: String): OrionRuntimeAsciiWriter {
        if (value.length > limit - text.length) throw TooLarge()
        check(value.all { it.code <= 127 })
        text.append(value); return this
    }
    fun quoted(value: String?): OrionRuntimeAsciiWriter {
        if (value == null) return raw("null")
        raw("\"")
        for (unit in value) when (unit) {
            '"' -> raw("\\\""); '\\' -> raw("\\\\")
            else -> if (unit.code in 32..126) raw(unit.toString())
                else raw("\\u" + unit.code.toString(16).padStart(4, '0'))
        }
        return raw("\"")
    }
    fun number(value: Long): OrionRuntimeAsciiWriter = raw(value.toString())
    fun bool(value: Boolean): OrionRuntimeAsciiWriter = raw(if (value) "true" else "false")
    fun key(name: String): OrionRuntimeAsciiWriter = quoted(name).raw(":")
    fun finish(): String = text.toString()
}

/** No acquisition, snapshot, Clock, RPC, content, arbitrary objects or HOST identity enters this encoder. */
internal object OrionRuntimeCapsuleEncoder {
    const val MAX_SOURCE_BYTES = 96 * 1024
    const val MAX_STATUS_BYTES = 1024
    private class Rejected(val code: OrionRuntimeExportFailure) : RuntimeException()
    private fun requireFact(value: Boolean, code: OrionRuntimeExportFailure) { if (!value) throw Rejected(code) }
    private val sourceFailure = OrionRuntimeExportFailure.SOURCE_UNAVAILABLE
    private val timeFailure = OrionRuntimeExportFailure.CLOCK_DISCONTINUOUS
    private val identityFailure = OrionRuntimeExportFailure.IDENTITY_UNAVAILABLE
    private val inferenceFailure = OrionRuntimeExportFailure.INFERENCE_EVENT_UNAVAILABLE
    private val loadFailure = OrionRuntimeExportFailure.LOAD_TERMINAL_UNAVAILABLE
    private val configFailure = OrionRuntimeExportFailure.CONFIGURATION_UNAVAILABLE

    private class Projection(val snapshot: OrionRuntimeExportSnapshot, val facts: OrionRuntimeCaptureSnapshot,
        val boot: Int, val start: Long, val end: Long, val age: Long,
        val loadStart: OrionRuntimeOperationMarker, val loadDispatch: OrionRuntimeOperationMarker,
        val loadEnd: OrionRuntimeOperationMarker, val loadHash: String, val logicalHash: String,
        val infer: OrionRuntimeOperationMarker?, val transportHash: String?,
        val unloadStart: OrionRuntimeOperationMarker?, val unloadEnd: OrionRuntimeOperationMarker?, val incomplete: Boolean)

    fun encode(snapshot: OrionRuntimeExportSnapshot?): OrionRuntimeEncodedCapsule {
        if (snapshot == null) return unavailable(sourceFailure)
        return try {
            val projection = validate(snapshot)
            val text = render(projection)
            val limitation = if (projection.unloadStart == null && projection.facts.markers.any {
                it.kind == OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH }) OrionRuntimeExportFailure.UNLOAD_UNCONFIRMED
                else if (projection.incomplete) OrionRuntimeExportFailure.PHASE_MISSED else null
            OrionRuntimeEncodedCapsule(text, status(true, projection.incomplete, limitation, snapshot.receipt), limitation)
        } catch (_: OrionRuntimeAsciiWriter.TooLarge) { unavailable(OrionRuntimeExportFailure.EXPORT_TOO_LARGE, snapshot.receipt) }
          catch (failure: Rejected) { unavailable(failure.code, snapshot.receipt) }
          catch (_: Throwable) { unavailable(sourceFailure, snapshot.receipt) }
    }

    fun unavailable(code: OrionRuntimeExportFailure, receipt: OrionRuntimeSessionReceipt? = null): OrionRuntimeEncodedCapsule =
        OrionRuntimeEncodedCapsule(null, status(false, true, code, receipt), code)

    private fun status(available: Boolean, incomplete: Boolean, failure: OrionRuntimeExportFailure?,
        receipt: OrionRuntimeSessionReceipt?): String {
        val facts = receipt?.facts; val sampling = receipt?.memorySampling; val lifecycle = receipt?.lifecycle
        // Sources can report the same lost fact. Maximum is a conservative lower bound, never a sum of duplicates.
        val dropped = maxOf(receipt?.droppedAtLeast ?: 0, facts?.droppedAtLeast ?: 0,
            sampling?.droppedAtLeast ?: 0, lifecycle?.droppedAtLeast ?: 0)
        val failures = maxOf(receipt?.recordingFailuresAtLeast ?: 0, facts?.recordingFailuresAtLeast ?: 0,
            sampling?.recordingFailuresAtLeast ?: 0, lifecycle?.recordingFailuresAtLeast ?: 0)
        val completeAccounting = dropped == 0L && failures == 0L && receipt?.lossAccountingComplete == true &&
            facts?.lossAccountingComplete == true && sampling?.lossAccountingComplete == true && lifecycle?.lossAccountingComplete == true
        val out = OrionRuntimeAsciiWriter(MAX_STATUS_BYTES)
        out.raw("{").key("schemaVersion").number(1).raw(",").key("kind").quoted("ORION_RUNTIME_CAPTURE_STATUS")
            .raw(",").key("qualification").quoted("NOT_QUALIFIED").raw(",").key("recordState").quoted(if (available) "V1_SOURCE" else "NO_V1_RECORD")
            .raw(",").key("captureIntegrity").quoted(if (!available) "UNAVAILABLE" else if (incomplete) "INCOMPLETE" else "COMPLETE")
            .raw(",").key("failureCode").quoted(failure?.name).raw(",").key("ownerCallInvoked").raw("null")
            .raw(",").key("droppedAtLeast").number(dropped.coerceAtLeast(0)).raw(",").key("recordingFailuresAtLeast").number(failures.coerceAtLeast(0))
            .raw(",").key("lossAccountingComplete").bool(completeAccounting)
        for (key in listOf("sourceAuthenticated", "physicalVerified", "profileApproved", "productionInferenceApproved",
            "runtimeEvidenceV1Complete", "fullF5GateClosed", "fullSoakGateClosed", "f6Authorized")) out.raw(",").key(key).bool(false)
        return out.raw("}").finish()
    }

    private fun hash(value: String?): String {
        requireFact(value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' } && value.any { it != '0' }, identityFailure)
        return checkNotNull(value)
    }
    private fun moment(value: OrionRuntimeEventTime?, boot: Int, lower: Long, upper: Long): Long {
        requireFact(value != null && value.bootCount == boot && value.elapsedMillis != null &&
            value.elapsedMillis in lower..upper, timeFailure)
        return checkNotNull(value?.elapsedMillis)
    }
    private fun validate(snapshot: OrionRuntimeExportSnapshot): Projection {
        val receipt = snapshot.receipt
        val facts = receipt.facts ?: throw Rejected(sourceFailure)
        val association = facts.association ?: throw Rejected(identityFailure)
        val context = snapshot.context; val scalars = snapshot.scalars
        requireFact(context.appPackage == "network.orion.qualification" && context.testPackage == context.appPackage + ".test" &&
            context.mainUid > 0 && context.mainPid > 0 && association.mainPid == context.mainPid &&
            association.aiPid != null && association.aiPid > 0 && association.aiPid != association.mainPid &&
            association.connectionEpoch != null && association.connectionEpoch >= 0 && association.turnGeneration != null && association.turnGeneration > 0,
            identityFailure)
        hash(association.model.sha256)
        requireFact(association.model.sizeBytes in 1..4L * 1024 * 1024 * 1024, identityFailure)
        requireFact(receipt.identityKnown && !receipt.invalidated && !facts.invalidated, OrionRuntimeExportFailure.IDENTITY_CHANGED)
        requireFact(receipt.originalOwnerEntered == true && receipt.actualTurnEntered == true && (receipt.returned xor receipt.threw) &&
            receipt.pendingUnassociatedMarkers.isEmpty() && !facts.snapshotContended, sourceFailure)
        val sampling = receipt.memorySampling ?: throw Rejected(configFailure)
        val lifecycle = receipt.lifecycle ?: throw Rejected(sourceFailure)
        requireFact(facts.lifecycleImport === lifecycle && lifecycle.claimed && lifecycle.active && !lifecycle.invalidated &&
            !lifecycle.snapshotContended && !lifecycle.firstTerminalUnavailable && lifecycle.pendingUpdatesAtLeast == 0L &&
            lifecycle.gaps.isEmpty() && lifecycle.chunkSequenceIntact && lifecycle.droppedAtLeast == 0L &&
            lifecycle.recordingFailuresAtLeast == 0L && lifecycle.lossAccountingComplete, OrionRuntimeExportFailure.CAPTURE_LOSS)
        requireFact(facts.chunkSequenceIntact && !facts.firstTerminalUnavailable && facts.chunks.size <= 4097 &&
            facts.markers.size <= 32 && facts.memory.size <= 128 && lifecycle.orderedEventCount == facts.chunks.size,
            OrionRuntimeExportFailure.CAPTURE_LOSS)
        // The foundation's UNWIRED_SOURCE stays intact. The private session seal supplies composition proof.
        requireFact(facts.gaps.all { it == OrionRuntimeCaptureGap.UNWIRED_SOURCE || it == OrionRuntimeCaptureGap.MEMORY_INVALID } &&
            receipt.gaps.all { it == OrionRuntimeSessionGap.UNLOAD_UNCONFIRMED }, OrionRuntimeExportFailure.CAPTURE_LOSS)
        requireFact(facts.recordingFailuresAtLeast == 0L && receipt.recordingFailuresAtLeast == 0L &&
            facts.lossAccountingComplete && receipt.lossAccountingComplete, OrionRuntimeExportFailure.CAPTURE_LOSS)
        requireFact(scalars.contextTokens != null && scalars.contextTokens in 32..32768 && scalars.threads != null && scalars.threads in 1..16 &&
            scalars.maxTokens != null && scalars.maxTokens in 1..4096 && scalars.wireTemperature != null &&
            scalars.wireTemperature.isFinite() && scalars.wireTemperature in 0f..2f && scalars.deadlineElapsed != null &&
            scalars.processInventory?.size == 2 && scalars.processInventory?.toSet() == setOf(context.appPackage, context.appPackage + ":ai") &&
            sampling.maximumSampleAgeMillis in 1..1000, configFailure)
        val boot = association.bootCount ?: throw Rejected(identityFailure)
        requireFact(boot >= 0, identityFailure)
        val start = moment(facts.started, boot, 0, Long.MAX_VALUE)
        val end = moment(facts.completed, boot, start, Long.MAX_VALUE)
        requireFact(sampling.retainedRows == facts.memory.size && sampling.attemptedMask and 127 == sampling.attemptedMask &&
            sampling.startedMask and sampling.attemptedMask == sampling.startedMask &&
            sampling.completedMask and sampling.startedMask == sampling.completedMask &&
            facts.memory.all { sampling.completedMask and (1 shl it.window.phase.ordinal) != 0 }, sourceFailure)
        fun one(kind: OrionRuntimeMarkerKind, required: Boolean): OrionRuntimeOperationMarker? {
            val markers = facts.markers.filter { it.kind == kind }
            requireFact(markers.size <= 1 && (!required || markers.size == 1), if (kind.name.startsWith("LOAD")) loadFailure else inferenceFailure)
            return markers.singleOrNull()
        }
        val loadStart = checkNotNull(one(OrionRuntimeMarkerKind.LOAD_LOCAL_REQUEST_START, true))
        val loadDispatch = checkNotNull(one(OrionRuntimeMarkerKind.LOAD_TRUE_DISPATCH, true))
        val loadEnd = checkNotNull(one(OrionRuntimeMarkerKind.LOAD_TRUE_TERMINAL, true))
        val loadStatus = loadEnd.status ?: throw Rejected(loadFailure)
        requireFact(receipt.loadDispatchObserved && receipt.loadReplyAccepted == true && loadStart.sameIssuedId(loadDispatch) &&
            loadEnd.sameIssuedId(loadDispatch) && loadStatus.stage == OrionInferenceStage.TERMINAL && loadStatus.terminal != null &&
            (loadStatus.terminal != OrionInferenceTerminal.SUCCEEDED || loadEnd.loadedModelConfirmed == true), loadFailure)
        val localStart = moment(loadStart.at, boot, start, end)
        val dispatch = moment(loadDispatch.at, boot, localStart, end)
        val loadedAt = moment(loadEnd.at, boot, dispatch, end)
        val logicalHash = hash(association.logicalIdSha256ForManualExport())
        val loadHash = hash(loadStart.issuedIdSha256ForManualExport())
        requireFact(logicalHash != loadHash, identityFailure)
        val infer = one(OrionRuntimeMarkerKind.INFERENCE_TRUE_DISPATCH, false)
        val transportHash = infer?.let { hash(it.issuedIdSha256ForManualExport()) }
        if (infer != null) {
            requireFact(loadStatus.terminal == OrionInferenceTerminal.SUCCEEDED && transportHash != logicalHash && transportHash != loadHash, inferenceFailure)
            val at = moment(infer.at, boot, loadedAt, end)
            val deadline = checkNotNull(scalars.deadlineElapsed)
            requireFact(at < deadline && deadline - at <= 120000 && infer.inputBytes != null && infer.inputBytes in 1..1024L * 1024, inferenceFailure)
            validateInference(facts, boot, at, end, deadline)
        } else requireFact(facts.chunks.isEmpty(), inferenceFailure)
        val unloadStart = one(OrionRuntimeMarkerKind.UNLOAD_TRUE_DISPATCH, false)
        val unloadEnd = one(OrionRuntimeMarkerKind.UNLOAD_TRUE_CLEANUP, false)
        requireFact(unloadEnd == null || unloadStart != null, sourceFailure)
        val after = if (infer == null) loadedAt else moment(facts.firstTerminal?.at, boot, loadedAt, end)
        unloadStart?.let { moment(it.at, boot, after, end) }
        unloadEnd?.let {
            moment(it.at, boot, checkNotNull(unloadStart?.at?.elapsedMillis), end)
            requireFact(it.cleanup != null, sourceFailure)
        }
        val died = if (infer != null) facts.firstTerminal?.status?.terminal == OrionInferenceTerminal.PROCESS_DIED
            else loadStatus.terminal == OrionInferenceTerminal.PROCESS_DIED
        requireFact(!died || unloadStart == null, sourceFailure)
        val completeUnloadStart = if (unloadEnd != null) unloadStart else null
        validateMemory(facts, boot, start, end, sampling.maximumSampleAgeMillis, localStart, loadedAt,
            loadStatus.terminal == OrionInferenceTerminal.SUCCEEDED, infer, completeUnloadStart, unloadEnd, died, after)
        val incomplete = infer == null || unloadEnd?.cleanup != OrionRuntimeCleanupResult.UNLOADED ||
            facts.memory.map { it.window.phase }.toSet().size != 7 || sampling.unfinished || sampling.gaps.isNotEmpty() ||
            sampling.droppedAtLeast != 0L || sampling.recordingFailuresAtLeast != 0L || !sampling.lossAccountingComplete ||
            facts.gaps.any { it != OrionRuntimeCaptureGap.UNWIRED_SOURCE } || receipt.gaps.isNotEmpty()
        return Projection(snapshot, facts, boot, start, end, sampling.maximumSampleAgeMillis, loadStart, loadDispatch,
            loadEnd, loadHash, logicalHash, infer, transportHash, completeUnloadStart, unloadEnd, incomplete)
    }

    private fun validateInference(facts: OrionRuntimeCaptureSnapshot, boot: Int, start: Long, end: Long, deadline: Long) {
        val terminal = facts.firstTerminal ?: throw Rejected(inferenceFailure)
        requireFact(terminal.status.stage == OrionInferenceStage.TERMINAL && terminal.status.terminal != null &&
            (terminal.status.failure == null || terminal.status.terminal == OrionInferenceTerminal.FAILED), inferenceFailure)
        val at = moment(terminal.at, boot, start, end)
        val callback = terminal.origin == OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION
        requireFact(if (!callback && terminal.status.terminal == OrionInferenceTerminal.TIMED_OUT) at >= deadline else at < deadline, timeFailure)
        var last = start; var finals = 0
        for ((index, chunk) in facts.chunks.withIndex()) {
            val metadata = chunk.metadata
            last = moment(chunk.at, boot, last, at)
            val text = metadata.validatedTextDeltaUtf8Bytes; val tool = metadata.validatedToolDeltaUtf8Bytes
            requireFact(last < deadline && metadata.sequence == index.toLong() && text != null && tool != null && text >= 0 && tool >= 0 &&
                text.toLong() + tool <= 65536 && text.toLong() + tool == metadata.validatedTotalContentUtf8Bytes?.toLong(), inferenceFailure)
            if (metadata.isFinal) {
                finals++
                requireFact(index == facts.chunks.lastIndex && metadata.finishReason == terminal.status.terminal &&
                    metadata.failure == terminal.status.failure, inferenceFailure)
            } else requireFact(metadata.finishReason == null && metadata.failure == null, inferenceFailure)
        }
        requireFact(if (callback) finals == 1 && last == at && terminal.status.terminal != OrionInferenceTerminal.PROCESS_DIED
            else finals == 0 && terminal.status.terminal != OrionInferenceTerminal.SUCCEEDED, inferenceFailure)
    }

    private fun validateMemory(facts: OrionRuntimeCaptureSnapshot, boot: Int, start: Long, end: Long, age: Long,
        loadStart: Long, loadEnd: Long, loaded: Boolean, infer: OrionRuntimeOperationMarker?,
        unload: OrionRuntimeOperationMarker?, unloaded: OrionRuntimeOperationMarker?, died: Boolean, deathAt: Long) {
        var previous = start; var phase = -1; var runtimeSeen = false
        val aiPid = facts.association?.aiPid; val mainPid = facts.association?.mainPid
        for (row in facts.memory) {
            val begin = moment(row.started, boot, previous, end)
            val completed = moment(row.completed, boot, begin, end)
            requireFact(row.window.phase.ordinal >= phase && row.mainPid == mainPid && row.aiPid == aiPid &&
                moment(row.mainSampled, boot, begin, completed) == begin &&
                moment(row.aiSampled, boot, begin, completed) >= begin && completed - begin < age, sourceFailure)
            val main = row.mainPssBytes; val ai = row.aiPssBytes; val total = row.observedTotalMemoryBytes
            requireFact(main != null && ai != null && main > 0 && ai > 0 && main <= Long.MAX_VALUE - ai &&
                row.aggregatePssBytes == main + ai && total != null && main + ai <= total && row.mainNativeIncluded == true &&
                row.aiNativeIncluded == true && row.nativeRuntimeLoaded != null && row.nativeValidationCompleted != null &&
                (!row.nativeValidationCompleted || row.nativeRuntimeLoaded) && (!runtimeSeen || row.nativeRuntimeLoaded), sourceFailure)
            runtimeSeen = runtimeSeen || row.nativeRuntimeLoaded == true
            requireFact(!died || completed <= deathAt, sourceFailure)
            var lower = start; var upper = end
            val states = when (row.window.phase) {
                OrionRuntimeMemoryPhase.BEFORE_LOAD -> { upper = loadStart; setOf(OrionRuntimeServiceState.UNLOADED) }
                OrionRuntimeMemoryPhase.DURING_LOAD -> { lower = loadStart; upper = loadEnd; setOf(OrionRuntimeServiceState.LOADING, OrionRuntimeServiceState.LOADED) }
                OrionRuntimeMemoryPhase.AFTER_LOAD -> { requireFact(loaded, sourceFailure); lower = loadEnd
                    upper = infer?.at?.elapsedMillis ?: unload?.at?.elapsedMillis ?: end; setOf(OrionRuntimeServiceState.LOADED) }
                OrionRuntimeMemoryPhase.DURING_INFERENCE -> { requireFact(infer != null, sourceFailure)
                    lower = checkNotNull(infer?.at?.elapsedMillis); upper = checkNotNull(facts.firstTerminal?.at?.elapsedMillis)
                    setOf(OrionRuntimeServiceState.INFERENCING, OrionRuntimeServiceState.LOADED) }
                OrionRuntimeMemoryPhase.AFTER_TERMINAL -> { requireFact(infer != null && !died, sourceFailure)
                    lower = checkNotNull(facts.firstTerminal?.at?.elapsedMillis); upper = unload?.at?.elapsedMillis ?: end; setOf(OrionRuntimeServiceState.LOADED) }
                OrionRuntimeMemoryPhase.DURING_UNLOAD -> { requireFact(unload != null && unloaded != null, sourceFailure)
                    lower = checkNotNull(unload?.at?.elapsedMillis); upper = checkNotNull(unloaded?.at?.elapsedMillis)
                    setOf(OrionRuntimeServiceState.UNLOADING, OrionRuntimeServiceState.UNLOADED) }
                OrionRuntimeMemoryPhase.AFTER_UNLOAD -> { requireFact(unloaded?.cleanup == OrionRuntimeCleanupResult.UNLOADED, sourceFailure)
                    lower = checkNotNull(unloaded?.at?.elapsedMillis); setOf(OrionRuntimeServiceState.UNLOADED) }
            }
            requireFact(lower <= begin && completed <= upper && row.serviceState in states &&
                moment(row.window.started, boot, lower, begin) <= begin && moment(row.window.completed, boot, completed, upper) >= completed &&
                (!(loaded && row.window.phase.ordinal >= 2 || row.serviceState == OrionRuntimeServiceState.LOADED ||
                    row.serviceState == OrionRuntimeServiceState.INFERENCING) || row.nativeRuntimeLoaded == true && row.nativeValidationCompleted == true), sourceFailure)
            previous = completed; phase = row.window.phase.ordinal
        }
    }

    private fun render(value: Projection): String {
        val out = OrionRuntimeAsciiWriter(MAX_SOURCE_BYTES)
        val facts = value.facts; val snapshot = value.snapshot; val scalars = snapshot.scalars
        val association = checkNotNull(facts.association)
        val model = association.model
        out.raw("{").key("schemaVersion").number(1).raw(",").key("kind").quoted("ORION_MAIN_RUNTIME_CAPTURE")
            .raw(",").key("mainIdentity").raw("{").key("appPackage").quoted(snapshot.context.appPackage)
            .raw(",").key("testPackage").quoted(snapshot.context.testPackage).raw(",").key("modelSha256").quoted(model.sha256)
            .raw(",").key("modelSizeBytes").number(model.sizeBytes).raw("},").key("session").raw("{")
            .key("bootCount").number(value.boot.toLong()).raw(",").key("mainPid").number(snapshot.context.mainPid.toLong())
            .raw(",").key("aiPid").number(checkNotNull(association.aiPid).toLong()).raw(",").key("startedElapsed").number(value.start)
            .raw(",").key("completedElapsed").number(value.end).raw(",").key("maximumSampleAgeMillis").number(value.age)
            .raw(",").key("processInventory").raw("[")
        // Canonical order of the two actual copied names; no inventory filtering or reconstruction.
        checkNotNull(scalars.processInventory).sorted().forEachIndexed { index, name -> if (index != 0) out.raw(","); out.quoted(name) }
        out.raw("]},").key("candidate").raw("{").key("contextTokens").number(checkNotNull(scalars.contextTokens).toLong())
            .raw(",").key("threads").number(checkNotNull(scalars.threads).toLong()).raw(",").key("maxTokens").number(checkNotNull(scalars.maxTokens).toLong())
            .raw(",").key("temperature").raw(checkNotNull(scalars.wireTemperature).toDouble().toString())
            .raw("},").key("load").raw("{").key("requestIdSha256").quoted(value.loadHash).raw(",").key("modelSha256").quoted(model.sha256)
            .raw(",").key("startedElapsed").number(checkNotNull(value.loadStart.at.elapsedMillis)).raw(",").key("completedElapsed").number(checkNotNull(value.loadEnd.at.elapsedMillis))
            .raw(",").key("terminal").quoted(value.loadEnd.status?.terminal?.name).raw("},").key("inference")
        val inference = value.infer
        if (inference == null) out.raw("null") else {
            val terminal = checkNotNull(facts.firstTerminal)
            out.raw("{").key("requestIdSha256").quoted(value.logicalHash).raw(",").key("transportRequestIdSha256").quoted(value.transportHash)
                .raw(",").key("modelSha256").quoted(model.sha256).raw(",").key("startedElapsed").number(checkNotNull(inference.at.elapsedMillis))
                .raw(",").key("terminalElapsed").number(checkNotNull(terminal.at.elapsedMillis)).raw(",").key("deadlineElapsed").number(checkNotNull(scalars.deadlineElapsed))
                .raw(",").key("inputBytes").number(checkNotNull(inference.inputBytes)).raw(",").key("terminal").quoted(terminal.status.terminal?.name)
                .raw(",").key("terminalSource").quoted(if (terminal.origin == OrionLifecycleTerminalOrigin.FINAL_CHUNK_TRANSITION) "CALLBACK" else "MAIN_LIFECYCLE")
                .raw(",").key("failureCode").quoted(terminal.status.failure?.name).raw(",").key("chunks").raw("[")
            facts.chunks.forEachIndexed { index, chunk ->
                if (index != 0) out.raw(",")
                val metadata = chunk.metadata
                out.raw("{").key("sequence").number(metadata.sequence).raw(",").key("receivedElapsed").number(checkNotNull(chunk.at.elapsedMillis))
                    .raw(",").key("textDeltaBytes").number(checkNotNull(metadata.validatedTextDeltaUtf8Bytes).toLong())
                    .raw(",").key("toolDeltaBytes").number(checkNotNull(metadata.validatedToolDeltaUtf8Bytes).toLong())
                    .raw(",").key("isFinal").bool(metadata.isFinal).raw(",").key("finishReason").quoted(metadata.finishReason?.name)
                    .raw(",").key("errorCode").quoted(metadata.failure?.name).raw("}")
            }
            out.raw("]}")
        }
        out.raw(",").key("unload")
        if (value.unloadStart == null || value.unloadEnd == null) out.raw("null") else
            out.raw("{").key("startedElapsed").number(checkNotNull(value.unloadStart.at.elapsedMillis)).raw(",").key("completedElapsed").number(checkNotNull(value.unloadEnd.at.elapsedMillis))
                .raw(",").key("result").quoted(value.unloadEnd.cleanup?.name).raw("}")
        out.raw(",").key("memory").raw("[")
        facts.memory.forEachIndexed { index, row ->
            if (index != 0) out.raw(",")
            out.raw("{").key("phase").quoted(row.window.phase.name).raw(",").key("startedElapsed").number(checkNotNull(row.started.elapsedMillis))
                .raw(",").key("completedElapsed").number(checkNotNull(row.completed.elapsedMillis)).raw(",").key("bootCount").number(value.boot.toLong())
                .raw(",").key("mainPid").number(checkNotNull(row.mainPid).toLong()).raw(",").key("aiPid").number(checkNotNull(row.aiPid).toLong())
                .raw(",").key("mainSampledElapsed").number(checkNotNull(row.mainSampled.elapsedMillis)).raw(",").key("aiSampledElapsed").number(checkNotNull(row.aiSampled.elapsedMillis))
                .raw(",").key("mainPssBytes").number(checkNotNull(row.mainPssBytes)).raw(",").key("aiPssBytes").number(checkNotNull(row.aiPssBytes))
                .raw(",").key("aggregatePssBytes").number(checkNotNull(row.aggregatePssBytes)).raw(",").key("mainNativeIncluded").bool(checkNotNull(row.mainNativeIncluded))
                .raw(",").key("aiNativeIncluded").bool(checkNotNull(row.aiNativeIncluded)).raw(",").key("source").quoted(row.source.name)
                .raw(",").key("serviceState").quoted(row.serviceState.name).raw(",").key("nativeRuntimeLoaded").bool(checkNotNull(row.nativeRuntimeLoaded))
                .raw(",").key("nativeValidationCompleted").bool(checkNotNull(row.nativeValidationCompleted)).raw("}")
        }
        return out.raw("]}").finish()
    }
}
