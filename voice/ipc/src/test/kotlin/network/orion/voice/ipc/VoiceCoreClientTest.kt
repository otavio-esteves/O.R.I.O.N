package network.orion.voice.ipc

import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCoreClientTest {
    @Test fun `unavailable client advertises baseline but never claims a connection`() {
        val client: VoiceCoreClient = UnavailableVoiceCoreClient()
        assertEquals(1, client.protocolInfo.protocolMajor)
        assertEquals(0, client.protocolInfo.protocolMinor)
        var outcome: Result<VoiceCoreConnectionState>? = null
        val lifecycle: suspend () -> VoiceCoreConnectionState = {
            val state = client.connect()
            client.disconnect()
            client.disconnect()
            state
        }
        lifecycle.startCoroutine(object : Continuation<VoiceCoreConnectionState> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<VoiceCoreConnectionState>) { outcome = result }
        })
        assertEquals(VoiceCoreConnectionState.UNAVAILABLE, checkNotNull(outcome).getOrThrow())
        assertEquals(VoiceCoreConnectionState.UNAVAILABLE, client.state)
    }
}
