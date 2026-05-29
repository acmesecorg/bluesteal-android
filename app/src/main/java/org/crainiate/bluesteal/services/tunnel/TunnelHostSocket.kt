package org.crainiate.bluesteal.services.tunnel

import org.crainiate.bluesteal.logging.RelayTracer

/**
 * Host-side (authenticator role) tunnel socket. Opens `/cable/new/<tid>` and
 * reads the server-assigned routing_id from the `X-caBLE-Routing-ID` response
 * header. Kotlin port of `TunnelHostSocket.swift`.
 */
class TunnelHostSocket(tracer: RelayTracer) : TunnelSocket(tracer) {
    @Volatile
    var routingId: ByteArray = ByteArray(0)
        private set

    companion object {
        suspend fun connect(host: String, tunnelId: ByteArray, tracer: RelayTracer): TunnelHostSocket {
            require(tunnelId.size == 16) { "tunnelId must be 16 bytes" }
            val socket = TunnelHostSocket(tracer)
            val url = "https://$host/cable/new/${tunnelId.hexUpper()}"

            tracer.log("tunnel", "ws_connecting", mapOf(
                "role" to "host", "host" to host, "tunnelId" to tunnelId.hexUpper()
            ))

            val response = socket.connect(url, origin = "wss://$host")
            val negotiated = response.header("Sec-WebSocket-Protocol") ?: ""
            if (negotiated != "fido.cable") {
                socket.close()
                throw TunnelException(TunnelError.SUBPROTOCOL_NOT_NEGOTIATED, "got '$negotiated'")
            }

            // Header lookup is case-insensitive; the server returns the value as
            // "X-Cable-Routing-Id".
            val routing = response.header("X-caBLE-Routing-ID")?.let { hexToBytes(it) }
            if (routing == null || routing.size != 3) {
                socket.close()
                throw TunnelException(TunnelError.MISSING_ROUTING_ID)
            }
            socket.routingId = routing

            tracer.log("tunnel", "ws_open", mapOf(
                "role" to "host", "routingId" to routing.hexUpper(), "url" to url
            ))
            return socket
        }
    }
}
