package org.crainiate.bluesteal.services.tunnel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.crainiate.bluesteal.crypto.cable.TunnelServerDomain
import org.crainiate.bluesteal.logging.RelayTracer
import org.crainiate.bluesteal.models.RelayHandoffAck
import org.crainiate.bluesteal.models.RelayHandoffOffer
import org.crainiate.bluesteal.models.RelayHeartbeat
import org.crainiate.bluesteal.models.RelayMessage
import org.crainiate.bluesteal.services.RelayRole
import org.crainiate.bluesteal.services.RelayState
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * caBLE tunnel side-channel transport with make-before-break handoff. Kotlin
 * port of `bluesteal-macos/.../Services/Tunnel/TunnelTransport.swift` — the
 * full dual-role version (Android can both host and join, unlike iOS).
 *
 * Every 30 s (host only) the next hop is opened in parallel and promotion is
 * negotiated with the peer so the session survives the relay's ~60 s socket
 * cap. Promotion is never time-triggered — it requires an ack (host) or the
 * first post-ack frame (peer); timers only abort stuck pending hops.
 *
 * All state is confined to a single-threaded dispatcher (the analogue of the
 * macOS `@MainActor`), so [Crypter] sequence numbers and the hop fields stay
 * single-threaded. Socket/Noise awaits suspend that thread without blocking it.
 */
class TunnelTransport(private val tracer: RelayTracer) {

    private enum class HopState { STEADY, PENDING_ACK, AWAITING_FIRST_FRAME }

    private inner class Hop(val session: TunnelNoiseSession, val routingId: ByteArray) {
        var readJob: Job? = null
        fun close() {
            readJob?.cancel()
            session.close()
        }
    }

    // ---- Observable surface (RelayView collects these) ----

    private val _state = MutableStateFlow<RelayState>(RelayState.Idle)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _hopNumber = MutableStateFlow(0)
    val hopNumber: StateFlow<Int> = _hopNumber.asStateFlow()

    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent.asStateFlow()

    private val _messagesReceived = MutableStateFlow(0)
    val messagesReceived: StateFlow<Int> = _messagesReceived.asStateFlow()

    private val _heartbeatCount = MutableStateFlow(0)
    val heartbeatCount: StateFlow<Int> = _heartbeatCount.asStateFlow()

    /** Decrypted advert/unknown frames (raw JSON bytes) for AdvertRelay. */
    var onFrame: ((ByteArray) -> Unit)? = null

    private val stateListeners = CopyOnWriteArrayList<(RelayState) -> Unit>()
    fun addStateListener(listener: (RelayState) -> Unit) = stateListeners.add(listener)

    // ---- Confinement ----

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bluesteal.tunnel").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // ---- Session state (touched only on `scope`) ----

    private var role: RelayRole = RelayRole.Host
    private var code = ""
    private var keys: TunnelKeys? = null
    private var host = "cable.ua5v.com"
    @Volatile private var userInitiated = false
    private var peerSaidBye = false
    @Volatile private var disposed = false

    private var currentHop: Hop? = null
    private var pendingHop: Hop? = null
    private var hopState: HopState = HopState.STEADY
    private var pendingRoutingId: String? = null

    private var heartbeatJob: Job? = null
    private var handoffJob: Job? = null

    // ---- Public API ----

    fun host(code: String) {
        scope.launch { hostInternal(code) }
    }

    fun join(token: String) {
        scope.launch { joinInternal(token) }
    }

    fun send(data: ByteArray) {
        scope.launch {
            if (disposed) return@launch
            val hop = currentHop ?: return@launch
            try {
                hop.session.send(data)
                _messagesSent.value += 1
            } catch (e: Exception) {
                tracer.log("tunnel", "send_failed", mapOf("error" to e.toString()))
            }
        }
    }

