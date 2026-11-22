package network.orion.voice.ipc

import java.util.UUID
import network.orion.core.ipc.VoiceCoreResponse
import network.orion.core.ipc.VoiceUtteranceRequest
import network.orion.core.ipc.OrionIpcProtocol
import network.orion.core.ipc.ProtocolInfo

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
    suspend fun disconnect()
}

class UnavailableVoiceCoreClient : VoiceCoreClient {
    override val protocolInfo: ProtocolInfo = OrionIpcProtocol.CURRENT
    override val state: VoiceCoreConnectionState = VoiceCoreConnectionState.UNAVAILABLE
    override suspend fun connect(): VoiceCoreConnectionState = state
    override suspend fun getCoreReadiness(): String? = null
    override suspend fun disconnect() = Unit
}
