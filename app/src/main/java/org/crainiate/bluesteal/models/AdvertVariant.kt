package org.crainiate.bluesteal.models

/**
 * Classification of a single caBLE / FIDO hybrid advertisement service-data entry.
 *
 * Mirrors `bluesteal-macos/bluesteal/Models/AdvertVariant.swift` and Windows
 * `Bluesteal/Models/AdvertVariant.cs` exactly — `wireName` round-trips between
 * platforms via [RelayMessage.Advert.variant], so any divergence breaks
 * cross-platform pairing.
 */
sealed class AdvertVariant {
    /** FIDO caBLE v2: UUID 0xFFF9, 20-byte payload `AES-ECB(EID) ‖ HMAC[:4]`. */
    data object FidoV2 : AdvertVariant()
    /** Google caBLE v1: UUID 0xFDE2, 18-byte `[flags, version, EID16]` payload. */
    data object GoogleV1 : AdvertVariant()
    /** Google caBLE v2: UUID 0xFDE2, 20-byte payload (same shape as FidoV2). */
    data object GoogleV2 : AdvertVariant()
    /** Google caBLE v1 carried as manufacturer data (company id 0x00E0). */
    data object GoogleMfgV1 : AdvertVariant()
    /** Service data under a caBLE UUID but doesn't match a known shape. */
    data class Unknown(val reason: String) : AdvertVariant()

    val badge: String
        get() = when (this) {
            FidoV2 -> "FIDO-v2"
            GoogleV1 -> "Google-v1"
            GoogleV2 -> "Google-v2"
            GoogleMfgV1 -> "Google-Mfg"
            is Unknown -> "?"
        }

    /** Stable serialization key used on the JSONL log and the relay wire. */
    val wireName: String
        get() = when (this) {
            FidoV2 -> "fidoV2"
            GoogleV1 -> "googleV1"
            GoogleV2 -> "googleV2"
            GoogleMfgV1 -> "googleMfgV1"
            is Unknown -> "unknown($reason)"
        }

    companion object {
        fun fromWireName(name: String): AdvertVariant = when (name) {
            "fidoV2" -> FidoV2
            "googleV1" -> GoogleV1
            "googleV2" -> GoogleV2
            "googleMfgV1" -> GoogleMfgV1
            else -> Unknown(name)
        }
    }
}
