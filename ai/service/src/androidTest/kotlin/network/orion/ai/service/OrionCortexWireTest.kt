package network.orion.ai.service

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import network.orion.ai.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android Parcel/Binder behavior only; no model/native/performance qualification. */
@RunWith(AndroidJUnit4::class)
class OrionCortexWireTest {
    @Test fun oversizedInlinePayloadIsRejectedBeforeBinderInvocation() {
        var invoked = false
        val endpoint = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                invoked = true; return false
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionCortexWire.call(endpoint, OrionCortexWire.INFER,
                { it.writeString("x".repeat(262_144)) }) { Unit }
        }
        assertFalse(invoked)
    }
    @Test fun majorAndSchemaMismatchesAreRejected() {
        for ((major, schema) in listOf(2 to 1, 1 to 2)) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(OrionCortexWire.DESCRIPTOR)
                data.writeInt(major); data.writeInt(0); data.writeInt(schema)
                data.setDataPosition(0)
                assertThrows(IllegalArgumentException::class.java) { OrionCortexWire.checkFrame(data) }
            } finally { data.recycle() }
        }
    }
    @Test fun callbackParcelsPreserveUnicodeAndTerminalMetadata() {
        val observed = mutableListOf<OrionCortexChunk>()
        val endpoint = OrionCortexCallbackBinder(observed::add)
        val id = UUID.randomUUID()
        val chunk = OrionCortexChunk(id, 0, "Olá \uD83D\uDE80")
        OrionCortexWire.chunk(endpoint, chunk)
        OrionCortexWire.chunk(endpoint, OrionCortexChunk(id, 1, terminal = OrionCortexRequestStatus.SUCCEEDED))
        assertEquals(listOf(chunk, OrionCortexChunk(id, 1, terminal = OrionCortexRequestStatus.SUCCEEDED)), observed)
    }
    @Test fun trailingFieldsCannotBeSilentlyAccepted() {
        val data = Parcel.obtain()
        try {
            OrionCortexWire.frame(data); data.writeInt(99); data.setDataPosition(0)
            OrionCortexWire.checkFrame(data)
            assertThrows(IllegalArgumentException::class.java) { OrionCortexWire.exhausted(data) }
        } finally { data.recycle() }
    }
    @Test fun malformedChunksAreNotDelivered() {
        var delivered = false
        val endpoint = OrionCortexCallbackBinder { delivered = true }
        val data = Parcel.obtain()
        try {
            OrionCortexWire.frame(data, OrionCortexWire.CALLBACK)
            data.writeString(UUID.randomUUID().toString()); data.writeLong(-1)
            data.writeString("private"); data.writeString(null)
            assertFalse(endpoint.transact(OrionCortexWire.CHUNK, data, null, IBinder.FLAG_ONEWAY))
            assertFalse(delivered)
        } finally { data.recycle() }
    }
}
