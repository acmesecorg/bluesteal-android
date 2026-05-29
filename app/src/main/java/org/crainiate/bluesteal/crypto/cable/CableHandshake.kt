package org.crainiate.bluesteal.crypto.cable

/**
 * caBLE NKpsk0 handshake. Kotlin port of `Handshake.swift` / Windows
 * `HandshakeInitiator.cs` + `HandshakeResponder.cs`, restricted to the NKpsk0
 * paths bluesteal uses. Initiator = peer (holds the responder's static public
 * key); responder = host (holds its static private key).
 *
 * NOTE: deriving the responder static key from a shared secret deviates from
 * real NKpsk0's threat model — here Noise is used purely as an AEAD framing
 * layer, not for authenticator authentication.
 */
class HandshakeResult(val crypter: Crypter, val handshakeHash: ByteArray)

object CableHandshake {

    /** Initiator (peer). Stateful across [buildInitialMessage] / [processResponse]. */
    class Initiator(
        private val psk: ByteArray,
        private val peerIdentity: ByteArray,                 // responder static public key (X9.62)
        private val ephemeral: CableKey = CableP256.generateEphemeral()
    ) {
        private val noise = Noise()

        init {
            require(psk.size == 32)
            require(peerIdentity.size == CableP256.X962_LENGTH)
        }

        fun buildInitialMessage(): ByteArray {
            noise.initialize(HandshakeType.NKPSK0)
            noise.mixHash(byteArrayOf(0))
            noise.mixHash(peerIdentity)
            noise.mixKeyAndHash(psk)

            val ephPub = ephemeral.publicKeyX962
            noise.mixHash(ephPub)
            noise.mixKey(ephPub)

            val es = CableP256.computeSharedSecret(ephemeral, peerIdentity)
            noise.mixKey(es)

            val ct = noise.encryptAndHash(ByteArray(0))
            return ephPub + ct
        }

        fun processResponse(response: ByteArray): HandshakeResult? {
            if (response.size != CableP256.X962_LENGTH + 16) return null
            val peerPointBytes = response.copyOfRange(0, CableP256.X962_LENGTH)
            val ct = response.copyOfRange(CableP256.X962_LENGTH, response.size)

            val peerPoint = try {
                CableP256.importPublicKeyX962(peerPointBytes)
            } catch (e: Exception) {
                return null
            }
            val ee = CableP256.computeSharedSecret(ephemeral, peerPoint)

            noise.mixHash(peerPointBytes)
            noise.mixKey(peerPointBytes)
            noise.mixKey(ee)

            val pt = noise.decryptAndHash(ct) ?: return null
            if (pt.isNotEmpty()) return null

            val (writeKey, readKey) = noise.trafficKeys()
            return HandshakeResult(Crypter(readKey = readKey, writeKey = writeKey), noise.handshakeHash())
        }
    }

    class ResponderResult(val result: HandshakeResult, val response: ByteArray)

    /** Responder (host). */
    fun respond(
        psk: ByteArray,
        identity: CableKey,
        message: ByteArray,
        ephemeral: CableKey = CableP256.generateEphemeral()
    ): ResponderResult? {
        if (message.size < CableP256.X962_LENGTH) return null
        val peerPointBytes = message.copyOfRange(0, CableP256.X962_LENGTH)
        val ct = message.copyOfRange(CableP256.X962_LENGTH, message.size)

        val peerPoint = try {
            CableP256.importPublicKeyX962(peerPointBytes)
        } catch (e: Exception) {
            return null
        }

        val noise = Noise()
        noise.initialize(HandshakeType.NKPSK0)
        noise.mixHash(byteArrayOf(0))
        noise.mixHashPoint(identity.publicKeyX962)
        noise.mixKeyAndHash(psk)

        noise.mixHash(peerPointBytes)
        noise.mixKey(peerPointBytes)

        val es = CableP256.computeSharedSecret(identity, peerPoint)
        noise.mixKey(es)

        val pt = noise.decryptAndHash(ct) ?: return null
        if (pt.isNotEmpty()) return null

        val ourEphPub = ephemeral.publicKeyX962
        noise.mixHash(ourEphPub)
        noise.mixKey(ourEphPub)

        val ee = CableP256.computeSharedSecret(ephemeral, peerPoint)
        noise.mixKey(ee)

        val respCt = noise.encryptAndHash(ByteArray(0))
        val response = ourEphPub + respCt

        val (readKey, writeKey) = noise.trafficKeys()
        val result = HandshakeResult(Crypter(readKey = readKey, writeKey = writeKey), noise.handshakeHash())
        return ResponderResult(result, response)
    }
}
