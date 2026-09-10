package network.orion.core.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolInfoTest {
    private val streaming = IpcCapabilityId("streaming")
    private val cancellation = IpcCapabilityId("cancellation")

    @Test
    fun `canonical protocol starts at version one zero without implied capabilities`() {
        assertEquals(1, OrionIpcProtocol.CURRENT.protocolMajor)
        assertEquals(0, OrionIpcProtocol.CURRENT.protocolMinor)
        assertEquals(emptySet<IpcCapabilityId>(), OrionIpcProtocol.CURRENT.capabilities)
    }

    @Test
    fun `matching major negotiates the lower minor and common capabilities`() {
        val local = ProtocolInfo(1, 3, setOf(streaming, cancellation))
        val peer = ProtocolInfo(1, 1, setOf(streaming, IpcCapabilityId("health")))

        assertEquals(
            IpcProtocolNegotiation.Compatible(1, 1, setOf(streaming)),
            local.negotiateWith(peer),
        )
        assertEquals(local.negotiateWith(peer), peer.negotiateWith(local))
    }

    @Test
    fun `matching version with no shared capability remains protocol compatible`() {
        val result = ProtocolInfo(1, 0, setOf(streaming))
            .negotiateWith(ProtocolInfo(1, 0, setOf(cancellation)))

        assertEquals(IpcProtocolNegotiation.Compatible(1, 0, emptySet()), result)
    }

    @Test
    fun `protocol info snapshots advertised capabilities`() {
        val advertised = mutableSetOf(streaming)
        val info = ProtocolInfo(1, 0, advertised)

        advertised.clear()

        assertEquals(setOf(streaming), info.capabilities)
    }

    @Test
    fun `different major is rejected in both directions without capability fallback`() {
        val v1 = ProtocolInfo(1, 9, setOf(streaming))
        val v2 = ProtocolInfo(2, 0, setOf(streaming))

        assertEquals(IpcProtocolNegotiation.MajorMismatch(1, 2), v1.negotiateWith(v2))
        assertEquals(IpcProtocolNegotiation.MajorMismatch(2, 1), v2.negotiateWith(v1))
    }

    @Test
    fun `protocol versions reject nonpositive major and negative minor`() {
        for (major in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolInfo(major, 0) }
        }
        for (minor in listOf(-1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolInfo(1, minor) }
        }
        assertEquals(Int.MAX_VALUE, ProtocolInfo(Int.MAX_VALUE, Int.MAX_VALUE).protocolMinor)
    }

    @Test
    fun `negotiation outcomes reject states their names cannot represent`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcProtocolNegotiation.Compatible(0, 0, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            IpcProtocolNegotiation.Compatible(1, -1, emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            IpcProtocolNegotiation.MajorMismatch(1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IpcProtocolNegotiation.MajorMismatch(0, 1)
        }
    }

    @Test
    fun `capability identifiers reject empty padded and control-character values`() {
        for (invalid in listOf("", " ", " streaming", "streaming ", "line\nbreak", "tab\tvalue")) {
            assertThrows(IllegalArgumentException::class.java) { IpcCapabilityId(invalid) }
        }
        assertEquals("vendor.feature-v2", IpcCapabilityId("vendor.feature-v2").value)
    }
}
