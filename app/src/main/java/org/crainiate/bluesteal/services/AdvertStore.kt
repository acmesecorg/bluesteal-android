package org.crainiate.bluesteal.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.crainiate.bluesteal.logging.JsonlLogger
import org.crainiate.bluesteal.models.AdvertVariant
import org.crainiate.bluesteal.models.CableAdvert
import org.crainiate.bluesteal.models.toHexLower
import java.time.Instant

/**
 * Aggregates raw scanner events into a deduped, ordered list of [CableAdvert]
 * and appends every detection to the JSONL log.
 *
 * Mirrors `bluesteal-macos/bluesteal/Services/AdvertStore.swift` and Windows
 * `Bluesteal/Services/AdvertStore.cs`.
 */
class AdvertStore(private val logger: JsonlLogger) {

    private val _adverts = MutableStateFlow<List<CableAdvert>>(emptyList())
    val adverts: StateFlow<List<CableAdvert>> = _adverts.asStateFlow()

    private val _totalDetections = MutableStateFlow(0)
    val totalDetections: StateFlow<Int> = _totalDetections.asStateFlow()

    /**
     * Count of scanner events suppressed because they matched a payload we
     * are currently re-broadcasting ourselves. Surfaced for diagnostics only.
     */
    private val _selfEchoesDropped = MutableStateFlow(0)
    val selfEchoesDropped: StateFlow<Int> = _selfEchoesDropped.asStateFlow()

    /**
     * Called for every fresh-or-updated advert after the store mutates. The
     * relay bridge uses this to forward live state to a paired instance
     * without polling.
     */
    var onIngest: ((CableAdvert) -> Unit)? = null

    /**
     * Loop-guard hook. When set, every incoming scanner-classified payload is
     * tested against this closure; matches are dropped before upsert.
     * `RebroadcastService` registers a closure pointing at its own
     * self-published set so we don't ingest, render, or relay echoes of
     * adverts we're emitting ourselves.
     */
    var selfPayloadFilter: ((String) -> Boolean)? = null

    private val lock = Any()
    private val index = HashMap<String, Int>()    // CableAdvert.id -> array index

    fun ingest(event: RawAdvertEvent) {
        // Service-data path: one row per caBLE service-data entry (FFF9 / FDE2).
        for ((parcelUuid, payload) in event.serviceData) {
            if (!AdvertClassifier.isCableUuid(parcelUuid)) continue
            val hex = payload.toHexLower()
            if (selfPayloadFilter?.invoke(hex) == true) {
                _selfEchoesDropped.update { it + 1 }
                continue
            }
            val variant = AdvertClassifier.classify(parcelUuid, payload)
            val short = AdvertClassifier.toShortForm(parcelUuid)
            upsert(payload = payload, variant = variant, serviceUuid = short, event = event)
        }

        // Manufacturer-data path: Google caBLE v1 advert under company ID 0x00E0.
        event.manufacturerData?.let { blob ->
            val (variant, eid) = AdvertClassifier.classifyManufacturerData(blob) ?: return@let
            val hex = eid.toHexLower()
            if (selfPayloadFilter?.invoke(hex) == true) {
                _selfEchoesDropped.update { it + 1 }
                return@let
            }
            upsert(payload = eid, variant = variant, serviceUuid = null, event = event)
        }
    }

    private fun upsert(
        payload: ByteArray,
        variant: AdvertVariant,
        serviceUuid: String?,
        event: RawAdvertEvent
    ) {
        val payloadHex = payload.toHexLower()
        val id = CableAdvert.makeID(event.peripheralID, payloadHex)
        _totalDetections.update { it + 1 }

        val emitted: CableAdvert
        synchronized(lock) {
            val list = _adverts.value.toMutableList()
            val existing = index[id]
            if (existing != null) {
                val row = list[existing].copy(
                    lastSeen = Instant.ofEpochMilli(event.timestampMillis),
                    count = list[existing].count + 1,
                    rssi = event.rssi
                )
                list[existing] = row
                emitted = row
            } else {
                val ts = Instant.ofEpochMilli(event.timestampMillis)
                val advert = CableAdvert(
                    id = id,
                    peripheralID = event.peripheralID,
                    serviceUUID = serviceUuid,
                    payload = payload,
                    variant = variant,
                    firstSeen = ts,
                    lastSeen = ts,
                    count = 1,
                    rssi = event.rssi,
                    localName = event.localName,
                    advertisedServiceUUIDs = event.advertisedServiceUUIDs,
                    manufacturerData = event.manufacturerData,
                    txPowerLevel = event.txPowerLevel,
                    isConnectable = event.isConnectable
                )
                list.add(0, advert)
                rebuildIndex(list)
                emitted = advert
            }
            _adverts.value = list
        }
        logger.append(event, serviceUuid, payload, variant)
        onIngest?.invoke(emitted)
    }

    fun clear() {
        synchronized(lock) {
            _adverts.value = emptyList()
            index.clear()
        }
        _totalDetections.value = 0
    }

    private fun rebuildIndex(list: List<CableAdvert>) {
        index.clear()
        for ((i, a) in list.withIndex()) index[a.id] = i
    }
}
