package org.crainiate.bluesteal.models

import java.time.Instant
import java.util.UUID

/**
 * One deduplicated record of an observed caBLE service-data advertisement.
 * Identity is `(peripheralID, payloadHex)` — same payload re-emitted by the
 * same peripheral folds into a single row, with [count] and [lastSeen] bumped.
 *
 * Note: Android's `BluetoothDevice` exposes a real MAC (or randomised MAC for
 * paired peripherals) rather than CoreBluetooth's per-app pseudo-UUID; the
 * scanner derives a UUID from the MAC so [peripheralID] keeps the same shape
 * across platforms.
 */
data class CableAdvert(
    val id: String,
    val peripheralID: UUID,
    /** "FFF9" / "FDE2" 16-bit short form, or null for manufacturer-data adverts. */
    val serviceUUID: String?,
    val payload: ByteArray,
    val variant: AdvertVariant,
    val firstSeen: Instant,
    var lastSeen: Instant,
    var count: Int,
    var rssi: Int,
    val localName: String?,
    val advertisedServiceUUIDs: List<String>,
    val manufacturerData: ByteArray?,
    val txPowerLevel: Int?,
    val isConnectable: Boolean?
) {
    val payloadHex: String get() = payload.toHexLower()
    val manufacturerHex: String? get() = manufacturerData?.toHexLower()

    companion object {
        fun makeID(peripheralID: UUID, payloadHex: String): String =
            "${peripheralID.toString().uppercase()}|$payloadHex"
    }

    // Custom equality so a ByteArray field doesn't break value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CableAdvert) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

/** Lowercase hex, matching Swift `Data.hexEncoded`. */
fun ByteArray.toHexLower(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX_LO[v ushr 4]).append(HEX_LO[v and 0x0f])
    }
    return sb.toString()
}

/** Parse a hex string back to bytes. Returns null on odd length / invalid digits. */
fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = hexNibble(this[i * 2]) ?: return null
        val lo = hexNibble(this[i * 2 + 1]) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun hexNibble(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> null
}

private val HEX_LO = "0123456789abcdef".toCharArray()
