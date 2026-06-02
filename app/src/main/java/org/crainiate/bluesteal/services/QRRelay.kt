package org.crainiate.bluesteal.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.crainiate.bluesteal.logging.RelayTracer
import org.crainiate.bluesteal.models.RelayMessage
import org.crainiate.bluesteal.models.RelayQr
import org.crainiate.bluesteal.services.tunnel.TunnelTransport
import java.nio.charset.StandardCharsets

/**
 * Scan-and-share: relays a scanned `FIDO:/` QR string to the paired peer over
 * the caBLE tunnel (outbound), and surfaces a QR received from the peer for
 * display in a modal (inbound). Carries the QR string only; it does not parse
 * or act on the FIDO payload. Mirrors `bluesteal-ios` `QRRelay`.
 */
class QRRelay(
    private val tunnel: TunnelTransport,
    private val tracer: RelayTracer
) {
    private val _received = MutableStateFlow<String?>(null)
    /** A QR received from the peer to display (drives the receive modal). */
    val received: StateFlow<String?> = _received.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Encode a scanned `FIDO:/` URI as a [RelayMessage.Qr] and send it to the peer. */
    fun send(uri: String) {
        val bytes = runCatching {
            json.encodeToString(RelayMessage.serializer(), RelayMessage.Qr(RelayQr(uri)))
                .toByteArray(StandardCharsets.UTF_8)
        }.getOrNull() ?: return
        tracer.log("qr", "qr.send", mapOf("len" to uri.length))
        tunnel.send(bytes)
    }

    /**
     * Try to consume an inbound tunnel frame. Returns true if it was a QR
     * message (surfaced for display); false otherwise so the caller can route
     * it down the normal advert path.
     */
    fun tryHandle(data: ByteArray): Boolean {
        val msg = runCatching {
            json.decodeFromString(RelayMessage.serializer(), String(data, StandardCharsets.UTF_8))
        }.getOrNull() ?: return false
        if (msg is RelayMessage.Qr) {
            tracer.log("qr", "qr.recv", mapOf("len" to msg.payload.uri.length))
            _received.value = msg.payload.uri
            return true
        }
        return false
    }

    /** Surface a URI for local display (used by the receive modal + test hooks). */
    fun show(uri: String) { _received.value = uri }

    fun dismiss() { _received.value = null }
}
