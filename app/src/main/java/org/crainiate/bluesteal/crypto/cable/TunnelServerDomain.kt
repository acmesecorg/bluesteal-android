package org.crainiate.bluesteal.crypto.cable

import java.security.MessageDigest

/**
 * Resolves a 16-bit tunnel-server domain id to a hostname. For bluesteal v1 the
 * id is clamped to 0 (Google), but the hashed-domain derivation is ported
 * faithfully so the platforms stay byte-compatible if that changes. Kotlin port
 * of `bluesteal-macos/.../Crypto/Cable/TunnelServerDomain.swift`.
 */
object TunnelServerDomain {
    private val assignedDomains = listOf(
        "cable.ua5v.com", // 0 - Google
        "cable.auth.com"  // 1 - Apple
    )

    // "caBLEv2 tunnel server domain" (28 bytes) + 3 NUL bytes = 31 bytes.
    private val salt = "caBLEv2 tunnel server domain".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0)
    private val tlds = listOf(".com", ".org", ".net", ".info")
    private val base32Alphabet = "abcdefghijklmnopqrstuvwxyz234567".toCharArray()

    fun get(id: Int): String? {
        val uid = id and 0xFFFF
        if (uid < 256) {
            return if (uid < assignedDomains.size) assignedDomains[uid] else null
        }

        val buf = salt.copyOf() // 31 bytes
        buf[28] = (uid and 0xFF).toByte()
        buf[29] = ((uid ushr 8) and 0xFF).toByte()

        val digest = MessageDigest.getInstance("SHA-256").digest(buf)

        var result = 0L
        for (i in 0 until 8) result = result or ((digest[i].toLong() and 0xFF) shl (8 * i))

        val tld = tlds[(result and 3).toInt()]
        result = result ushr 2

        val sb = StringBuilder("cable.")
        while (result != 0L) {
            sb.append(base32Alphabet[(result and 31).toInt()])
            result = result ushr 5
        }
        sb.append(tld)
        return sb.toString()
    }
}
