package org.crainiate.bluesteal.services

import java.security.SecureRandom

/**
 * Generates the short human-shareable pairing code (the "CODE" half of a tunnel
 * token). Replaces the code generation that used to live on `SignalingClient`.
 * Mirrors Windows `Services/PairingCode.cs`.
 */
object PairingCode {
    /**
     * Uppercase letters with the ambiguous I, L, O removed. **Must match the
     * other platforms exactly** so a code generated on one device is typable on
     * another.
     */
    val alphabet: CharArray = "ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray()

    /** 5 letters from a 23-letter alphabet ≈ 6.4 M combinations. */
    const val length: Int = 5

    private val rng = SecureRandom()

    fun make(): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) sb.append(alphabet[rng.nextInt(alphabet.size)])
        return sb.toString()
    }
}
