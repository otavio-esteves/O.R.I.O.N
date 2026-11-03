package network.orion.core.actions

import java.util.UUID
import kotlin.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

class UnavailableActionEngineTest {
    @Test fun `a supplied ID never creates execution or claims success`() {
        var outcome: Result<ActionExecutionResult>? = null
        val execution: suspend () -> ActionExecutionResult = {
            UnavailableActionEngine().execute(UUID(0, 1))
        }
        execution.startCoroutine(object : Continuation<ActionExecutionResult> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<ActionExecutionResult>) { outcome = result }
        })
        assertEquals(ActionExecutionResult.UNAVAILABLE, checkNotNull(outcome).getOrThrow())
    }
}
