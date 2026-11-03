package network.orion.voice.ipc

import network.orion.core.ipc.OrionIpcProtocol
import network.orion.core.ipc.ProtocolInfo

/** F1 exposes no live transport; operational lifecycle states enter with the voice contract. */
enum class VoiceCoreConnectionState { UNAVAILABLE }

/**
 * Lightweight connection port. This is not the wire protocol or a command API.
 * Future transports must negotiate the existing protocol and enforce payload guards;
 * every mutable voice request still enters the Core's gated versioned ingress.
 */
interface VoiceCoreClient {
    val protocolInfo: ProtocolInfo
    val state: VoiceCoreConnectionState
    suspend fun connect(): VoiceCoreConnectionState
    suspend fun disconnect()
}

class UnavailableVoiceCoreClient : VoiceCoreClient {
    override val protocolInfo: ProtocolInfo = OrionIpcProtocol.CURRENT
    override val state: VoiceCoreConnectionState = VoiceCoreConnectionState.UNAVAILABLE
    override suspend fun connect(): VoiceCoreConnectionState = state
    override suspend fun disconnect() = Unit
}
