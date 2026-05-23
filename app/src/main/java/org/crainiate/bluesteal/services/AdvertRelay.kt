package org.crainiate.bluesteal.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.crainiate.bluesteal.models.AdvertVariant
import org.crainiate.bluesteal.models.CableAdvert
import org.crainiate.bluesteal.models.RelayAdvert
import org.crainiate.bluesteal.models.RelayHeartbeat
import org.crainiate.bluesteal.models.RelayMessage
import org.crainiate.bluesteal.models.hexToBytes
import org.crainiate.bluesteal.models.toHexLower
import java.time.Instant
import java.util.UUID

/**
 * Bridges the local [AdvertStore] with a paired [RelayClient].
 *
 * - **Outbound:** every locally ingested advert (`store.onIngest`) is forwarded
 *   to the peer.
 * - **Inbound:** adverts received from the peer are mirrored into a separate
 *   receive-side list so the UI can render them in a "From peer" section
 *   without touching the local list.
 * - **Heartbeats:** when the relay enters `.Open` we start a 4 s bidirectional
 *   heartbeat coroutine — both directions must keep the NAT mapping fresh,
 *   otherwise the silent side's UDP binding ages out and ICE consent-freshness
 *   tears the session down. See docs §10.
 *
 * Mirrors `bluemonday/bluesteal/Services/AdvertRelay.swift`.
 */
class AdvertRelay(
    private val store: AdvertStore,
    private val relay: RelayClient
) {
    private val _received = MutableStateFlow<List<CableAdvert>>(emptyList())
    /** Adverts received from the paired peer, newest-first. */
    val received: StateFlow<List<CableAdvert>> = _received.asStateFlow()

    private val _lastPeerHeartbeat = MutableStateFlow<Instant?>(null)
    val lastPeerHeartbeat: StateFlow<Instant?> = _lastPeerHeartbeat.asStateFlow()

    private val receivedIndex = HashMap<String, Int>()
    private val lock = Any()

    var onLocalIngest: ((CableAdvert) -> Unit)? = null
    var onReceived: ((CableAdvert) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null

    init {
        wireUp()
    }

    private fun wireUp() {
        // Outbound.
        store.onIngest = { advert ->
            relay.send(RelayMessage.Advert(advert.toWire()))
            onLocalIngest?.invoke(advert)
        }
        // Inbound.
        relay.onMessage = { message -> handleInbound(message) }
        // Heartbeats — start on .Open, stop on anything else.
        relay.addStateListener { newState ->
            if (newState is RelayClient.State.Open) startHeartbeats()
            else stopHeartbeats()
        }
    }

    fun startHeartbeats() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(4_000)
                if (relay.state.value !is RelayClient.State.Open) break
                val ids = store.adverts.value.map { it.id }
                relay.send(RelayMessage.Heartbeat(
                    RelayHeartbeat(ts = Instant.now().toString(), knownIds = ids)
                ))
            }
        }
    }

    fun stopHeartbeats() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun handleInbound(message: RelayMessage) {
        when (message) {
            is RelayMessage.Advert -> {
                val advert = message.payload.toCableAdvert() ?: return
                synchronized(lock) {
                    val list = _received.value.toMutableList()
                    val existing = receivedIndex[advert.id]
                    if (existing != null) {
                        list[existing] = advert
                    } else {
                        list.add(0, advert)
                        rebuildReceivedIndex(list)
                    }
                    _received.value = list
                }
                onReceived?.invoke(advert)
            }
            is RelayMessage.Heartbeat -> {
                _lastPeerHeartbeat.value = runCatching { Instant.parse(message.payload.ts) }
                    .getOrNull() ?: Instant.now()
            }
            is RelayMessage.Bye -> {
                // RelayClient tears down on bye; nothing to do here.
            }
        }
    }

    private fun rebuildReceivedIndex(list: List<CableAdvert>) {
        receivedIndex.clear()
        for ((i, a) in list.withIndex()) receivedIndex[a.id] = i
    }
}

// ---------- CableAdvert ⇄ RelayAdvert ----------

internal fun CableAdvert.toWire(): RelayAdvert {
    // CoreBluetooth surfaces manufacturer data with a two-byte little-endian
    // company-ID prefix. Surface it as a discrete field so the Windows peer
    // doesn't have to re-parse `manufacturerHex` to recover the company ID.
    val mfgId: Int? = manufacturerData?.takeIf { it.size >= 2 }?.let {
        (it[0].toInt() and 0xff) or ((it[1].toInt() and 0xff) shl 8)
    }
    return RelayAdvert(
        id = id,
        ts = lastSeen.toString(),
        peripheralID = peripheralID.toString().uppercase(),
        serviceUUID = serviceUUID,
        payloadHex = payloadHex,
        variant = variant.wireName,
        rssi = rssi,
        count = count,
        firstSeen = firstSeen.toString(),
        lastSeen = lastSeen.toString(),
        localName = localName,
        manufacturerHex = manufacturerHex,
        manufacturerId = mfgId,
        advertisedServiceUUIDs = advertisedServiceUUIDs.takeIf { it.isNotEmpty() },
        txPowerLevel = txPowerLevel,
        isConnectable = isConnectable
    )
}

internal fun RelayAdvert.toCableAdvert(): CableAdvert? {
    val uuid = runCatching { UUID.fromString(peripheralID) }.getOrNull() ?: return null
    val payload = payloadHex.hexToBytes() ?: return null
    // Defensive: an older Windows peer (pre-Phase-1 schema) sends "0000" as a
    // placeholder for "no service UUID" instead of omitting the field. Treat
    // it as null so the row doesn't end up with a phantom 0x0000 UUID.
    val service = serviceUUID?.takeIf { it != "0000" }
    val mfg = manufacturerHex?.hexToBytes()
    val firstSeen = runCatching { Instant.parse(firstSeen) }.getOrNull() ?: Instant.now()
    val lastSeen = runCatching { Instant.parse(lastSeen) }.getOrNull() ?: Instant.now()
    return CableAdvert(
        id = id,
        peripheralID = uuid,
        serviceUUID = service,
        payload = payload,
        variant = AdvertVariant.fromWireName(variant),
        firstSeen = firstSeen,
        lastSeen = lastSeen,
        count = count,
        rssi = rssi,
        localName = localName,
        advertisedServiceUUIDs = advertisedServiceUUIDs.orEmpty(),
        manufacturerData = mfg,
        txPowerLevel = txPowerLevel,
        isConnectable = isConnectable
    )
}
