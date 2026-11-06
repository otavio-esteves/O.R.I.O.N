package network.orion.app.notifications
import java.time.Instant
import java.util.UUID
import network.orion.core.actions.ActionConfirmationSnapshot
import org.junit.Assert.*
import org.junit.Test
class OrionNotificationIngressWireTest {
    private fun draft()=OrionNotificationIngressWire("POST",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Title","Body\nLine 2")
    @Test fun initialPayloadRoundTripsExactlyWithoutLoggingRawContent() {
        val wire=draft();val bytes=wire.encode();val decoded=OrionNotificationIngressWire.decode(bytes)
        assertArrayEquals(bytes,decoded.encode());assertEquals(wire.body,decoded.body)
        assertFalse(wire.toString().contains(wire.title));assertFalse(wire.toString().contains(wire.body))
    }
    @Test fun traceIsSeparateMetadataWhileChangedBodyChangesCanonicalWire() {
        val wire=draft()
        assertArrayEquals(wire.encode(),OrionNotificationIngressWire(wire.operation,wire.requestId,wire.key,UUID.randomUUID(),wire.title,wire.body).encode())
        assertFalse(wire.encode().contentEquals(OrionNotificationIngressWire(wire.operation,wire.requestId,wire.key,wire.trace,wire.title,"Other").encode()))
    }
    @Test fun malformedVersionTrailingBytesAndUnboundedPayloadFail() {
        val bytes=draft().encode()
        assertThrows(IllegalArgumentException::class.java) { OrionNotificationIngressWire.decode(bytes+byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { OrionNotificationIngressWire.decode(ByteArray(16_385)) }
        bytes[3]=2;assertThrows(IllegalArgumentException::class.java) { OrionNotificationIngressWire.decode(bytes) }
    }
    @Test fun confirmationAndExplicitConfirmedExecutionKeepOnlyReferencesAndOriginalReceiptTimes() {
        val id=UUID.randomUUID();val at=Instant.parse("2026-11-05T04:00:00Z")
        val receipt=ActionConfirmationSnapshot(id,"a","a","fp","v1",at,at.plusSeconds(60),at)
        for(op in listOf("POST","EXECUTE")) {
            val wire=OrionNotificationIngressWire(op,id,id,id,actionId=id,actionVersion=1,fingerprint="fp",policyVersion="v1",receipt=receipt)
            val decoded=OrionNotificationIngressWire.decode(wire.encode())
            assertEquals("",decoded.title);assertEquals("",decoded.body);assertEquals(receipt,decoded.receipt)
        }
    }
    @Test fun contentCopiesAndNonCanonicalInitialTitlesAreRejected() {
        val wire=draft()
        assertThrows(IllegalArgumentException::class.java) { OrionNotificationIngressWire.decode(OrionNotificationIngressWire("POST",wire.requestId,wire.key,wire.trace," Title ",wire.body).encode()) }
        assertThrows(IllegalArgumentException::class.java) { OrionNotificationIngressWire.decode(OrionNotificationIngressWire("EXECUTE",wire.requestId,wire.key,wire.trace,wire.title,wire.body,actionId=UUID.randomUUID()).encode()) }
    }
}