    fun disconnect() {
        if (disposed) return
        userInitiated = true
        scope.launch {
            val s = _state.value
            val hop = currentHop
            if (s is RelayState.Open && hop != null) {
                try {
                    sendControl(hop, RelayMessage.Bye("user_disconnect"))
                    tracer.log("tunnel", "bye.sent", mapOf("reason" to "user_disconnect"))
                } catch (e: Exception) { /* ignore */ }
            }
            dispose()
        }
    }

    private suspend fun hostInternal(code: String) {
        resetSession()
        this.code = code
        role = RelayRole.Host
        val keys = TunnelKeyDerivation.derive(code)
        this.keys = keys
        host = TunnelServerDomain.get(keys.tunnelServerId) ?: "cable.ua5v.com"
        tracer.setContext("Host", code)
        setState(RelayState.Hosting(code))

        val minute = TunnelKeyDerivation.currentUtcMinute()
        val tunnelId = TunnelKeyDerivation.deriveTunnelId(code, minute)
        tracer.log("tunnel", "host.minute", mapOf("minute" to minute, "tunnelId" to tunnelId.hexUpper()))

        try {
            val socket = TunnelHostSocket.connect(host, tunnelId, tracer)
            val tok = TunnelKeyDerivation.formatToken(code, socket.routingId)
            tracer.log("tunnel", "token.ready", mapOf("token" to tok))
            _token.value = tok

            // Stay in .hosting (token on screen, copyable) while waiting for a
            // peer; the handshake only proceeds once the peer connects and sends
            // its initiator message, at which point we go straight to .open.
            val session = TunnelNoiseSession.handshakeAsHost(socket, keys, tracer)

            val hop = Hop(session, socket.routingId)
            currentHop = hop
            setState(RelayState.Open(RelayRole.Host))
            startReadLoop(hop)
            startHeartbeat()
            startHandoffTimer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracer.log("tunnel", "host.failed", mapOf("error" to e.toString()))
            if (!isClosed()) setState(RelayState.Closed("host: ${e.message}"))
        }
    }

    private suspend fun joinInternal(token: String) {
        resetSession()
        val parsed = TunnelKeyDerivation.parseToken(token)
        if (parsed == null) {
            setState(RelayState.Closed("join: bad token"))
            return
        }
        val (code, routingId) = parsed
        this.code = code
        role = RelayRole.Peer
        val keys = TunnelKeyDerivation.derive(code)
        this.keys = keys
        host = TunnelServerDomain.get(keys.tunnelServerId) ?: "cable.ua5v.com"
        tracer.setContext("Peer", code)
        setState(RelayState.Joining(code))

        val minute = TunnelKeyDerivation.currentUtcMinute()
        val candidates = (-2..2).map { TunnelKeyDerivation.deriveTunnelId(code, minute + it) }
        tracer.log("tunnel", "peer.minute", mapOf("minute" to minute, "candidates" to candidates.size))

        try {
            val socket = TunnelPeerSocket.connectRace(host, routingId, candidates, tracer)
            setState(RelayState.Negotiating)
            val session = TunnelNoiseSession.handshakeAsPeer(socket, keys, tracer)

            val hop = Hop(session, routingId)
            currentHop = hop
            setState(RelayState.Open(RelayRole.Peer))
            startReadLoop(hop)
            startHeartbeat()
            // Peer does not initiate handoffs — it responds to offers.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracer.log("tunnel", "join.failed", mapOf("error" to e.toString()))
            if (!isClosed()) setState(RelayState.Closed("join: ${e.message}"))
        }
    }

    // ---- Read loop ----

