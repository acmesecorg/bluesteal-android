package org.crainiate.bluesteal.services.tunnel

import org.crainiate.bluesteal.crypto.cable.CableHandshake
import org.crainiate.bluesteal.crypto.cable.Crypter
import org.crainiate.bluesteal.logging.RelayTracer

/**
 * Runs the Noise NKpsk0 handshake over an opened tunnel socket, then provides
 * encrypted send/receive. Kotlin port of `TunnelNoiseSession.swift`.
 *
 * NOTE: [Crypter] is stateful (sequence numbers) and NOT thread-safe; callers
 * must serialise send/receive on one thread.
 */
class TunnelNoiseSession private constructor(
    private val crypter: Crypter,
    private val socket: TunnelSocket,
    private val tracer: RelayTracer
) {
    suspend fun send(plaintext: ByteArray) {
        val ciphertext = crypter.encrypt(plaintext)
            ?: throw TunnelException(TunnelError.CRYPTER_LIMIT_EXCEEDED)
        socket.send(ciphertext)
    }

    /** Decrypts the next frame. Throws on decrypt failure or socket close. */
    suspend fun receive(): ByteArray {
        val frame = socket.receive()
        return crypter.decrypt(frame) ?: run {
            tracer.log("tunnel", "noise_decrypt_failed")
            throw TunnelException(TunnelError.DECRYPT_FAILED)
        }
    }

    fun close() = socket.close()

    companion object {
        /** Host-side (responder) handshake. */
        suspend fun handshakeAsHost(
            socket: TunnelHostSocket, keys: TunnelKeys, tracer: RelayTracer
        ): TunnelNoiseSession {
            tracer.log("tunnel", "noise_handshake_start", mapOf("role" to "host"))

            val initiatorMsg = socket.receive()
            val rr = CableHandshake.respond(keys.psk, keys.responderStatic, initiatorMsg)
                ?: throw TunnelException(TunnelError.HANDSHAKE_FAILED)
            socket.send(rr.response)

            tracer.log("tunnel", "noise_handshake_complete", mapOf("role" to "host"))
            return TunnelNoiseSession(rr.result.crypter, socket, tracer)
        }

        /** Peer-side (initiator) handshake. */
        suspend fun handshakeAsPeer(
            socket: TunnelPeerSocket, keys: TunnelKeys, tracer: RelayTracer
        ): TunnelNoiseSession {
            tracer.log("tunnel", "noise_handshake_start", mapOf("role" to "peer"))

            val initiator = CableHandshake.Initiator(keys.psk, keys.responderStatic.publicKeyX962)
            socket.send(initiator.buildInitialMessage())

            val responseMsg = socket.receive()
            val result = initiator.processResponse(responseMsg)
                ?: throw TunnelException(TunnelError.HANDSHAKE_FAILED)

            tracer.log("tunnel", "noise_handshake_complete", mapOf("role" to "peer"))
            return TunnelNoiseSession(result.crypter, socket, tracer)
        }
    }
}
