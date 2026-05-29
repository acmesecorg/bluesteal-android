package org.crainiate.bluesteal.services.tunnel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.crainiate.bluesteal.logging.RelayTracer

/**
 * Peer-side (consumer/Chromium role) tunnel socket. Opens
 * `/cable/connect/<routing>/<tid>`. On initial join it races ±2-minute
 * tunnel_id candidates in parallel; first successful upgrade wins, losers are
 * closed. Kotlin port of `TunnelPeerSocket.swift`.
 */
class TunnelPeerSocket(tracer: RelayTracer) : TunnelSocket(tracer) {

    companion object {
        suspend fun connect(
            host: String, routingId: ByteArray, tunnelId: ByteArray, tracer: RelayTracer
        ): TunnelPeerSocket {
            require(routingId.size == 3) { "routingId must be 3 bytes" }
            require(tunnelId.size == 16) { "tunnelId must be 16 bytes" }

            val socket = TunnelPeerSocket(tracer)
            val url = "https://$host/cable/connect/${routingId.hexUpper()}/${tunnelId.hexUpper()}"

            tracer.log("tunnel", "ws_connecting", mapOf("role" to "peer", "url" to url))

            // Peer mirrors Chromium: no Origin header.
            val response = socket.connect(url, origin = null)
            val negotiated = response.header("Sec-WebSocket-Protocol") ?: ""
            if (negotiated != "fido.cable") {
                socket.close()
                throw TunnelException(TunnelError.SUBPROTOCOL_NOT_NEGOTIATED, "got '$negotiated'")
            }

            tracer.log("tunnel", "ws_open", mapOf("role" to "peer", "url" to url))
            return socket
        }

        /**
         * Races N candidate tunnel_ids in parallel; first successful WebSocket
         * open wins. Throws if every candidate fails.
         */
        suspend fun connectRace(
            host: String, routingId: ByteArray, candidateTunnelIds: List<ByteArray>, tracer: RelayTracer
        ): TunnelPeerSocket {
            if (candidateTunnelIds.isEmpty()) throw TunnelException(TunnelError.RACE_FAILED)

            tracer.log("tunnel", "peer.race.start", mapOf(
                "n" to candidateTunnelIds.size, "routingId" to routingId.hexUpper()
            ))

            return coroutineScope {
                val results = Channel<Pair<Int, TunnelPeerSocket?>>(Channel.UNLIMITED)
                candidateTunnelIds.forEachIndexed { i, tid ->
                    launch {
                        val s = try {
                            connect(host, routingId, tid, tracer)
                        } catch (e: Exception) {
                            tracer.log("tunnel", "peer.candidate.failed", mapOf(
                                "candidateIndex" to i,
                                "tunnelId" to tid.hexUpper(),
                                "error" to e.toString()
                            ))
                            null
                        }
                        results.trySend(i to s)
                    }
                }

                var winner: TunnelPeerSocket? = null
                val losers = mutableListOf<TunnelPeerSocket>()
                repeat(candidateTunnelIds.size) {
                    val (idx, sock) = results.receive()
                    if (sock != null) {
                        if (winner == null) {
                            winner = sock
                            tracer.log("tunnel", "peer.race.win", mapOf("candidateIndex" to idx))
                        } else {
                            tracer.log("tunnel", "peer.race.cancel",
                                mapOf("candidateIndex" to idx, "reason" to "loser"))
                            losers.add(sock)
                        }
                    }
                }
                losers.forEach { it.close() }

                winner ?: run {
                    tracer.log("tunnel", "peer.race.failed")
                    throw TunnelException(TunnelError.RACE_FAILED)
                }
            }
        }
    }
}
