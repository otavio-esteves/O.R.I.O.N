package network.orion.app.memories

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class OrionMemoryIngressWireTest {
    private val id=UUID.randomUUID()
    @Test fun closedWireKeepsUserContentCanonicalAndTraceOutsideReplayBytes() {
        val first=OrionMemoryIngressWire("CREATE",id,id,UUID.randomUUID(),"Lembrança é pessoal")
        val second=OrionMemoryIngressWire("CREATE",id,id,UUID.randomUUID(),"Lembrança é pessoal")
        assertArrayEquals(first.encode(),second.encode())
        val decoded=OrionMemoryIngressWire.decode(first.encode())
        assertEquals(id,decoded.memoryId); assertEquals("Lembrança é pessoal",decoded.content)
        assertEquals("OrionMemoryIngressWire(<redacted>)",decoded.toString())
    }
    @Test fun malformedOperationsTrailingBytesAndUnboundConfirmationsAreRejected() {
        for (wire in listOf(OrionMemoryIngressWire("PROMOTE_INFERRED",id,id,id,"x"),
            OrionMemoryIngressWire("CREATE",id,id,id,"e\u0301"),OrionMemoryIngressWire("DELETE",id,id,id,actionId=id)))
            assertThrows(IllegalArgumentException::class.java) { OrionMemoryIngressWire.decode(wire.encode()) }
        val correct=OrionMemoryIngressWire("CREATE",id,id,id,"x").encode()
        assertThrows(IllegalArgumentException::class.java) { OrionMemoryIngressWire.decode(correct+byteArrayOf(1)) }
    }
}
