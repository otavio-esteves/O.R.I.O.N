package network.orion.app.ai

import java.security.MessageDigest

import network.orion.ai.api.OrionInferenceStatus
import network.orion.ai.api.OrionLifecycleChunkMetadata
import network.orion.ai.api.OrionLifecycleTerminalOrigin
import network.orion.core.time.OrionObservationMoment

/** Bounded technical identity only. No raw-value getter, content hash or implicit diagnostic output. */
internal class OrionRuntimeTechnicalId(private val raw: String) {
    init {
        require(raw.length in 1..128 && raw == raw.trim() && raw.none(Char::isISOControl))
        var index = 0
        while (index < raw.length) {
            val unit = raw[index++]
            if (unit.isHighSurrogate()) { require(index < raw.length && raw[index].isLowSurrogate()); index++ }
            else require(!unit.isLowSurrogate())
        }
    }
    fun sameValue(other: OrionRuntimeTechnicalId): Boolean = raw == other.raw
    fun matchesRaw(value: String): Boolean = raw == value
    /** Separate manual encoder seam only. Bounded strict UTF-8 identity, never content or a fingerprint. */
    fun sha256ForManualExport(): String? {
        var bytes: ByteArray? = null
        return try {
            bytes = raw.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { null }
        finally { bytes?.fill(0) }
    }
    override fun toString() = "OrionRuntimeTechnicalId(REDACTED)"
}

/** A minimized copy after future original READY/profile/file checks, never a ModelRegistryEntity. */
internal class OrionRuntimeModelReference(val sha256: String, val sizeBytes: Long) {
    init { require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' } && sizeBytes > 0) }
    override fun toString() = "OrionRuntimeModelReference(REDACTED)"
}

/** Opaque references are equality fences only. They are never inspected, invoked, logged or snapshotted. */
internal class OrionRuntimeCaptureFence(
    private val owner: Any?, private val token: Any?, private val clientScope: Any?,
    private val connection: Any?, private val binder: Any?,
    val connectionEpoch: Long?, val turnGeneration: Long?, val mainPid: Int?, val aiPid: Int?, val bootCount: Int?,
    val model: OrionRuntimeModelReference, private val logicalId: OrionRuntimeTechnicalId,
) {
    fun known(): Boolean = owner != null && token != null && clientScope != null && connection != null && binder != null &&
        connectionEpoch != null && connectionEpoch >= 0 && turnGeneration != null && turnGeneration > 0 &&
        mainPid != null && mainPid > 0 && aiPid != null && aiPid > 0 && mainPid != aiPid && bootCount != null && bootCount >= 0
    fun matches(other: OrionRuntimeCaptureFence): Boolean = known() && other.known() &&
        owner === other.owner && token === other.token && clientScope === other.clientScope &&
        connection === other.connection && binder === other.binder && model === other.model &&
        connectionEpoch == other.connectionEpoch && turnGeneration == other.turnGeneration &&
        mainPid == other.mainPid && aiPid == other.aiPid && bootCount == other.bootCount && logicalId.sameValue(other.logicalId)
    fun snapshotAssociation() = OrionRuntimeCaptureAssociation(model, mainPid, aiPid, bootCount, connectionEpoch, turnGeneration, logicalId)
    override fun toString() = "OrionRuntimeCaptureFence(REDACTED)"
}

/** Historical minimized association only; it retains none of the owner/client/connection/Binder references. */
internal class OrionRuntimeCaptureAssociation(val model: OrionRuntimeModelReference, val mainPid: Int?, val aiPid: Int?,
    val bootCount: Int?, val connectionEpoch: Long?, val turnGeneration: Long?, private val logicalId: OrionRuntimeTechnicalId) {
    fun logicalIdSha256ForManualExport(): String? = logicalId.sha256ForManualExport()
    override fun toString() = "OrionRuntimeCaptureAssociation(REDACTED)"
}

/** Already-observed capture data, never a domain deadline, lease or clock/provider. */
internal data class OrionRuntimeEventTime(val bootCount: Int?, val elapsedMillis: Long?) {
    companion object {
        fun fromObservation(value: OrionObservationMoment) = OrionRuntimeEventTime(value.observationalBootCount, value.elapsedRealtimeMillis)
    }
}

internal enum class OrionRuntimeMarkerKind {
    LOAD_LOCAL_REQUEST_START, LOAD_TRUE_DISPATCH, LOAD_TRUE_TERMINAL, INFERENCE_TRUE_DISPATCH, UNLOAD_TRUE_DISPATCH, UNLOAD_TRUE_CLEANUP,
    DELIVERY_ENTRY, DELIVERY_RETURN,
}
internal enum class OrionRuntimeCleanupResult { UNLOADED, FAILED }

/** Source-supplied facts; a constructed marker is neither proof of invocation nor admission. */
internal class OrionRuntimeOperationMarker(val kind: OrionRuntimeMarkerKind, val at: OrionRuntimeEventTime,
    private val issuedId: OrionRuntimeTechnicalId? = null, val status: OrionInferenceStatus? = null,
    val inputBytes: Long? = null, val cleanup: OrionRuntimeCleanupResult? = null, val loadedModelConfirmed: Boolean? = null) {
    fun hasIssuedId(): Boolean = issuedId != null
    fun sameIssuedId(other: OrionRuntimeOperationMarker): Boolean = issuedId != null && other.issuedId != null && issuedId.sameValue(other.issuedId)
    fun issuedIdSha256ForManualExport(): String? = issuedId?.sha256ForManualExport()
    override fun toString() = "OrionRuntimeOperationMarker(kind=$kind)"
}

internal class OrionRuntimeFirstTerminal(val at: OrionRuntimeEventTime, val status: OrionInferenceStatus,
    val origin: OrionLifecycleTerminalOrigin) {
    override fun toString() = "OrionRuntimeFirstTerminal(terminal=${status.terminal}, origin=$origin)"
}
internal class OrionRuntimeOrderedChunk(val at: OrionRuntimeEventTime, val metadata: OrionLifecycleChunkMetadata) {
    override fun toString() = "OrionRuntimeOrderedChunk(sequence=${metadata.sequence})"
}

internal enum class OrionRuntimeMemoryPhase { BEFORE_LOAD, DURING_LOAD, AFTER_LOAD, DURING_INFERENCE,
    AFTER_TERMINAL, DURING_UNLOAD, AFTER_UNLOAD }
internal enum class OrionRuntimeServiceState { UNLOADED, LOADING, LOADED, INFERENCING, UNLOADING, UNKNOWN }
internal enum class OrionRuntimeMemorySource { SELF_DEBUG_MEMORY_INFO_PSS }

/** Closed event window supplied by the future classifier; this unit does not manufacture/classify windows. */
internal class OrionRuntimePhaseWindow(val phase: OrionRuntimeMemoryPhase, val started: OrionRuntimeEventTime,
    val completed: OrionRuntimeEventTime)
internal class OrionRuntimeMemoryRow(val window: OrionRuntimePhaseWindow,
    val started: OrionRuntimeEventTime, val completed: OrionRuntimeEventTime,
    val mainSampled: OrionRuntimeEventTime, val aiSampled: OrionRuntimeEventTime,
    val mainPid: Int?, val aiPid: Int?, val mainPssBytes: Long?, val aiPssBytes: Long?, val aggregatePssBytes: Long?,
    val observedTotalMemoryBytes: Long?, val mainNativeIncluded: Boolean?, val aiNativeIncluded: Boolean?,
    val serviceState: OrionRuntimeServiceState, val nativeRuntimeLoaded: Boolean?, val nativeValidationCompleted: Boolean?,
    val source: OrionRuntimeMemorySource) {
    override fun toString() = "OrionRuntimeMemoryRow(phase=${window.phase})"
}

internal enum class OrionRuntimeCaptureGap { UNWIRED_SOURCE, IDENTITY_UNAVAILABLE, IDENTITY_CHANGED,
    CLOCK_UNAVAILABLE, CLOCK_DISCONTINUOUS, NOT_STARTED, ALREADY_COMPLETED, CONTENDED, CHUNK_SEQUENCE,
    CHUNK_METADATA_UNAVAILABLE, CHUNK_METADATA_INVALID, TERMINAL_UNAVAILABLE, TERMINAL_MISMATCH, MEMORY_INVALID,
    MARKER_INVALID, CAPACITY, RECORDING_FAILURE, PROCESS_DIED }

internal class OrionRuntimeCaptureSnapshot(val association: OrionRuntimeCaptureAssociation?, val started: OrionRuntimeEventTime?, val completed: OrionRuntimeEventTime?,
    val markers: List<OrionRuntimeOperationMarker>, val chunks: List<OrionRuntimeOrderedChunk>,
    val firstTerminal: OrionRuntimeFirstTerminal?, val firstTerminalUnavailable: Boolean,
    val memory: List<OrionRuntimeMemoryRow>, val gaps: Set<OrionRuntimeCaptureGap>,
    val invalidated: Boolean, val chunkSequenceIntact: Boolean, val droppedAtLeast: Long,
    val recordingFailuresAtLeast: Long, val lossAccountingComplete: Boolean, val snapshotContended: Boolean,
    val lifecycleImport: OrionRuntimeLifecycleImportSummary? = null) {
    val wired = false
    val sourceAuthenticated = false
    val runtimeEvidenceV1Complete = false
    val physicalVerified = false
    val profileApproved = false
    val productionInferenceApproved = false
    val fullF5GateClosed = false
    val fullSoakGateClosed = false
    val f6Authorized = false
    override fun toString() = "OrionRuntimeCaptureSnapshot(wired=false, invalidated=$invalidated)"
}

/** Detached manual source integrity only; no duplicate chunk array, request, content or raw error. */
internal class OrionRuntimeLifecycleImportSummary(source: network.orion.ai.api.OrionLifecycleEventSnapshot) {
    val claimed = source.claimed
    val active = source.active
    val invalidated = source.invalidated
    val firstTerminalUnavailable = source.firstTerminalRecordUnavailable
    val orderedEventCount = source.orderedEvents.size
    val pendingUpdatesAtLeast = source.pendingUpdatesAtLeast
    val gaps = source.gaps
    val droppedAtLeast = source.droppedAtLeast
    val recordingFailuresAtLeast = source.recordingFailuresAtLeast
    val lossAccountingComplete = source.lossAccountingComplete
    val chunkSequenceIntact = source.chunkSequenceIntact
    val snapshotContended = source.snapshotContended
}
