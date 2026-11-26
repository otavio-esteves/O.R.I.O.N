package network.orion.voice.ipc

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import network.orion.core.ipc.VoiceCoreResponse
import network.orion.core.ipc.VoiceUtteranceRequest
import network.orion.core.ipc.OrionIpcProtocol
import network.orion.core.ipc.ProtocolInfo

/** Upper bound for a voice turn, including the local Cortex cooldown and generation. */
const val ORION_VOICE_REPLY_TIMEOUT_MILLIS = 190_000L
const val ORION_VOICE_CALLBACK_TIMEOUT_MILLIS = ORION_VOICE_REPLY_TIMEOUT_MILLIS + 1_000

enum class VoiceCoreConnectionState { UNAVAILABLE, CONNECTED }

/**
 * Private voice transport. The Core owns request admission and all business authority.
 */
interface VoiceCoreClient {
    val protocolInfo: ProtocolInfo
    val state: VoiceCoreConnectionState
    suspend fun connect(): VoiceCoreConnectionState
    suspend fun getCoreReadiness(): String?
    suspend fun submitUtterance(request: VoiceUtteranceRequest): OrionVoiceAdmission = OrionVoiceAdmission.CORE_UNAVAILABLE
    suspend fun status(requestId: UUID, afterSequence: Long = -1): VoiceCoreResponse? = null
    suspend fun cancel(requestId: UUID): Boolean = false
    /** Completes on a terminal response or disconnect. A transport deadline fails the collection. */
    fun responses(requestId: UUID): Flow<VoiceCoreResponse> = emptyFlow()
    suspend fun healthCheck(): Boolean = false
    suspend fun disconnect()
}

class UnavailableVoiceCoreClient : VoiceCoreClient {
    override val protocolInfo: ProtocolInfo = OrionIpcProtocol.CURRENT
    override val state: VoiceCoreConnectionState = VoiceCoreConnectionState.UNAVAILABLE
    override suspend fun connect(): VoiceCoreConnectionState = state
    override suspend fun getCoreReadiness(): String? = null
    override suspend fun disconnect() = Unit
}
