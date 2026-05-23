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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Messages exchanged through the public MQTT broker during the ~5 s pairing
 * handshake. Topics live under `bluesteal/v1/<code>/`.
 *
 * Wire shape (must match Swift / Windows exactly):
 *
 * - offer:  `{"type":"offer",  "payload":{"sdp":"…"}}`
 * - answer: `{"type":"answer", "payload":{"sdp":"…"}}`
 * - ice:    `{"type":"ice",    "payload":{"sdp":"…","sdpMid":"…","sdpMLineIndex":0}}`
 * - bye:    `{"type":"bye",    "reason":"…"}`
 */
@Serializable(with = SignalingMessageSerializer::class)
sealed class SignalingMessage {
    data class Offer(val sdp: String) : SignalingMessage()
    data class Answer(val sdp: String) : SignalingMessage()
    data class Ice(val candidate: IceCandidate) : SignalingMessage()
    data class Bye(val reason: String) : SignalingMessage()

    @Serializable
    data class IceCandidate(
        val sdp: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int
    )
}

internal object SignalingMessageSerializer : KSerializer<SignalingMessage> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("SignalingMessage")

    override fun serialize(encoder: Encoder, value: SignalingMessage) {
        val jsonEnc = encoder as? JsonEncoder
            ?: error("SignalingMessage requires JSON encoder")
        val json = jsonEnc.json
        val obj = when (value) {
            is SignalingMessage.Offer -> buildJsonObject {
                put("type", JsonPrimitive("offer"))
                put("payload", buildJsonObject { put("sdp", JsonPrimitive(value.sdp)) })
            }
            is SignalingMessage.Answer -> buildJsonObject {
                put("type", JsonPrimitive("answer"))
                put("payload", buildJsonObject { put("sdp", JsonPrimitive(value.sdp)) })
            }
            is SignalingMessage.Ice -> buildJsonObject {
                put("type", JsonPrimitive("ice"))
                put("payload", json.encodeToJsonElement(SignalingMessage.IceCandidate.serializer(), value.candidate))
            }
            is SignalingMessage.Bye -> buildJsonObject {
                put("type", JsonPrimitive("bye"))
                put("reason", JsonPrimitive(value.reason))
            }
        }
        jsonEnc.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): SignalingMessage {
        val jsonDec = decoder as? JsonDecoder
            ?: error("SignalingMessage requires JSON decoder")
        val json = jsonDec.json
        val obj = jsonDec.decodeJsonElement().jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?: error("SignalingMessage missing 'type'")
        return when (type) {
            "offer" -> SignalingMessage.Offer(
                sdp = obj["payload"]?.jsonObject?.get("sdp")?.jsonPrimitive?.contentOrNull
                    ?: error("offer missing payload.sdp")
            )
            "answer" -> SignalingMessage.Answer(
                sdp = obj["payload"]?.jsonObject?.get("sdp")?.jsonPrimitive?.contentOrNull
                    ?: error("answer missing payload.sdp")
            )
            "ice" -> {
                val payload = obj["payload"] ?: error("ice missing payload")
                SignalingMessage.Ice(
                    json.decodeFromJsonElement(SignalingMessage.IceCandidate.serializer(), payload)
                )
            }
            "bye" -> SignalingMessage.Bye(
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            )
            else -> error("Unknown SignalingMessage type: $type")
        }
    }
}

/** Tiny helper so call sites don't have to repeat the type tag pretty-printer. */
internal fun SignalingMessage.typeName(): String = when (this) {
    is SignalingMessage.Offer -> "offer"
    is SignalingMessage.Answer -> "answer"
    is SignalingMessage.Ice -> "ice"
    is SignalingMessage.Bye -> "bye"
}
