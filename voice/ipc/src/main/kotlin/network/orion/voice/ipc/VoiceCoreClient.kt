package network.orion.voice.ipc

import network.orion.core.ipc.OrionIpcProtocol
import network.orion.core.ipc.ProtocolInfo

enum class VoiceCoreConnectionState { UNAVAILABLE, CONNECTED }

/**
 * Lightweight connection and readiness port.
 * Future transports must negotiate the existing protocol and enforce payload guards;
 * every mutable voice request still enters the Core's gated versioned ingress.
 */
interface VoiceCoreClient {
    val protocolInfo: ProtocolInfo
    val state: VoiceCoreConnectionState
    suspend fun connect(): VoiceCoreConnectionState
    suspend fun getCoreReadiness(): String?
    suspend fun disconnect()
}

class UnavailableVoiceCoreClient : VoiceCoreClient {
    override val protocolInfo: ProtocolInfo = OrionIpcProtocol.CURRENT
    override val state: VoiceCoreConnectionState = VoiceCoreConnectionState.UNAVAILABLE
    override suspend fun connect(): VoiceCoreConnectionState = state
    override suspend fun getCoreReadiness(): String? = null
    override suspend fun disconnect() = Unit
}
