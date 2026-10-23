package io.github.hugo_cacciatori.basedinformant

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class SealedSecretTest {
    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    // Synthetic, built in parts so the secret scan does not take it for a real key.
    private val secret = "rfab_" + "x".repeat(24) + "wxyz"

    @Test
    fun opensWhatItSealed() {
        val key = aesKey()
        assertEquals(secret, SealedSecret.open(key, SealedSecret.seal(key, secret)))
    }

    @Test
    fun theStoredBytesNeverHoldTheKeyAndDifferEachTime() {
        val key = aesKey()
        val first = SealedSecret.seal(key, secret)
        val second = SealedSecret.seal(key, secret)
        assertFalse(first.decodeToString().contains("wxyz"))
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun anotherKeyOrATamperedFileIsRefused() {
        val sealed = SealedSecret.seal(aesKey(), secret)
        assertFailsWith<GeneralSecurityException> { SealedSecret.open(aesKey(), sealed) }

        val key = aesKey()
        val tampered = SealedSecret.seal(key, secret).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<GeneralSecurityException> { SealedSecret.open(key, tampered) }
        assertFailsWith<IllegalArgumentException> { SealedSecret.open(key, byteArrayOf(9, 9, 9)) }
    }
}
