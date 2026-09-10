package network.orion.core.ipc

/**
 * Static identifier for an optional IPC protocol feature.
 *
 * IDs are compared exactly and must contain no surrounding whitespace or control
 * characters. They describe transport features; they do not represent Android
 * permissions, device capabilities, policy decisions or business authority.
 */
@JvmInline
value class IpcCapabilityId(val value: String) {
    init {
        require(value.isNotBlank()) { "IPC capability ID must not be blank." }
        require(value == value.trim()) { "IPC capability ID must not have surrounding whitespace." }
        require(value.none(Char::isISOControl)) { "IPC capability ID must not contain control characters." }
    }
}

/**
 * Version and optional features advertised during an internal IPC handshake.
 *
 * A positive major version identifies an incompatible protocol generation. Minor
 * versions are nonnegative and negotiate within a shared major version. Capability
 * sets are declarative; an empty intersection remains protocol-compatible but does
 * not enable any optional operation. The advertised set is snapshotted at creation.
 */
class ProtocolInfo(
    val protocolMajor: Int,
    val protocolMinor: Int,
    capabilities: Set<IpcCapabilityId> = emptySet(),
) {
    val capabilities: Set<IpcCapabilityId> = capabilities.toSet()

    init {
        require(protocolMajor > 0) { "IPC protocol major version must be positive." }
        require(protocolMinor >= 0) { "IPC protocol minor version must be nonnegative." }
    }

    /**
     * Rejects different major versions. A shared major negotiates the lower minor
     * and exact intersection of optional capability IDs, independent of call side.
     */
    fun negotiateWith(peer: ProtocolInfo): IpcProtocolNegotiation {
        if (protocolMajor != peer.protocolMajor) {
            return IpcProtocolNegotiation.MajorMismatch(protocolMajor, peer.protocolMajor)
        }
        return IpcProtocolNegotiation.Compatible(
            protocolMajor = protocolMajor,
            protocolMinor = minOf(protocolMinor, peer.protocolMinor),
            capabilities = capabilities intersect peer.capabilities,
        )
    }

    override fun equals(other: Any?): Boolean = other is ProtocolInfo &&
        protocolMajor == other.protocolMajor &&
        protocolMinor == other.protocolMinor &&
        capabilities == other.capabilities

    override fun hashCode(): Int {
        var result = protocolMajor
        result = 31 * result + protocolMinor
        return 31 * result + capabilities.hashCode()
    }
}

/** Result of protocol negotiation only; compatibility grants no business authority. */
sealed interface IpcProtocolNegotiation {
    data class Compatible(
        val protocolMajor: Int,
        val protocolMinor: Int,
        val capabilities: Set<IpcCapabilityId>,
    ) : IpcProtocolNegotiation {
        init {
            require(protocolMajor > 0) { "Negotiated IPC protocol major must be positive." }
            require(protocolMinor >= 0) { "Negotiated IPC protocol minor must be nonnegative." }
        }
    }

    /** Communication must be refused and the optional subsystem degraded safely. */
    data class MajorMismatch(
        val localProtocolMajor: Int,
        val peerProtocolMajor: Int,
    ) : IpcProtocolNegotiation {
        init {
            require(localProtocolMajor > 0 && peerProtocolMajor > 0) {
                "Mismatched IPC protocol majors must be positive."
            }
            require(localProtocolMajor != peerProtocolMajor) {
                "Major mismatch requires different protocol majors."
            }
        }
    }
}

/** Foundation baseline for the common internal IPC protocol. */
object OrionIpcProtocol {
    val CURRENT: ProtocolInfo = ProtocolInfo(protocolMajor = 1, protocolMinor = 0)
}
