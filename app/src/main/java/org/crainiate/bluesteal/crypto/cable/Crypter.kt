package org.crainiate.bluesteal.crypto.cable

import org.crainiate.bluesteal.crypto.AesGcm

/**
 * caBLE post-handshake AEAD framer. Kotlin port of `Crypter.swift` / Windows
 * `Crypter.cs`: 32-byte padding granularity, big-endian sequence number at
 * nonce offset 8, empty AAD.
 *
 * NOTE: stateful (sequence numbers) and NOT thread-safe; callers must serialise
 * send/receive on one thread.
 */
class Crypter(private val readKey: ByteArray, private val writeKey: ByteArray) {
    init {
        require(readKey.size == 32 && writeKey.size == 32)
    }

    private var readSeq = 0
    private var writeSeq = 0

    fun encrypt(plaintext: ByteArray): ByteArray? {
        val paddedSize = ((plaintext.size + 1 + PADDING - 1) / PADDING) * PADDING
        val numZeros = paddedSize - plaintext.size - 1

        val padded = ByteArray(paddedSize)
        System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
        padded[paddedSize - 1] = numZeros.toByte()

        if (writeSeq.toLong() and 0xFFFFFFFFL > MAX_SEQUENCE) return null

        val nonce = ByteArray(12)
        writeUInt32BE(writeSeq, nonce, 8)
        writeSeq += 1

        return AesGcm.seal(writeKey, nonce, padded, aad = null)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size < 16 || (readSeq.toLong() and 0xFFFFFFFFL) > MAX_SEQUENCE) return null

        val nonce = ByteArray(12)
        writeUInt32BE(readSeq, nonce, 8)

        val plaintext = AesGcm.open(readKey, nonce, ciphertext, aad = null) ?: return null
        readSeq += 1

        if (plaintext.isEmpty()) return null
        val paddingLength = plaintext[plaintext.size - 1].toInt() and 0xFF
        if (paddingLength + 1 > plaintext.size) return null
        return plaintext.copyOfRange(0, plaintext.size - paddingLength - 1)
    }

    fun isCounterpartyOf(other: Crypter): Boolean =
        readKey.contentEquals(other.writeKey) && writeKey.contentEquals(other.readKey)

    private fun writeUInt32BE(value: Int, buf: ByteArray, offset: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    companion object {
        private const val PADDING = 32
        private const val MAX_SEQUENCE = (1L shl 24) - 1
    }
}
