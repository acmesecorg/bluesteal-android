package org.crainiate.bluesteal.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF-SHA256, implemented over `HmacSHA256` so empty-IKM and
 * empty-salt cases behave identically to .NET's `HKDF.DeriveKey` and the
 * macOS `HKDFShim` (empty salt → HashLen zero bytes). Kotlin port of
 * `bluesteal-macos/bluesteal/Crypto/HKDFShim.swift`.
 */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val saltKey = if (salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(saltKey, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val out = ByteArray(length)
        var filled = 0
        var t = ByteArray(0)
        var counter = 1
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(prk, "HmacSHA256"))
        while (filled < length) {
            prkMac.reset()
            prkMac.update(t)
            prkMac.update(info)
            prkMac.update(counter.toByte())
            t = prkMac.doFinal()
            val take = minOf(t.size, length - filled)
            System.arraycopy(t, 0, out, filled, take)
            filled += take
            counter += 1
        }
        return out
    }
}
