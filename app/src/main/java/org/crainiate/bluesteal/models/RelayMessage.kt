package org.crainiate.bluesteal.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-wire messages exchanged through the WebRTC data channel once pairing
 * completes. Codable JSON; one message per channel send.
 *
 * Wire shape (tagged union, must match
 * `bluesteal-macos/bluesteal/Models/RelayMessage.swift` and Windows
 * `Bluesteal/Models/RelayMessage.cs` exactly):
 *
 * - advert:    `{"type":"advert",    "payload": RelayAdvert}`
 * - heartbeat: `{"type":"heartbeat", "payload": RelayHeartbeat}`
 * - bye:       `{"type":"bye",       "reason":  "..."}`
 *
 * Note the inconsistency: `bye` carries its argument at the top level under
 * `reason`, while advert/heartbeat nest theirs under `payload`. Mirrors the
 * Swift custom `Codable` implementation exactly.
 *
 * Payload data classes ([RelayAdvert], [RelayHeartbeat]) are top-level so they
 * don't collide with the sealed-class variant names — Kotlin disallows a nested
 * data class sharing its outer sealed subclass's identifier.
 */
@Serializable(with = RelayMessageSerializer::class)
sealed class RelayMessage {
    data class Advert(val payload: RelayAdvert) : RelayMessage()
    data class Heartbeat(val payload: RelayHeartbeat) : RelayMessage()
    data class Bye(val reason: String) : RelayMessage()

    /**
     * caBLE tunnel make-before-break handoff control messages. Only used by the
     * tunnel transport; the WebRTC path never emits or receives these.
     */
    data class HandoffOffer(val payload: RelayHandoffOffer) : RelayMessage()
    data class HandoffAck(val payload: RelayHandoffAck) : RelayMessage()
}

/** Lossy projection of a [CableAdvert] for the wire. */
@Serializable
data class RelayAdvert(
    val id: String,
    val ts: String,                          // ISO-8601 fractional seconds
    val peripheralID: String,                // UUID string (uppercase)
    val serviceUUID: String? = null,         // "FFF9" / "FDE2", null for mfg-data
    val payloadHex: String,
    val variant: String,                     // [AdvertVariant.wireName]
    val rssi: Int,
    val count: Int,
    val firstSeen: String,
    val lastSeen: String,
    val localName: String? = null,
    val manufacturerHex: String? = null,
    /**
     * Bluetooth SIG company identifier (e.g. 0x00E0 for Google) when this
     * advert was carried in manufacturer data. Mirrors Windows
     * `RelayAdvert.ManufacturerId`.
     */
    val manufacturerId: Int? = null,
    val advertisedServiceUUIDs: List<String>? = null,
    val txPowerLevel: Int? = null,
    val isConnectable: Boolean? = null
)

@Serializable
data class RelayHeartbeat(
    val ts: String,
    val knownIds: List<String>
)

/**
 * Host announces the next hop's routing_id + minute over the current tunnel.
 * Promotion still requires an ack — [validFrom] is advisory.
 */
@Serializable
data class RelayHandoffOffer(
    val nextRoutingId: String,    // 6 hex chars
    val nextMinute: Long,         // peer derives next tunnel_id from (code, nextMinute)
    val validFrom: String         // ISO-8601
)

/**
 * Peer's reply: [connected] true iff its NKpsk0 on the new hop succeeded.
 * [nextRoutingId] is the correlation key across overlapping hops.
 */
@Serializable
data class RelayHandoffAck(
    val nextRoutingId: String,
    val connected: Boolean
)

/**
 * Custom serializer for the tagged-union format. Kotlinx-serialization's
 * built-in polymorphism wants a flat discriminator (`{"type":"…", …flat…}`)
 * but the canonical Swift/C# wire format nests payloads — so we hand-roll.
 */
internal object RelayMessageSerializer : KSerializer<RelayMessage> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("RelayMessage")

    override fun serialize(encoder: Encoder, value: RelayMessage) {
        val jsonEnc = encoder as? JsonEncoder
            ?: error("RelayMessage requires JSON encoder")
        val json = jsonEnc.json
        val obj = when (value) {
            is RelayMessage.Advert -> buildJsonObject {
                put("type", JsonPrimitive("advert"))
                put("payload", json.encodeToJsonElement(RelayAdvert.serializer(), value.payload))
            }
            is RelayMessage.Heartbeat -> buildJsonObject {
                put("type", JsonPrimitive("heartbeat"))
                put("payload", json.encodeToJsonElement(RelayHeartbeat.serializer(), value.payload))
            }
            is RelayMessage.Bye -> buildJsonObject {
                put("type", JsonPrimitive("bye"))
                put("reason", JsonPrimitive(value.reason))
            }
            is RelayMessage.HandoffOffer -> buildJsonObject {
                put("type", JsonPrimitive("handoffOffer"))
                put("payload", json.encodeToJsonElement(RelayHandoffOffer.serializer(), value.payload))
            }
            is RelayMessage.HandoffAck -> buildJsonObject {
                put("type", JsonPrimitive("handoffAck"))
                put("payload", json.encodeToJsonElement(RelayHandoffAck.serializer(), value.payload))
            }
        }
        jsonEnc.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): RelayMessage {
        val jsonDec = decoder as? JsonDecoder
            ?: error("RelayMessage requires JSON decoder")
        val json = jsonDec.json
        val obj = jsonDec.decodeJsonElement().jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: error("RelayMessage missing 'type'")
        return when (type) {
            "advert" -> {
                val payload = obj["payload"] ?: error("advert missing payload")
                RelayMessage.Advert(json.decodeFromJsonElement(RelayAdvert.serializer(), payload))
            }
            "heartbeat" -> {
                val payload = obj["payload"] ?: error("heartbeat missing payload")
                RelayMessage.Heartbeat(json.decodeFromJsonElement(RelayHeartbeat.serializer(), payload))
            }
            "bye" -> {
                val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                RelayMessage.Bye(reason)
            }
            "handoffOffer" -> {
                val payload = obj["payload"] ?: error("handoffOffer missing payload")
                RelayMessage.HandoffOffer(json.decodeFromJsonElement(RelayHandoffOffer.serializer(), payload))
            }
            "handoffAck" -> {
                val payload = obj["payload"] ?: error("handoffAck missing payload")
                RelayMessage.HandoffAck(json.decodeFromJsonElement(RelayHandoffAck.serializer(), payload))
            }
            else -> error("Unknown RelayMessage type: $type")
        }
    }
}

/** Pretty type tag for tracer events. */
internal fun RelayMessage.typeName(): String = when (this) {
    is RelayMessage.Advert -> "advert"
    is RelayMessage.Heartbeat -> "heartbeat"
    is RelayMessage.Bye -> "bye"
    is RelayMessage.HandoffOffer -> "handoffOffer"
    is RelayMessage.HandoffAck -> "handoffAck"
}
