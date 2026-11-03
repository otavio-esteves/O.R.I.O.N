package network.orion.core.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IpcBoundaryPolicyTest {
    @Test
    fun `schema versions are positive and do not imply protocol compatibility`() {
        for (invalid in listOf(0, -1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { IpcSchemaVersion(invalid) }
        }
        assertEquals(1, IpcSchemaVersion(1).value)
        assertEquals(Int.MAX_VALUE, IpcSchemaVersion(Int.MAX_VALUE).value)
    }

    @Test
    fun `inline payload limit is exactly 256 KiB in bytes`() {
        assertEquals(256 * 1024, InlineIpcPayloadPolicy.MAX_INLINE_IPC_PAYLOAD_BYTES)
        assertEquals(
            IpcPayloadTransport.INLINE,
            InlineIpcPayloadPolicy.transportFor(0),
        )
        assertEquals(
            IpcPayloadTransport.INLINE,
            InlineIpcPayloadPolicy.transportFor(256 * 1024),
        )
        assertEquals(
            IpcPayloadTransport.EXTERNAL_REQUIRED,
            InlineIpcPayloadPolicy.transportFor(256 * 1024 + 1),
        )
        assertEquals(
            IpcPayloadTransport.EXTERNAL_REQUIRED,
            InlineIpcPayloadPolicy.transportFor(Int.MAX_VALUE),
        )
    }

    @Test
    fun `negative payload sizes are rejected rather than treated as inline`() {
        for (invalid in listOf(-1, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                InlineIpcPayloadPolicy.transportFor(invalid)
            }
        }
    }
}
