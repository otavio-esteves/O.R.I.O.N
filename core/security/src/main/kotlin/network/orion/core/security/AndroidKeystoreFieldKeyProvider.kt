package network.orion.core.security

import android.app.Application
import android.content.Context
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import network.orion.core.common.process.MainProcessStoragePolicy
import network.orion.core.common.process.OrionProcessRole

/**
 * MAIN-only credential-encrypted, non-backup data-key registry. AES-256 data keys
 * are wrapped together using a non-exportable Android Keystore AES-GCM key.
 * Opening and reading never generate a key or overwrite an unreadable registry.
 * Perform disk/Keystore operations off the UI thread. This grants no action authority.
 */
class AndroidKeystoreFieldKeyProvider private constructor(private val keys: DurableFieldKeyProvider) : FieldKeyProvider {
    override fun currentKey(): FieldKeyResult = keys.currentKey()
    override fun keyForVersion(version: Int): FieldKeyResult = keys.keyForVersion(version)

    /** Explicit first-install operation, only after the owner proves encrypted storage empty. */
    fun provisionNewInstallation(): FieldKeyLifecycleResult = keys.provisionNewInstallation()
    fun rotate(): FieldKeyLifecycleResult = keys.rotate()

    companion object {
        fun open(context: Context): AndroidKeystoreFieldKeyProvider {
            val app = context.applicationContext
            MainProcessStoragePolicy.requireAccess(
                OrionProcessRole.fromProcessName(app.packageName, Application.getProcessName()),
                app.getSystemService(UserManager::class.java).isUserUnlocked,
                context.isDeviceProtectedStorage,
            )
            val file = File(app.noBackupFilesDir, "orion_field_keys/registry.v1")
            return AndroidKeystoreFieldKeyProvider(DurableFieldKeyProvider(
                AtomicWrappedKeyRegistryStorage(file), AndroidFieldKeyWrapping(),
            ))
        }
    }
}

internal class AtomicWrappedKeyRegistryStorage(private val file: File) : WrappedKeyRegistryStorage {
    private val atomic = AtomicFile(file)

    override fun read(): ByteArray? {
        val stream = try { atomic.openRead() } catch (failure: FileNotFoundException) {
            // Permission errors and unreadable paths must not masquerade as a new install.
            if (file.exists() || File(file.path + ".bak").exists()) throw failure
            return null
        }
        return stream.use {
            if (it.channel.size() > DurableFieldKeyProvider.MAX_REGISTRY_BYTES) throw FieldKeyProtectionException(true)
            val buffer = ByteArray(DurableFieldKeyProvider.MAX_REGISTRY_BYTES + 1)
            var used = 0
            while (used < buffer.size) {
                val count = it.read(buffer, used, buffer.size - used)
                if (count < 0) break
                used += count
            }
            if (used > DurableFieldKeyProvider.MAX_REGISTRY_BYTES) throw FieldKeyProtectionException(true)
            buffer.copyOf(used)
        }
    }

    override fun write(bytes: ByteArray) {
        val parent = file.parentFile ?: throw IOException("Key registry unavailable.")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Key registry unavailable.")
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
        // AtomicFile reports some rename failures internally; verify the durable result.
        if (read()?.contentEquals(bytes) != true) throw IOException("Key registry commit unavailable.")
    }
}

internal class AndroidFieldKeyWrapping(private val alias: String = ALIAS) : FieldKeyWrapping {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    override fun exists(): Boolean = withProtection { store().containsAlias(alias) }

    override fun create() = withProtection {
        if (store().containsAlias(alias)) throw FieldKeyProtectionException(false)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        generator.generateKey()
        Unit
    }

    override fun wrap(plaintext: ByteArray): ByteArray = withProtection {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val nonce = cipher.iv
        if (nonce.size != 12) throw FieldKeyProtectionException(false)
        // Fixed wrapping format: 4-byte version + 12-byte nonce + authenticated ciphertext/tag.
        byteArrayOf(0, 0, 0, 1) + nonce + cipher.doFinal(plaintext)
    }

    override fun unwrap(ciphertext: ByteArray): ByteArray = withProtection {
        if (ciphertext.size < 32 || !ciphertext.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 0, 1))) {
            throw FieldKeyProtectionException(true)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, ciphertext.copyOfRange(4, 16)))
        cipher.updateAAD(AAD)
        cipher.doFinal(ciphertext, 16, ciphertext.size - 16)
    }

    private fun key(): SecretKey = store().getKey(alias, null) as? SecretKey ?: throw FieldKeyProtectionException(false)

    private fun <T> withProtection(operation: () -> T): T = try { operation() }
        catch (_: KeyPermanentlyInvalidatedException) { throw FieldKeyProtectionException(true) }
        catch (_: UnrecoverableKeyException) { throw FieldKeyProtectionException(true) }
        catch (_: AEADBadTagException) { throw FieldKeyProtectionException(true) }
        catch (_: BadPaddingException) { throw FieldKeyProtectionException(true) }
        catch (_: GeneralSecurityException) { throw FieldKeyProtectionException(false) }
        catch (_: IOException) { throw FieldKeyProtectionException(false) }
        catch (_: ProviderException) { throw FieldKeyProtectionException(false) }

    companion object {
        private const val ALIAS = "orion_field_wrapping_v1"
        private val AAD = "orion_wrapped_data_key_registry|1|AES_GCM|1".toByteArray(Charsets.UTF_8)
    }
}
