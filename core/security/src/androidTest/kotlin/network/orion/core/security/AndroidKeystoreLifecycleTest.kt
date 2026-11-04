package network.orion.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses unique test-only aliases and files; never deletes a production key or registry. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreLifecycleTest {
    private fun fixture(test: (File, String, () -> DurableFieldKeyProvider) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "orion_key_test_$id")
        val file = File(directory, "registry.v1")
        val alias = "orion_test_field_wrapping_$id"
        val provider = { DurableFieldKeyProvider(AtomicWrappedKeyRegistryStorage(file), AndroidFieldKeyWrapping(alias)) }
        try { test(file, alias, provider) } finally {
            KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    @Test fun actualKeystoreWrapReopenRotateAndHistoricalFieldRead() = fixture { file, alias, provider ->
        val first = provider()
        assertEquals(FieldKeyResult.Unavailable, first.currentKey())
        assertEquals(FieldKeyLifecycleResult.APPLIED, first.provisionNewInstallation())
        val key = first.currentKey() as FieldKeyResult.Available
        val context = FieldEncryptionContext("task/fixture", "description", 1)
        val secret = "instrumented synthetic secret".toByteArray()
        val envelope = (OrionFieldEncryption(first).encrypt(secret, context) as FieldEncryptionResult.Encrypted).envelope
        val wrappingKey = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }.getKey(alias, null)
        assertNull(wrappingKey.encoded)
        assertFalse(contains(file.readBytes(), key.key.encoded))
        assertFalse(contains(file.readBytes(), secret))
        assertEquals(FieldKeyLifecycleResult.APPLIED, first.rotate())
        val reopened = provider()
        assertEquals(2, (reopened.currentKey() as FieldKeyResult.Available).version)
        assertArrayEquals(key.key.encoded, (reopened.keyForVersion(1) as FieldKeyResult.Available).key.encoded)
        val clear = OrionFieldEncryption(reopened).decrypt(envelope, context) as FieldDecryptionResult.Plaintext
        assertArrayEquals(secret, clear.copyBytes())
    }

    @Test fun missingWrappingKeyNeverReplacesRegistry() = fixture { file, alias, provider ->
        assertEquals(FieldKeyLifecycleResult.APPLIED, provider().provisionNewInstallation())
        val original = file.readBytes()
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }.deleteEntry(alias)
        assertEquals(FieldKeyResult.Unavailable, provider().currentKey())
        assertEquals(FieldKeyLifecycleResult.ALREADY_EXISTS, provider().provisionNewInstallation())
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun tamperedRegistryFailsAuthenticationAndCannotBeOverwritten() = fixture { file, _, provider ->
        assertEquals(FieldKeyLifecycleResult.APPLIED, provider().provisionNewInstallation())
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        val corrupt = file.readBytes()
        assertEquals(FieldKeyResult.Invalidated, provider().currentKey())
        assertEquals(FieldKeyLifecycleResult.INVALIDATED, provider().rotate())
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun atomicInterruptedWriteRestoresCommittedRegistry() = fixture { file, _, provider ->
        assertEquals(FieldKeyLifecycleResult.APPLIED, provider().provisionNewInstallation())
        val original = (provider().currentKey() as FieldKeyResult.Available).key.encoded
        // AtomicFile leaves the previous base committed while the .new write is unfinished.
        android.util.AtomicFile(file).startWrite().use { it.write(byteArrayOf(1, 2, 3)); it.fd.sync() }
        assertArrayEquals(original, (provider().currentKey() as FieldKeyResult.Available).key.encoded)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}
