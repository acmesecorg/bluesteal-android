package org.crainiate.bluesteal.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.crainiate.bluesteal.logging.RelayTracer
import org.crainiate.bluesteal.models.AdvertVariant
import org.crainiate.bluesteal.models.CableAdvert
import org.crainiate.bluesteal.models.RelayAdvert
import org.crainiate.bluesteal.models.RelayMessage
import org.crainiate.bluesteal.models.hexToBytes
import java.time.Instant
import java.util.UUID

/**
 * Bridges the local [AdvertStore] with the paired peer over the caBLE tunnel.
 *
 * - **Outbound:** every locally ingested advert (`store.onIngest`) is serialised
 *   and forwarded to the peer through [tunnelSend].
 * - **Inbound:** frames arriving from the tunnel ([handleTunnelFrame]) are
 *   deserialised and mirrored into a separate receive-side list so the UI can
 *   render them in a "From peer" section without touching the local list.
 *
 * The tunnel runs its own heartbeat keep-alive, so there is no heartbeat loop
 * here. Mirrors `bluesteal-macos/bluesteal/Services/AdvertRelay.swift`.
 */
class AdvertRelay(
    private val store: AdvertStore,
    private val tracer: RelayTracer? = null
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

    /**
     * Outbound adverts are serialised to JSON and sent through this closure
     * (the caBLE tunnel transport). Set by the view / app once the tunnel is
     * connected; null means not connected, so adverts are not forwarded.
     * Mirrors macOS `AdvertRelay.tunnelSend` / Windows `AdvertRelay.TunnelSend`.
     */
    var tunnelSend: ((ByteArray) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    init {
        wireUp()
    }

    private fun wireUp() {
        // Outbound — forward to the peer over the tunnel when connected.
        store.onIngest = { advert ->
            tunnelSend?.let { send ->
                val msg = RelayMessage.Advert(advert.toWire())
                val bytes = runCatching {
                    json.encodeToString(RelayMessage.serializer(), msg).toByteArray(Charsets.UTF_8)
                }.getOrNull()
                if (bytes != null) send(bytes)
            }
            onLocalIngest?.invoke(advert)
        }
    }

    /**
     * Decodes a raw JSON frame delivered by the tunnel transport and routes it
     * through the inbound path — so the received list and re-broadcast keep
     * working unchanged. Mirrors macOS `AdvertRelay.handleTunnelFrame`.
     */
    fun handleTunnelFrame(data: ByteArray) {
        val message = runCatching {
            json.decodeFromString(RelayMessage.serializer(), String(data, Charsets.UTF_8))
        }.getOrNull() ?: return
        handleInbound(message)
    }

    private fun handleInbound(message: RelayMessage) {
        when (message) {
            is RelayMessage.Advert -> {
                val advert = message.payload.toCableAdvert() ?: return
                tracer?.log("relay", "relay.advert.recv", mapOf(
                    "id" to advert.id,
                    "variant" to advert.variant.wireName,
                    "payloadHexPrefix" to advert.payloadHex.take(16),
                    "rssi" to advert.rssi
                ))
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
                // The tunnel transport tears itself down on bye; nothing to do here.
            }
            is RelayMessage.HandoffOffer, is RelayMessage.HandoffAck -> {
                // Tunnel-transport control frames; handled inside TunnelTransport,
                // never surfaced on this inbound path.
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