    private fun startReadLoop(hop: Hop) {
        hop.readJob = scope.launch {
            try {
                while (isActive) {
                    val frame = hop.session.receive()
                    if (!isActive) break
                    handleFrame(frame, hop)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleReadExit(hop, e)
            }
        }
    }

    private fun handleReadExit(hop: Hop, error: Exception) {
        if (disposed) return
        tracer.log("tunnel", "read_loop_exit",
            mapOf("error" to error.toString(), "hopWasPending" to (hop === pendingHop)))
        if (hop === pendingHop) {
            abortPending("pending_read_error")
        } else if (hop === currentHop && hopState == HopState.STEADY && !isClosed()) {
            setState(RelayState.Closed(if (peerSaidBye) "peer" else "tunnel closed"))
        }
    }

    private fun handleFrame(frame: ByteArray, hop: Hop) {
        // Peer: first frame on the pending hop (after we acked) proves the host
        // has switched — promote, then process this frame on the now-current hop.
        if (hop === pendingHop && role == RelayRole.Peer && hopState == HopState.AWAITING_FIRST_FRAME) {
            tracer.log("tunnel", "hop.first_frame_received", mapOf("nextRoutingId" to (pendingRoutingId ?: "")))
            promoteHop()
        }

        val message = try {
            json.decodeFromString(RelayMessage.serializer(), String(frame, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            onFrame?.invoke(frame)
            return
        }

        when (message) {
            is RelayMessage.Heartbeat -> _heartbeatCount.value += 1
            is RelayMessage.Advert -> {
                _messagesReceived.value += 1
                onFrame?.invoke(frame)
            }
            is RelayMessage.Qr -> {
                _messagesReceived.value += 1
                onFrame?.invoke(frame)
            }
            is RelayMessage.Bye -> {
                tracer.log("tunnel", "bye.received", mapOf("reason" to message.reason))
                if (message.reason != "hop") {        // "hop" = expected end of an old hop
                    peerSaidBye = true
                    setState(RelayState.Closed("peer: ${message.reason}"))
                }
            }
            is RelayMessage.HandoffOffer -> {
                if (role == RelayRole.Peer) {
                    tracer.log("tunnel", "hop.offer_received", mapOf(
                        "nextRoutingId" to message.payload.nextRoutingId,
                        "nextMinute" to message.payload.nextMinute
                    ))
                    scope.launch { handleOfferAsPeer(message.payload) }
                } else {
                    onFrame?.invoke(frame)
                }
            }
            is RelayMessage.HandoffAck -> {
                if (role == RelayRole.Host) {
                    tracer.log("tunnel", "hop.ack_received", mapOf(
                        "nextRoutingId" to message.payload.nextRoutingId,
                        "connected" to message.payload.connected
                    ))
                    handleAckAsHost(message.payload)
                } else {
                    onFrame?.invoke(frame)
                }
            }
        }
    }

    // ---- Host-side handoff ----

    private fun startHandoffTimer() {
        if (role != RelayRole.Host) return
        handoffJob = scope.launch {
            while (isActive) {
                delay(HANDOFF_INTERVAL_MS)
                if (_state.value !is RelayState.Open) break
                if (hopState != HopState.STEADY) continue
                initiateHandoff()
            }
        }
    }

    private suspend fun initiateHandoff() {
        tracer.log("tunnel", "hop.begin")
        val keys = keys ?: return
        val current = currentHop ?: return
        try {
            val minute = TunnelKeyDerivation.currentUtcMinute()
            val tunnelId = TunnelKeyDerivation.deriveTunnelId(code, minute)
            val socket = TunnelHostSocket.connect(host, tunnelId, tracer)
            val nextRoutingHex = socket.routingId.hexUpper()
            pendingRoutingId = nextRoutingHex

            // Offer goes out over the current hop BEFORE the handshake — the peer
            // needs the routing_id to connect and send its initiator message.
            sendControl(current, RelayMessage.HandoffOffer(
                RelayHandoffOffer(nextRoutingHex, minute, Instant.now().toString())))
            tracer.log("tunnel", "hop.offer_sent", mapOf("nextRoutingId" to nextRoutingHex, "minute" to minute))

            val session = TunnelNoiseSession.handshakeAsHost(socket, keys, tracer)
            pendingHop = Hop(session, socket.routingId)  // host reads it only after promotion
            hopState = HopState.PENDING_ACK

            startAckTimeout(nextRoutingHex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracer.log("tunnel", "hop.begin_failed", mapOf("error" to e.toString()))
            abortPending("begin_failed")
        }
    }

    private fun startAckTimeout(correlationId: String) {
        scope.launch {
            delay((HOST_ACK_TIMEOUT_S - HANDOFF_INTERVAL_S) * 1000L)
            if (hopState == HopState.PENDING_ACK && pendingRoutingId == correlationId) {
                tracer.log("tunnel", "hop.abort", mapOf("reason" to "ack_timeout", "nextRoutingId" to correlationId))
                abortPending("ack_timeout")
            }
        }
    }

    private fun handleAckAsHost(ack: RelayHandoffAck) {
        if (hopState != HopState.PENDING_ACK || ack.nextRoutingId != pendingRoutingId) return
        if (ack.connected) {
            tracer.log("tunnel", "hop.promote", mapOf("role" to "host", "nextRoutingId" to ack.nextRoutingId))
            promoteHop()
        } else {
            tracer.log("tunnel", "hop.abort", mapOf("reason" to "peer_nack", "nextRoutingId" to ack.nextRoutingId))
            abortPending("peer_nack")
        }
    }

    // ---- Peer-side handoff ----

    private suspend fun handleOfferAsPeer(offer: RelayHandoffOffer) {
        val keys = keys ?: return
        try {
            val routing = hexToBytes(offer.nextRoutingId)
            if (routing == null || routing.size != 3) throw TunnelException(TunnelError.MISSING_ROUTING_ID)
            val tunnelId = TunnelKeyDerivation.deriveTunnelId(code, offer.nextMinute)
            val socket = TunnelPeerSocket.connect(host, routing, tunnelId, tracer)
            val session = TunnelNoiseSession.handshakeAsPeer(socket, keys, tracer)

            val hop = Hop(session, routing)
            pendingHop = hop
            pendingRoutingId = offer.nextRoutingId
            hopState = HopState.AWAITING_FIRST_FRAME

            currentHop?.let {
                sendControl(it, RelayMessage.HandoffAck(RelayHandoffAck(offer.nextRoutingId, connected = true)))
                tracer.log("tunnel", "hop.ack_sent", mapOf("nextRoutingId" to offer.nextRoutingId, "connected" to true))
            }

            startReadLoop(hop)              // catches the first post-ack frame → promotion
            startFirstFrameTimeout(offer.nextRoutingId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracer.log("tunnel", "hop.peer_connect_failed",
                mapOf("error" to e.toString(), "nextRoutingId" to offer.nextRoutingId))
            currentHop?.let {
                try {
                    sendControl(it, RelayMessage.HandoffAck(RelayHandoffAck(offer.nextRoutingId, connected = false)))
                    tracer.log("tunnel", "hop.ack_sent", mapOf("nextRoutingId" to offer.nextRoutingId, "connected" to false))
                } catch (e2: Exception) { /* ignore */ }
            }
            abortPending("peer_connect_failed")
        }
    }

    private fun startFirstFrameTimeout(correlationId: String) {
        scope.launch {
            delay((PEER_FIRST_FRAME_TIMEOUT_S - HANDOFF_INTERVAL_S) * 1000L)
            if (hopState == HopState.AWAITING_FIRST_FRAME && pendingRoutingId == correlationId) {
                tracer.log("tunnel", "hop.abort",
                    mapOf("reason" to "first_frame_timeout", "nextRoutingId" to correlationId))
                abortPending("first_frame_timeout")
            }
        }
    }

    // ---- Promotion / abort ----

    private fun promoteHop() {
        val old = currentHop
        currentHop = pendingHop
        pendingHop = null
        hopState = HopState.STEADY
        pendingRoutingId = null
        _hopNumber.value += 1

        val rid = currentHop?.routingId?.hexUpper() ?: "?"
        tracer.log("tunnel", "hop.promote", mapOf("newRoutingId" to rid, "hopCount" to _hopNumber.value))

        // Host's promoted hop had no read loop yet; the peer's promoted hop
        // (was pending) already has one running.
        currentHop?.let { if (it.readJob == null) startReadLoop(it) }

        old?.let { scheduleCloseOldHop(it) }
    }

    private fun abortPending(reason: String) {
        pendingHop?.close()
        pendingHop = null
        hopState = HopState.STEADY
        pendingRoutingId = null
    }

    private fun scheduleCloseOldHop(old: Hop) {
        old.readJob?.cancel()
        scope.launch {
            delay(OLD_HOP_LINGER_S * 1000L)
            try {
                sendControl(old, RelayMessage.Bye("hop"))
            } catch (e: Exception) { /* ignore */ }
            tracer.log("tunnel", "hop.old_closed")
            old.close()
        }
    }

    // ---- Heartbeat ----

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(2_000)
                if (_state.value !is RelayState.Open) continue
                val hop = currentHop ?: continue
                try {
                    sendControl(hop, RelayMessage.Heartbeat(RelayHeartbeat(Instant.now().toString(), emptyList())))
                    tracer.log("tunnel", "heartbeat.sent")
                } catch (e: Exception) {
                    tracer.log("tunnel", "heartbeat.send_failed", mapOf("error" to e.toString()))
                    return@launch
                }
            }
        }
    }

    // ---- Helpers ----

    private suspend fun sendControl(hop: Hop, message: RelayMessage) {
        val data = json.encodeToString(RelayMessage.serializer(), message).toByteArray(StandardCharsets.UTF_8)
        hop.session.send(data)
    }

    private fun dispose() {
        if (disposed) return
        disposed = true
        heartbeatJob?.cancel()
        handoffJob?.cancel()
        currentHop?.close()
        pendingHop?.close()
        currentHop = null
        pendingHop = null
        if (!isClosed()) {
            setState(RelayState.Closed(
                if (userInitiated) "user" else if (peerSaidBye) "peer" else "closed"))
        }
    }

    /**
     * Wipe per-session state at the start of a new host()/join() so a previous
     * session's hop count, message counters, token, and `disposed` flag don't
     * bleed into the new one. Cancels any timers/hops that survived the prior
     * dispose() for belt-and-braces safety.
     */
    private fun resetSession() {
        _hopNumber.value = 0
        _messagesSent.value = 0
        _messagesReceived.value = 0
        _heartbeatCount.value = 0
        _token.value = null
        disposed = false
        userInitiated = false
        peerSaidBye = false
        pendingRoutingId = null
        hopState = HopState.STEADY
        heartbeatJob?.cancel(); heartbeatJob = null
        handoffJob?.cancel(); handoffJob = null
        currentHop?.close(); currentHop = null
        pendingHop?.close(); pendingHop = null
    }

    private fun isClosed(): Boolean = _state.value is RelayState.Closed

    private fun setState(newState: RelayState) {
        if (_state.value == newState) return
        _state.value = newState
        tracer.log("tunnel", "state.change", mapOf("transport" to "cable-tunnel", "to" to label(newState)))
        for (l in stateListeners) l(newState)
    }

    private fun label(s: RelayState): String = when (s) {
        is RelayState.Idle -> "idle"
        is RelayState.Hosting -> "hosting"
        is RelayState.Joining -> "joining"
        is RelayState.Negotiating -> "negotiating"
        is RelayState.Open -> "open(${s.role.name.lowercase()})"
        is RelayState.Closed -> "closed(${s.reason})"
    }

    companion object {
        private const val HANDOFF_INTERVAL_S = 30L
        private const val HANDOFF_INTERVAL_MS = 30_000L
        private const val HOST_ACK_TIMEOUT_S = 50L
        private const val PEER_FIRST_FRAME_TIMEOUT_S = 55L
        private const val OLD_HOP_LINGER_S = 15L
    }
}
