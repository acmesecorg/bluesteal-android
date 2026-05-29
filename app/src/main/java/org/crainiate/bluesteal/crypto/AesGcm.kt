package org.crainiate.bluesteal.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a 128-bit tag and a 12-byte nonce. The JCA provider appends
 * the 16-byte tag to the ciphertext on `seal` and expects it appended on
 * `open`, which matches CryptoKit's `ciphertext + tag` layout and .NET's
 * split — so the wire bytes are identical across all three platforms.
 */
internal object AesGcm {
    private const val TAG_BITS = 128

    /** Returns ciphertext || 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Input is ciphertext || 16-byte tag. Returns null on auth failure. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray?): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (aad != null) cipher.updateAAD(aad)
            cipher.doFinal(ciphertextWithTag)
        } catch (e: AEADBadTagException) {
            null
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            null
        }
    }
}
