package org.crainiate.bluesteal.services

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.crainiate.bluesteal.BuildConfig
import org.crainiate.bluesteal.logging.RelayTracer
import org.crainiate.bluesteal.models.RelayMessage
import org.crainiate.bluesteal.models.SignalingMessage
import org.crainiate.bluesteal.models.typeName
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates a WebRTC DataChannel between two bluesteal instances paired by a
 * 5-letter code. The handshake (SDP + ICE) travels over a public MQTT broker
 * via [SignalingClient]; once the data channel is open we drop off the broker
 * and the channel is the only path used.
 *
 * Mirrors `bluesteal-macos/bluesteal/Services/RelayClient.swift` and Windows
 * `Bluesteal/Services/RelayClient.cs` — all five state transitions, the
 * `candidate:` prefix normalisation for SIPSorcery, and the trickle-ICE
 * buffer-until-remote-description ordering.
 */
class RelayClient(
    appContext: Context,
    private val tracer: RelayTracer
) {

    enum class Role { Host, Peer }

    sealed class State {
        data object Idle : State()
        data class Hosting(val code: String) : State()
        data class Joining(val code: String) : State()
        data object Negotiating : State()
        data class Open(val role: Role) : State()
        data class Closed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent.asStateFlow()

    private val _messagesReceived = MutableStateFlow(0)
    val messagesReceived: StateFlow<Int> = _messagesReceived.asStateFlow()

    /**
     * Tally of heartbeats on the data channel in either direction. Surfaces
     * in the UI alongside `sent` / `recv` so a session that is otherwise
     * silent (no adverts flowing) still visibly ticks every ~2s — confirms
     * the socket is alive end-to-end.
     */
    private val _heartbeatCount = MutableStateFlow(0)
    val heartbeatCount: StateFlow<Int> = _heartbeatCount.asStateFlow()

    private val _isUsingTurn = MutableStateFlow(false)
    val isUsingTurn: StateFlow<Boolean> = _isUsingTurn.asStateFlow()

    /** Inbound application messages (non-bye). Set by `AdvertRelay`. */
    var onMessage: ((RelayMessage) -> Unit)? = null

    /**
     * Fires on every distinct state transition. Set by `AdvertRelay` and
     * `RebroadcastService` to drive the heartbeat loop (start on `.Open`,
     * stop on anything else).
     *
     * Multiple subscribers fan out via [addStateListener] — `onStateChange`
     * is the legacy single-slot hook that mirrors macOS; new code should
     * prefer the multi-listener API.
     */
    var onStateChange: ((State) -> Unit)? = null
    private val extraStateListeners = ConcurrentLinkedQueue<(State) -> Unit>()

    fun addStateListener(listener: (State) -> Unit) {
        extraStateListeners.add(listener)
    }

    // ---- WebRTC plumbing ----

    private val factory: PeerConnectionFactory
    private val eglBase: EglBase = EglBase.create()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        val encoders = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoders = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoders)
            .setVideoDecoderFactory(decoders)
            .createPeerConnectionFactory()
    }

    private var peer: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var signaling: SignalingClient? = null
    @Volatile private var role: Role = Role.Host
    @Volatile private var code: String = ""

    private val pendingLocalCandidates = ConcurrentLinkedQueue<IceCandidate>()
    private val pendingRemoteCandidates = ConcurrentLinkedQueue<IceCandidate>()
    @Volatile private var hasRemoteDescription: Boolean = false
    private val isTornDown = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // ---- Public API ----

    fun host(): String {
        val newCode = SignalingClient.makeCode()
        code = newCode
        role = Role.Host
        isTornDown.set(false)
        tracer.setContext("Host", newCode)
        tracer.log("relay", "relay.host", mapOf("code" to newCode))
        setState(State.Hosting(newCode))
        createPeerConnection()
        startSignaling(newCode, SignalingClient.Role.Host)
        return newCode
    }

    fun join(code: String) {
        this.code = code
        role = Role.Peer
        isTornDown.set(false)
        tracer.setContext("Peer", code)
        tracer.log("relay", "relay.join", mapOf("code" to code))
        setState(State.Joining(code))
        createPeerConnection()
        startSignaling(code, SignalingClient.Role.Peer)
        // Peer's data channel arrives via PeerConnection.Observer.onDataChannel.
    }

    fun disconnect(reason: String = "user") {
        tracer.log("relay", "relay.disconnect", mapOf("reason" to reason))
        isTornDown.set(true)
        signaling?.takeIf { it.isConnected }?.publish(SignalingMessage.Bye(reason))
        signaling?.disconnect()
        signaling = null
        dataChannel?.close()
        dataChannel = null
        peer?.close()
        peer = null
        hasRemoteDescription = false
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        setState(State.Closed(reason))
    }

    fun send(message: RelayMessage) {
        val type = message.typeName()
        val dc = dataChannel
        if (dc == null) {
            tracer.log("dc", "dc.send.skipped", mapOf("reason" to "no channel", "type" to type))
            return
        }
        if (dc.state() != DataChannel.State.OPEN) {
            tracer.log("dc", "dc.send.skipped",
                mapOf("reason" to "state ${dc.state()}", "type" to type))
            return
        }
        val body = try {
            json.encodeToString(RelayMessage.serializer(), message)
        } catch (t: Throwable) {
            tracer.log("error", "dc.send.error", mapOf("type" to type, "error" to t.toString()))
            return
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), /* binary = */ false)
        val ok = dc.send(buffer)
        tracer.log("dc", "dc.send",
            mapOf("type" to type, "payloadBytes" to bytes.size, "queued" to ok))
        if (message is RelayMessage.Advert) _messagesSent.update { it + 1 }
        if (message is RelayMessage.Heartbeat) _heartbeatCount.update { it + 1 }
    }

    // ---- Setup ----

    private fun startSignaling(code: String, role: SignalingClient.Role) {
        val client = SignalingClient(role = role, code = code, tracer = tracer)
        client.onConnect = { signalingDidConnect() }
        client.onDisconnect = { err ->
            if (_state.value !is State.Open) {
                setState(State.Closed("signaling: ${err?.message ?: "disconnected"}"))
            }
        }
        client.onMessage = { msg -> handleSignaling(msg) }
        signaling = client
        client.connect()
    }

    private fun signalingDidConnect() {
        tracer.log("relay", "relay.signaling.connected", mapOf("role" to role.name))
        if (isTornDown.get() || peer == null || signaling == null) {
            tracer.log("relay", "relay.signaling.connected.ignored", mapOf(
                "state" to _state.value.label(),
                "isTornDown" to isTornDown.get(),
                "hasPC" to (peer != null),
                "hasSignaling" to (signaling != null)
            ))
            return
        }
        if (role != Role.Host) return

        val init = DataChannel.Init().apply { ordered = true }
        val ch = peer?.createDataChannel("bluesteal.adverts", init) ?: return
        ch.registerObserver(DataChannelObserverImpl(ch))
        dataChannel = ch
        tracer.log("dc", "dc.created", mapOf("label" to "bluesteal.adverts"))
        createOffer()
    }

    private fun createPeerConnection() {
        val ice = defaultIceServers()
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        peer = factory.createPeerConnection(cfg, PeerObserverImpl())
            ?: run {
                tracer.log("error", "pc.create.error", mapOf("error" to "createPeerConnection returned null"))
                null
            }
    }

    /**
     * ICE servers. STUN-only is fine for same-LAN pairings; TURN is the fallback
     * when ICE can't form a host/srflx pair — cross-AP via a WiFi extender,
     * cellular ↔ home, NATs without hairpinning, etc.
     *
     * Cloudflare Realtime TURN URLs include both UDP and TCP variants plus a
     * TLS-wrapped `turns:` line. The TLS form is the one that survives the
     * most hostile networks (corporate guest WiFi, mobile carriers that block
     * UDP). Credentials come from `BuildConfig`, populated at build time from
     * `local.properties` — see the root README for the signup flow. Blank
     * credentials are tolerated; libwebrtc will skip the TURN entries and
     * fall back to STUN.
     */
    private fun defaultIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder(listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
        )).createIceServer(),
        PeerConnection.IceServer.builder(listOf(
            "turn:turn.cloudflare.com:3478?transport=udp",
            "turn:turn.cloudflare.com:3478?transport=tcp",
            "turns:turn.cloudflare.com:5349?transport=tcp"
        ))
            .setUsername(BuildConfig.TURN_USERNAME)
            .setPassword(BuildConfig.TURN_CREDENTIAL)
            .createIceServer()
    )

    // ---- Offer / answer ----

    private fun createOffer() {
        val pc = peer ?: return
        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                tracer.log("pc", "pc.createOffer", mapOf("sdpBytes" to sdp.description.length))
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        tracer.log("pc", "pc.setLocalDescription",
                            mapOf("type" to "offer", "result" to "OK"))
                        signaling?.publish(SignalingMessage.Offer(sdp.description))
                    }
                    override fun onSetFailure(err: String?) {
                        tracer.log("error", "pc.setLocalDescription.error",
                            mapOf("type" to "offer", "error" to (err ?: "")))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(err: String?) {
                tracer.log("error", "pc.createOffer.error", mapOf("error" to (err ?: "")))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun createAnswer() {
        val pc = peer ?: return
        val constraints = MediaConstraints()
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                tracer.log("pc", "pc.createAnswer", mapOf("sdpBytes" to sdp.description.length))
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        tracer.log("pc", "pc.setLocalDescription",
                            mapOf("type" to "answer", "result" to "OK"))
                        signaling?.publish(SignalingMessage.Answer(sdp.description))
                        flushLocalCandidates()
                    }
                    override fun onSetFailure(err: String?) {
                        tracer.log("error", "pc.setLocalDescription.error",
                            mapOf("type" to "answer", "error" to (err ?: "")))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(err: String?) {
                tracer.log("error", "pc.createAnswer.error", mapOf("error" to (err ?: "")))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    // ---- Signaling inbound ----

    private fun handleSignaling(message: SignalingMessage) {
        val pc = peer ?: return
        when (message) {
            is SignalingMessage.Offer -> {
                if (role != Role.Peer) {
                    tracer.log("signaling", "signaling.unexpected",
                        mapOf("type" to "offer", "role" to role.name))
                    return
                }
                val remote = SessionDescription(SessionDescription.Type.OFFER, message.sdp)
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        tracer.log("pc", "pc.setRemoteDescription",
                            mapOf("type" to "offer", "result" to "OK"))
                        hasRemoteDescription = true
                        setState(State.Negotiating)
                        drainPendingRemoteCandidates()
                        createAnswer()
                    }
                    override fun onSetFailure(err: String?) {
                        tracer.log("error", "pc.setRemoteDescription.error",
                            mapOf("type" to "offer", "error" to (err ?: "")))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, remote)
            }

            is SignalingMessage.Answer -> {
                if (role != Role.Host) {
                    tracer.log("signaling", "signaling.unexpected",
                        mapOf("type" to "answer", "role" to role.name))
                    return
                }
                val remote = SessionDescription(SessionDescription.Type.ANSWER, message.sdp)
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        tracer.log("pc", "pc.setRemoteDescription",
                            mapOf("type" to "answer", "result" to "OK"))
                        hasRemoteDescription = true
                        setState(State.Negotiating)
                        drainPendingRemoteCandidates()
                        flushLocalCandidates()
                    }
                    override fun onSetFailure(err: String?) {
                        tracer.log("error", "pc.setRemoteDescription.error",
                            mapOf("type" to "answer", "error" to (err ?: "")))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, remote)
            }

            is SignalingMessage.Ice -> {
                // SIPSorcery on Windows emits the bare candidate value
                // (`foundation component proto …`) without the `candidate:`
                // prefix. WebRTC follows the W3C spec strictly and rejects
                // strings that don't start with `candidate:`. Normalise
                // defensively — accepts both forms.
                val normalised = if (message.candidate.sdp.startsWith("candidate:")) {
                    message.candidate.sdp
                } else {
                    "candidate:${message.candidate.sdp}"
                }
                val cand = IceCandidate(
                    message.candidate.sdpMid ?: "",
                    message.candidate.sdpMLineIndex,
                    normalised
                )
                if (hasRemoteDescription) {
                    tracer.log("ice", "ice.remote.apply", mapOf(
                        "type" to candidateType(message.candidate.sdp),
                        "sdpMid" to (message.candidate.sdpMid ?: ""),
                        "sdpMLineIndex" to message.candidate.sdpMLineIndex
                    ))
                    pc.addIceCandidate(cand)
                } else {
                    tracer.log("ice", "ice.remote.buffer", mapOf(
                        "type" to candidateType(message.candidate.sdp),
                        "sdpMid" to (message.candidate.sdpMid ?: "")
                    ))
                    pendingRemoteCandidates.add(cand)
                }
            }

            is SignalingMessage.Bye -> {
                disconnect(reason = "peer: ${message.reason}")
            }
        }
    }

    private fun drainPendingRemoteCandidates() {
        val pc = peer ?: return
        val n = pendingRemoteCandidates.size
        tracer.log("ice", "ice.remote.drain", mapOf("count" to n))
        while (true) {
            val c = pendingRemoteCandidates.poll() ?: break
            pc.addIceCandidate(c)
        }
    }

    private fun flushLocalCandidates() {
        val n = pendingLocalCandidates.size
        tracer.log("ice", "ice.local.flush", mapOf("count" to n))
        while (true) {
            val c = pendingLocalCandidates.poll() ?: break
            publishLocal(c)
        }
    }

    private fun publishLocal(candidate: IceCandidate) {
        signaling?.publish(SignalingMessage.Ice(
            SignalingMessage.IceCandidate(
                sdp = candidate.sdp,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex
            )
        ))
    }

    // ---- Observers ----

    private inner class PeerObserverImpl : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            tracer.log("ice", "ice.connectionState", mapOf("state" to newState.name))
            if (newState == PeerConnection.IceConnectionState.FAILED) {
                // Full teardown (not just setState) so per-session flags —
                // `hasRemoteDescription`, the candidate buffers — get cleared.
                // Without this, the next host()/join() reuses a stale
                // `hasRemoteDescription=true` and immediately publishes local
                // ICE candidates *before* the new peer has subscribed, which
                // breaks re-pairing after an ICE failure. See
                // docs/MACOS_RELAY_PAIRING_FIXES.md §10.
                disconnect(reason = "ICE failed")
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            tracer.log("ice", "ice.gatheringState", mapOf("state" to newState.name))
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            val type = candidateType(candidate.sdp)
            tracer.log("ice", "ice.local", mapOf(
                "type" to type,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "buffered" to !hasRemoteDescription
            ))
            if (hasRemoteDescription) publishLocal(candidate)
            else pendingLocalCandidates.add(candidate)
            if (type == "relay") _isUsingTurn.value = true
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel) {
            tracer.log("dc", "dc.received",
                mapOf("label" to dc.label(), "state" to dc.state().name))
            if (isTornDown.get()) {
                tracer.log("dc", "dc.received.ignored", mapOf("reason" to "torn down"))
                return
            }
            dataChannel = dc
            dc.registerObserver(DataChannelObserverImpl(dc))
            // The peer often receives a data channel that is *already* OPEN
            // by the time this delegate fires (DTLS+SCTP finishes before
            // WebRTC surfaces onDataChannel). Drive the open path
            // synchronously so we don't miss the transition.
            if (dc.state() == DataChannel.State.OPEN) {
                tracer.log("dc", "dc.alreadyOpen", mapOf("label" to dc.label()))
                handleDataChannelStateChange(dc)
            }
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    private inner class DataChannelObserverImpl(private val dc: DataChannel) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {
            handleDataChannelStateChange(dc)
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            tracer.log("dc", "dc.message",
                mapOf("payloadBytes" to bytes.size, "jsonPreview" to SignalingClient.preview(text)))
            val message = try {
                json.decodeFromString(RelayMessage.serializer(), text)
            } catch (t: Throwable) {
                tracer.log("error", "dc.parse.error",
                    mapOf("error" to t.toString(), "jsonPreview" to SignalingClient.preview(text)))
                return
            }
            if (message is RelayMessage.Advert) _messagesReceived.update { it + 1 }
            if (message is RelayMessage.Heartbeat) _heartbeatCount.update { it + 1 }
            if (message is RelayMessage.Bye) {
                disconnect(reason = "peer: ${message.reason}")
                return
            }
            onMessage?.invoke(message)
        }
    }

    private fun handleDataChannelStateChange(dc: DataChannel) {
        tracer.log("dc", "dc.stateChange",
            mapOf("label" to dc.label(), "state" to dc.state().name))
        when (dc.state()) {
            DataChannel.State.OPEN -> {
                tracer.log("dc", "dc.open", mapOf("label" to dc.label()))
                setState(State.Open(role))
                signaling?.disconnect()
                signaling = null
            }
            DataChannel.State.CLOSED -> {
                tracer.log("dc", "dc.close", mapOf("label" to dc.label()))
                if (_state.value is State.Open) setState(State.Closed("data channel closed"))
            }
            else -> {}
        }
    }

    // ---- State + tracing ----

    private fun setState(newState: State) {
        val old = _state.value
        if (old == newState) return
        _state.value = newState
        tracer.log("state", "state.change",
            mapOf("from" to old.label(), "to" to newState.label()))
        scope.launch {
            onStateChange?.invoke(newState)
            for (l in extraStateListeners) l(newState)
        }
    }

    private fun State.label(): String = when (this) {
        State.Idle -> "idle"
        is State.Hosting -> "hosting($code)"
        is State.Joining -> "joining($code)"
        State.Negotiating -> "negotiating"
        is State.Open -> "open(${role.name.lowercase()})"
        is State.Closed -> "closed($reason)"
    }

    private fun candidateType(sdp: String): String {
        val i = sdp.indexOf("typ ")
        if (i < 0) return "unknown"
        val rest = sdp.substring(i + 4)
        val end = rest.indexOf(' ')
        return if (end < 0) rest else rest.substring(0, end)
    }
}
