package network.orion.core.security

import java.io.IOException
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FirstInstallFieldKeysTest {
    private var key: FieldKeyResult = FieldKeyResult.Unavailable
    private var attempted = false
    private var calls = 0
    private val keys = object : FieldKeyProvider {
        override fun currentKey() = key
        override fun keyForVersion(version: Int) = key
    }
    private fun available() = FieldKeyResult.Available(1, SecretKeySpec(ByteArray(32) { 1 }, "AES"))
    private fun startup(provision: () -> FieldKeyLifecycleResult? = {
        assertTrue("Intent must be durable before key creation", attempted)
        calls++
        key = available()
        FieldKeyLifecycleResult.APPLIED
    }) = FirstInstallFieldKeys(keys, { attempted }, { attempted = true }, provision)

    @Test fun `first install commits intent before provisioning and reopens existing keys`() = runBlocking {
        assertEquals(FieldKeyInitialization.READY, startup().initialize())
        assertEquals(FieldKeyInitialization.READY, startup().initialize())
        assertEquals(1, calls)
        key = FieldKeyResult.Unavailable
        assertEquals(FieldKeyInitialization.UNAVAILABLE, startup().initialize())
        assertEquals(1, calls)
    }

    @Test fun `existing encrypted records prohibit key creation and future automatic attempts`() = runBlocking {
        assertEquals(FieldKeyInitialization.EXISTING_DATA, startup { null }.initialize())
        assertEquals(FieldKeyInitialization.UNAVAILABLE, startup().initialize())
        assertEquals(0, calls)
    }

    @Test fun `crash after intent commit never provisions on next startup`() = runBlocking {
        try {
            startup { throw IOException("synthetic interrupted provisioning") }.initialize()
            fail("Expected interruption")
        } catch (_: IOException) { }
        assertEquals(FieldKeyInitialization.UNAVAILABLE, startup().initialize())
        assertEquals(0, calls)
    }

    @Test fun `failed marker commit and invalidated keys never invoke provisioning`() = runBlocking {
        try {
            FirstInstallFieldKeys(keys, { false }, { throw IOException("synthetic failed commit") },
                { calls++; FieldKeyLifecycleResult.APPLIED }).initialize()
            fail("Expected marker failure")
        } catch (_: IOException) { }
        key = FieldKeyResult.Invalidated
        assertEquals(FieldKeyInitialization.INVALIDATED, startup().initialize())
        assertEquals(0, calls)
    }

    @Test fun `existing provider without marker records intent before returning ready`() = runBlocking {
        key = available()
        assertEquals(FieldKeyInitialization.READY, startup().initialize())
        assertTrue(attempted)
        assertEquals(0, calls)
    }

    @Test fun `provider success must be followed by a readable key`() = runBlocking {
        assertEquals(FieldKeyInitialization.UNAVAILABLE, startup { FieldKeyLifecycleResult.APPLIED }.initialize())
        assertTrue(attempted)
    }
}
