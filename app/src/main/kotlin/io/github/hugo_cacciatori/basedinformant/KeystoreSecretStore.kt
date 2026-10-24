package io.github.hugo_cacciatori.basedinformant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.hugo_cacciatori.basedinformant.core.key.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The rfab key, encrypted with an AES-256-GCM key that never leaves the Android Keystore (04-platforms.md section 3).
 * Only the sealed bytes are stored, in `noBackupFilesDir`. Nothing here logs the key.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
    private val mutex = Mutex()

    override suspend fun rfabKey(): String? = io {
        if (!file.exists()) return@io null
        try {
            SealedSecret.open(wrappingKey(), file.readBytes())
        } catch (e: GeneralSecurityException) {
            // The Keystore key was lost or invalidated: the stored key can never be read again.
            file.delete()
            null
        } catch (e: IllegalArgumentException) {
            file.delete()
            null
        }
    }

    override suspend fun setRfabKey(key: String) = io {
        val sealed = SealedSecret.seal(wrappingKey(), key)
        // Written beside, then renamed, so a crash never leaves half a key.
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not store the key" }
        }
    }

    override suspend fun removeRfabKey() = io {
        file.delete()
        Unit
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "rfab-key-wrap"
        const val FILE_NAME = "rfab-key.bin"
    }
}

/**
 * The stored format: a version byte, the 12-byte IV the cipher chose, then the AES-GCM ciphertext with its tag.
 * Platform-free, so it is tested on the host with an ordinary AES key.
 */
internal object SealedSecret {
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun seal(key: SecretKey, secret: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV size" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(secret.encodeToByteArray())
    }

    fun open(key: SecretKey, sealed: ByteArray): String {
        require(sealed.size > 1 + IV_BYTES && sealed[0] == VERSION) { "not a sealed secret" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES).decodeToString()
    }
}
