package org.crainiate.bluesteal.services.tunnel

import org.crainiate.bluesteal.crypto.Hkdf
import org.crainiate.bluesteal.crypto.cable.CableKey
import org.crainiate.bluesteal.crypto.cable.CableP256

/** Uppercase hex, no separators. */
internal fun ByteArray.hexUpper(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0xF])
    }
    return sb.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()

/** Hex string → bytes; accepts upper/lower case, rejects odd length / non-hex. */
internal fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        val hi = Character.digit(hex[i], 16)
        val lo = Character.digit(hex[i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}

/** All cryptographic material derived from a 5-char code. */
class TunnelKeys(
    val tunnelServerId: Int,
    val tunnelId: ByteArray,      // legacy salt-empty id (kept for parity; unused)
    val psk: ByteArray,
    val responderStatic: CableKey
)

/**
 * Derives all cryptographic material from the 5-char pairing code and handles
 * the 11-char composite OOB token (`CODE-ROUTING`, e.g. "BSTL1-7C4A9C"). Kotlin
 * port of `TunnelKeyDerivation.swift` / Windows `TunnelKeyDerivation.cs`.
 */
object TunnelKeyDerivation {
    private fun label(s: String) = s.toByteArray(Charsets.UTF_8)

    private val tokenCodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toSet()
    private val tokenHexChars = "0123456789ABCDEF".toSet()

    fun derive(code: String): TunnelKeys {
        require(code.trim().isNotEmpty())
        val ikm = code.toByteArray(Charsets.UTF_8)

        // tunnel_server_id derived but clamped to 0 (Google) for v1.
        Hkdf.derive(ikm, ByteArray(0), label("bluesteal/tunnel-server-id"), 2)
        val tunnelServerId = 0

        val tunnelId = Hkdf.derive(ikm, ByteArray(0), label("bluesteal/tunnel-id"), 16)
        val psk = Hkdf.derive(ikm, ByteArray(0), label("bluesteal/psk"), 32)
        val seed = Hkdf.derive(ikm, ByteArray(0), label("bluesteal/responder-static"), 32)
        val responder = CableP256.deriveKeyFromSeed(seed)

        return TunnelKeys(tunnelServerId, tunnelId, psk, responder)
    }

    /** UTC minutes since the Unix epoch. */
    fun currentUtcMinute(): Long = System.currentTimeMillis() / 1000L / 60L

    /**
     * 16-byte rotating tunnel_id for a given UTC minute. Derived from the code
     * alone (salt = little-endian Int64 minute) so host and peer agree without
     * extra OOB data.
     */
    fun deriveTunnelId(code: String, minute: Long): ByteArray {
        val salt = ByteArray(8)
        for (i in 0 until 8) salt[i] = ((minute ushr (8 * i)) and 0xFF).toByte()
        return Hkdf.derive(code.toByteArray(Charsets.UTF_8), salt, label("bluesteal/tunnel-minute"), 16)
    }

    /** "BSTL1" + [0x7C,0x4A,0x9C] → "BSTL1-7C4A9C". */
    fun formatToken(code: String, routingId: ByteArray): String {
        require(routingId.size == 3) { "routingId must be 3 bytes" }
        return "$code-${routingId.hexUpper()}"
    }

    /** Parses an 11-char composite token into (code, 3-byte routingId). */
    fun parseToken(token: String): Pair<String, ByteArray>? {
        val t = token.trim().uppercase()
        val m = matchToken(t) ?: return null
        val routing = hexToBytes(m.second) ?: return null
        return Pair(m.first, routing)
    }

    /** True if the input looks like an 11-char tunnel token (vs a 5-char code). */
    fun isTunnelToken(input: String): Boolean = matchToken(input.trim().uppercase()) != null

    /** Validates `^[A-Z0-9]{5}-[0-9A-F]{6}$` (input already uppercased). */
    private fun matchToken(s: String): Pair<String, String>? {
        val parts = s.split("-")
        if (parts.size != 2) return null
        val code = parts[0]; val routing = parts[1]
        if (code.length != 5 || routing.length != 6) return null
        if (!code.all { tokenCodeChars.contains(it) }) return null
        if (!routing.all { tokenHexChars.contains(it) }) return null
        return Pair(code, routing)
    }
}
