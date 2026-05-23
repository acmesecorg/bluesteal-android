package org.crainiate.bluesteal.services

import android.os.ParcelUuid
import org.crainiate.bluesteal.models.AdvertVariant
import java.util.UUID

/**
 * Pure classification: given a service-data UUID and its raw bytes, return the
 * caBLE variant. Mirrors `bluesteal-macos/bluesteal/Services/AdvertClassifier.swift`
 * exactly — see Chromium `fido_cable_discovery.cc` and `v2_constants.h`
 * (`kAdvertSize = 16 + 4`).
 */
object AdvertClassifier {
    /** 16-bit FIDO caBLE service UUID (`0xFFF9`). */
    val fidoCableUuid: ParcelUuid = ParcelUuid.fromString("0000FFF9-0000-1000-8000-00805F9B34FB")
    /** 16-bit Google caBLE service UUID (`0xFDE2`). */
    val googleCableUuid: ParcelUuid = ParcelUuid.fromString("0000FDE2-0000-1000-8000-00805F9B34FB")

    val cableUuids: List<ParcelUuid> = listOf(fidoCableUuid, googleCableUuid)

    /** "FFF9" / "FDE2" — the short forms macOS / iOS emit in [RelayAdvert.serviceUUID]. */
    const val FIDO_CABLE_SHORT = "FFF9"
    const val GOOGLE_CABLE_SHORT = "FDE2"

    /** Google Bluetooth SIG company identifier. Used for the manufacturer-data variant. */
    const val GOOGLE_COMPANY_ID = 0x00E0

    fun isCableUuid(uuid: ParcelUuid): Boolean =
        uuid == fidoCableUuid || uuid == googleCableUuid

    fun isCableUuid(uuid: UUID): Boolean = isCableUuid(ParcelUuid(uuid))

    /**
     * Convert a [ParcelUuid] to its canonical short form (`"FFF9"`, `"FDE2"`)
     * when it falls under the Bluetooth-base UUID, otherwise return the full
     * upper-cased string.
     */
    fun toShortForm(uuid: ParcelUuid): String {
        val s = uuid.uuid.toString().lowercase()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            s.substring(4, 8).uppercase()
        } else {
            s.uppercase()
        }
    }

    fun classify(serviceUuid: ParcelUuid, payload: ByteArray): AdvertVariant {
        val n = payload.size
        return when (serviceUuid) {
            fidoCableUuid ->
                if (n == 20) AdvertVariant.FidoV2
                else AdvertVariant.Unknown("fff9 len=$n")
            googleCableUuid -> {
                val flags = if (n > 0) payload[0].toInt() and 0xff else 0
                when {
                    n == 18 && ((flags shr 5) and 1) == 1 -> AdvertVariant.GoogleV1
                    n == 20 -> AdvertVariant.GoogleV2
                    else -> AdvertVariant.Unknown("fde2 len=$n")
                }
            }
            else -> AdvertVariant.Unknown("non-cable uuid ${serviceUuid.uuid}")
        }
    }

    /**
     * Recognise Google's manufacturer-data caBLE v1 advert.
     *
     * The macOS port surfaces the raw manufacturer-data blob *with* its
     * two-byte little-endian company-ID prefix, so the classifier expects:
     * ```
     * [00 E0] [14 15 20 01] [16-byte EID]   // 22 bytes
     *  co.id   len  ty  fl  ver
     * ```
     *
     * On Android the platform already strips the company-ID prefix before
     * handing data to scan callbacks, so the scanner prepends those two bytes
     * before invoking this method. That keeps the wire format identical
     * across all four platforms.
     */
    fun classifyManufacturerData(data: ByteArray): Pair<AdvertVariant, ByteArray>? {
        if (data.size != 22) return null
        val companyId = (data[0].toInt() and 0xff) or ((data[1].toInt() and 0xff) shl 8)
        if (companyId != GOOGLE_COMPANY_ID) return null
        if (data[2].toInt() and 0xff != 20) return null
        if (data[3].toInt() and 0xff != 0x15) return null
        if (data[4].toInt() and 0xff != 0x20) return null
        if (data[5].toInt() and 0xff != 0x01) return null
        return AdvertVariant.GoogleMfgV1 to data.copyOfRange(6, 22)
    }
}
