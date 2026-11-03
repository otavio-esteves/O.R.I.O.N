package network.orion.core.model

import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CapabilityTest {
    @Test fun `functional capability identifiers reject invalid static IDs`() {
        for (invalid in listOf("", " ", " padded", "control\ncharacter")) {
            assertThrows(IllegalArgumentException::class.java) { Capability(invalid) }
        }
    }
    @Test fun `unavailable registry never confuses permission with functional support`() {
        var outcome: Result<Boolean>? = null
        val query: suspend () -> Boolean = {
            UnavailableCapabilityRegistry().isAvailable(Capability("android.permission.RECORD_AUDIO"))
        }
        query.startCoroutine(object : Continuation<Boolean> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Boolean>) { outcome = result }
        })
        assertFalse(checkNotNull(outcome).getOrThrow())
    }
}
