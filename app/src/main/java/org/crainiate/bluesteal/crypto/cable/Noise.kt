package org.crainiate.bluesteal.crypto.cable

import org.crainiate.bluesteal.crypto.AesGcm
import org.crainiate.bluesteal.crypto.Hkdf
import java.security.MessageDigest

enum class HandshakeType(val protocolName: String) {
    KNPSK0("Noise_KNpsk0_P256_AESGCM_SHA256"),
    NKPSK0("Noise_NKpsk0_P256_AESGCM_SHA256"),
    NK("Noise_NK_P256_AESGCM_SHA256")
}

/**
 * The caBLE Noise symmetric state. Kotlin port of `Noise.swift` / Windows
 * `Noise.cs`. Nonces are big-endian counters at offset 0; HKDF salt is the
 * chaining key with empty info.
 */
class Noise {
    private var chainingKey = ByteArray(32)
    private var h = ByteArray(32)
    private var symmetricKey = ByteArray(32)
    private var symmetricNonce = 0

    fun initialize(type: HandshakeType) {
        val nameBytes = type.protocolName.toByteArray(Charsets.UTF_8) // all ≤ 32 bytes
        chainingKey = ByteArray(32)
        for (i in nameBytes.indices) chainingKey[i] = nameBytes[i]
        h = chainingKey.copyOf()
        symmetricKey = ByteArray(32)
        symmetricNonce = 0
    }

    fun mixHash(data: ByteArray) {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(h)
        md.update(data)
        h = md.digest()
    }

    fun mixKey(ikm: ByteArray) {
        val out = hkdf(ikm, 64)
        chainingKey = out.copyOfRange(0, 32)
        initializeKey(out.copyOfRange(32, 64))
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val out = hkdf(ikm, 96)
        chainingKey = out.copyOfRange(0, 32)
        mixHash(out.copyOfRange(32, 64))
        initializeKey(out.copyOfRange(64, 96))
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        writeUInt32BE(symmetricNonce, nonce, 0)
        symmetricNonce += 1
        val result = AesGcm.seal(symmetricKey, nonce, plaintext, aad = h)
        mixHash(result)
        return result
    }

    fun decryptAndHash(ciphertextWithTag: ByteArray): ByteArray? {
        if (ciphertextWithTag.size < 16) return null
        val nonce = ByteArray(12)
        writeUInt32BE(symmetricNonce, nonce, 0)
        symmetricNonce += 1
        val pt = AesGcm.open(symmetricKey, nonce, ciphertextWithTag, aad = h) ?: return null
        mixHash(ciphertextWithTag)
        return pt
    }

    fun handshakeHash(): ByteArray = h.copyOf()

    fun mixHashPoint(x962: ByteArray) {
        require(x962.size == CableP256.X962_LENGTH)
        mixHash(x962)
    }

    /** Returns (first, second) traffic keys. */
    fun trafficKeys(): Pair<ByteArray, ByteArray> {
        val out = hkdf(ByteArray(0), 64)
        return Pair(out.copyOfRange(0, 32), out.copyOfRange(32, 64))
    }

    private fun hkdf(ikm: ByteArray, length: Int): ByteArray =
        Hkdf.derive(ikm = ikm, salt = chainingKey, info = ByteArray(0), length = length)

    private fun initializeKey(key: ByteArray) {
        symmetricKey = key
        symmetricNonce = 0
    }

    private fun writeUInt32BE(value: Int, buf: ByteArray, offset: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
