package org.crainiate.bluesteal.services.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.crainiate.bluesteal.logging.RelayTracer
import java.util.concurrent.TimeUnit

enum class TunnelError(val message: String) {
    NO_RESPONSE("no HTTP response on WS upgrade"),
    SUBPROTOCOL_NOT_NEGOTIATED("fido.cable subprotocol not negotiated"),
    MISSING_ROUTING_ID("response missing/malformed X-caBLE-Routing-ID"),
    UNEXPECTED_TEXT_FRAME("unexpected non-binary WebSocket frame"),
    RACE_FAILED("all peer race candidates failed"),
    NOT_OPEN("socket not open"),
    SOCKET_CLOSED("tunnel socket closed"),
    HANDSHAKE_FAILED("Noise handshake failed"),
    DECRYPT_FAILED("Noise decrypt failed"),
    CRYPTER_LIMIT_EXCEEDED("Crypter sequence limit exceeded")
}

class TunnelException(val error: TunnelError, message: String? = null) :
    Exception(message ?: error.message)

/**
 * Base WebSocket channel for the caBLE tunnel transport, over OkHttp. OkHttp
 * delivers complete binary messages (no fragment reassembly), so inbound frames
 * are funnelled through an unbounded channel and pulled by a single consumer.
 * Kotlin port of `TunnelSocket.swift`.
 */
open class TunnelSocket(val tracer: RelayTracer) {
    @Volatile private var webSocket: WebSocket? = null
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val openDeferred = CompletableDeferred<Response>()
    @Volatile private var closed = false

    /** Opens the WebSocket; resolves once the upgrade completes, returning the
     *  HTTP 101 response so callers can read the routing-id header. */
    suspend fun connect(url: String, origin: String?): Response {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Sec-WebSocket-Protocol", "fido.cable")
        if (origin != null) builder.addHeader("Origin", origin)
        webSocket = sharedClient.newWebSocket(builder.build(), Listener())
        return openDeferred.await()
    }

    suspend fun send(data: ByteArray) {
        val ws = webSocket ?: throw TunnelException(TunnelError.NOT_OPEN)
        if (!ws.send(data.toByteString())) throw TunnelException(TunnelError.NOT_OPEN)
    }

    /** Returns the next complete binary message. Throws on close/error. */
    suspend fun receive(): ByteArray {
        val result = inbound.receiveCatching()
        result.getOrNull()?.let { return it }
        val cause = result.exceptionOrNull()
        throw (cause as? TunnelException) ?: TunnelException(TunnelError.SOCKET_CLOSED)
    }

    fun close() {
        closed = true
        webSocket?.let {
            it.close(1000, null)
            it.cancel()
        }
        inbound.close()
    }

    private fun ByteArray.toByteString(): ByteString = toByteString(0, size)

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!openDeferred.isCompleted) openDeferred.complete(response)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            inbound.trySend(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            inbound.close(TunnelException(TunnelError.UNEXPECTED_TEXT_FRAME))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            inbound.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!openDeferred.isCompleted) openDeferred.completeExceptionally(t)
            inbound.close(t)
        }
    }

    companion object {
        // No read timeout: the host socket can wait indefinitely for a peer
        // while in `.hosting`. No client pings: the transport runs its own
        // 2s application heartbeats once open, and server-side closes surface
        // via onClosed/onFailure.
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
