package network.orion.core.queries

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QueryContractTest {
    private data class Reply(val count: Int)
    private data class Request(val reference: String) : Query<Reply>

    @Test
    fun `request retains its declared result type`() {
        val port = object : QueryGateway {
            override suspend fun <R> execute(request: Query<R>): R {
                // One concrete request type in this fixture, checked before its result cast.
                check(request is Request)
                assertEquals("reference", request.reference)
                @Suppress("UNCHECKED_CAST")
                return Reply(3) as R
            }
        }
        val reply: Reply = immediate { port.execute(Request("reference")) }
        assertEquals(Reply(3), reply)
    }

    @Test
    fun `port propagates handler failure without retry`() {
        val failure = IllegalStateException("handler failed")
        var calls = 0
        val port = object : QueryGateway {
            override suspend fun <R> execute(request: Query<R>): R {
                calls++
                throw failure
            }
        }
        val thrown = assertThrows(IllegalStateException::class.java) {
            immediate { port.execute(Request("reference")) }
        }
        assertSame(failure, thrown)
        assertEquals(1, calls)
    }

    // These contract fixtures complete synchronously; no scheduler is under test.
    private fun <T> immediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome).getOrThrow()
    }
}
