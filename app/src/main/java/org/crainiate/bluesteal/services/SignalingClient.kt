package org.crainiate.bluesteal.services

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.crainiate.bluesteal.logging.RelayTracer
import org.crainiate.bluesteal.models.SignalingMessage
import org.crainiate.bluesteal.models.typeName
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin MQTT wrapper that runs the ~5-second WebRTC pairing handshake over a
 * public broker. Topics:
 *
 * ```
 * bluesteal/v1/<code>/offer      (retained — host publishes once)
 * bluesteal/v1/<code>/answer     (peer publishes once)
 * bluesteal/v1/<code>/ice/host   (host trickle ICE)
 * bluesteal/v1/<code>/ice/peer   (peer trickle ICE)
 * bluesteal/v1/<code>/bye        (either side announces close)
 * ```
 *
 * Knows nothing about WebRTC; just emits [SignalingMessage]s up to whoever
 * owns it. Mirrors `bluesteal-macos/bluesteal/Services/SignalingClient.swift` and
 * Windows `Bluesteal/Services/SignalingClient.cs` exactly — pairing-code
 * alphabet, code length, FNV-1a starting-broker index, and 6 s per-broker
 * timeout MUST agree across all four platforms or cross-platform pairing
 * breaks.
 */
class SignalingClient(
    val role: Role,
    val code: String,
    private val tracer: RelayTracer? = null,
    brokers: List<Broker>? = null
) {

    enum class Role { Host, Peer }

    data class Broker(
        val host: String,
        val port: Int,
        val path: String,
        val useTLS: Boolean
    ) {
        companion object {
            // Android uses plain MQTT-over-TLS on the standard 8883 port,
            // **not** the WSS endpoints (8081 / 8084 / 8884) the other three
            // platforms use. On Android the HiveMQ + Netty WebSocket upgrade
            // hangs through the full per-broker budget against every public
            // broker we tested — same broker, same IP, same network gets a
            // working WSS connection in ~500 ms from macOS / iOS. Dropping
            // WebSocket framing makes the handshake just TCP + TLS + MQTT
            // CONNECT and unblocks the pairing flow. All three brokers expose
            // both transports on the same broker instance, so a Host on WSS
            // 8084 and a Peer on TLS 8883 still pub/sub the same topics.
            //
            // `path` is unused on the TLS transport but kept for parity with
            // the canonical Swift / C# broker tables.
            val HiveMQ      = Broker("broker.hivemq.com",   8883, "/mqtt", useTLS = true)
            val EMQX        = Broker("broker.emqx.io",      8883, "/mqtt", useTLS = true)
            val Mosquitto   = Broker("test.mosquitto.org",  8883, "/mqtt", useTLS = true)
            val fallbacks: List<Broker> = listOf(HiveMQ, EMQX, Mosquitto)
        }
    }

    // ---- Public surface ----

    var onConnect: (() -> Unit)? = null
    var onDisconnect: ((Throwable?) -> Unit)? = null
    var onMessage: ((SignalingMessage) -> Unit)? = null

    @Volatile var isConnected: Boolean = false
        private set

    // ---- Internals ----

    private val brokers: List<Broker> = brokers ?: Broker.fallbacks
    @Volatile private var brokerIndex: Int = 0
    @Volatile private var attemptCount: Int = 0
    private val activeClient = AtomicReference<Mqtt3AsyncClient?>(null)
    @Volatile private var torndown: Boolean = false

    /**
     * DNS happens on a background thread because (a) it's blocking and we
     * connect from the main thread, and (b) every attempt needs to pre-resolve
     * to IPv4 (see [resolveIPv4]).
     */
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    companion object {
        /**
         * Alphabet used for pairing codes — uppercase letters with the
         * ambiguous I, L, O removed.
         *
         * **Must match Windows `SignalingClient.cs` exactly**: the FNV-1a
         * hash of the code drives the starting-broker selection on every
         * platform, so any divergence (length, alphabet, casing) breaks
         * cross-platform pairing.
         */
        val codeAlphabet: CharArray = "ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray()

        /** Length of a generated pairing code. 5 × 23 ≈ 6.4M combinations. */
        const val codeLength: Int = 5

        /**
         * Per-broker connect timeout. macOS / iOS / Windows all use 6 s; we
         * extend it to 10 s on Android because the first-attempt TLS + WSS
         * handshake carries significantly more setup cost on the platform
         * (Conscrypt cold-start, Netty bootstrap), and a 6 s budget kills the
         * connection mid-handshake on real mobile networks even when TCP and
         * the broker are healthy.
         *
         * Cross-platform broker lock-step (the FNV-1a-seeded walk) still
         * works as long as both sides eventually settle on the same reachable
         * broker — a longer budget on one side just means it converges later,
         * not differently. Logged explicitly so the trace makes the deviation
         * obvious when diffing against other platforms.
         */
        private const val perBrokerTimeoutSeconds: Long = 10

        fun makeCode(): String {
            val sb = StringBuilder(codeLength)
            val rng = java.security.SecureRandom()
            for (i in 0 until codeLength) sb.append(codeAlphabet[rng.nextInt(codeAlphabet.size)])
            return sb.toString()
        }

        fun topicRoot(code: String): String = "bluesteal/v1/$code"

        private fun fnv1aIndex(code: String, modulo: Int): Int {
            var hash: UInt = 2166136261u
            for (c in code) {
                hash = hash xor (c.code.toUInt() and 0xffu)
                hash = hash.times(16777619u)
            }
            return (hash % modulo.toUInt()).toInt()
        }

        fun preview(s: String): String =
            if (s.length <= 120) s else s.substring(0, 120) + "…(+${s.length - 120})"
    }

    fun connect() {
        brokerIndex = fnv1aIndex(code, brokers.size)
        attemptCount = 0
        tracer?.log(
            "mqtt", "mqtt.connect.start", mapOf(
                "role" to role.name,
                "code" to code,
                "brokerCount" to brokers.size,
                "startingBrokerIndex" to brokerIndex,
                "startingBroker" to brokers[brokerIndex].host,
                "perBrokerTimeoutMs" to (perBrokerTimeoutSeconds * 1000).toInt()
            )
        )
        attempt(brokerIndex)
    }

    fun disconnect() {
        tracer?.log("mqtt", "mqtt.disconnect.requested")
        torndown = true
        isConnected = false
        attemptCount = brokers.size   // suppress further walks
        val dying = activeClient.getAndSet(null)
        try {
            dying?.disconnect()
        } catch (_: Throwable) { }
    }

    fun publish(message: SignalingMessage) {
        val type = message.typeName()
        val client = activeClient.get()
        if (client == null || !isConnected) {
            tracer?.log(
                "signaling", "signaling.publish.skipped",
                mapOf("reason" to "not connected", "type" to type)
            )
            return
        }
        val root = topicRoot(code)
        val topic: String
        val retain: Boolean
        when (message) {
            is SignalingMessage.Offer -> { topic = "$root/offer"; retain = true }
            is SignalingMessage.Answer -> { topic = "$root/answer"; retain = false }
            is SignalingMessage.Ice -> {
                topic = "$root/ice/${if (role == Role.Host) "host" else "peer"}"
                retain = false
            }
            is SignalingMessage.Bye -> { topic = "$root/bye"; retain = false }
        }
        val body = try {
            json.encodeToString(SignalingMessage.serializer(), message)
        } catch (t: Throwable) {
            tracer?.log(
                "error", "signaling.publish.error",
                mapOf("type" to type, "topic" to topic, "error" to t.toString())
            )
            return
        }
        tracer?.log(
            "signaling", "signaling.publish",
            mapOf(
                "type" to type, "topic" to topic, "retain" to retain,
                "payloadBytes" to body.toByteArray().size, "preview" to preview(body)
            )
        )
        client.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(retain)
            .payload(body.toByteArray())
            .send()
    }

    // ---- Broker walk ----

    private fun attempt(index: Int) {
        if (attemptCount >= brokers.size) {
            tracer?.log("error", "mqtt.connect.exhausted", mapOf("triedBrokers" to brokers.size))
            onDisconnect?.invoke(RuntimeException("All brokers unreachable"))
            return
        }
        brokerIndex = index
        attemptCount += 1
        val broker = brokers[index]

        tracer?.log(
            "mqtt", "mqtt.connect.attempt", mapOf(
                "index" to index, "host" to broker.host, "port" to broker.port,
                "tls" to broker.useTLS, "attempt" to attemptCount
            )
        )

        // Two Android-specific deviations from the canonical Swift / C# code:
        //
        // 1. We pre-resolve the hostname ourselves and filter to the first
        //    IPv4 result, because Netty's `DnsNameResolver` ignores the JVM
        //    `preferIPv6Addresses=false` property at runtime and otherwise
        //    happily walks AAAA records on networks that black-hole IPv6 to
        //    these brokers (confirmed empirically against broker.hivemq.com
        //    and test.mosquitto.org on the Pixel 5).
        // 2. `InetAddress.getByAddress(host, ipv4Bytes)` is the documented
        //    escape hatch for "pre-resolved IP, preserve hostname". The
        //    resulting `InetSocketAddress.getHostString()` returns the
        //    original hostname, which Netty uses for the TLS SNI extension
        //    — so the server cert validates against `broker.hivemq.com` even
        //    though the socket is opened to `3.126.73.82`.
        //
        // We also use plain MQTT-over-TLS (no WebSocket) on Android — see
        // the comment on the Broker companion table for the rationale.
        resolverScope.launch {
            val address = try {
                resolveIPv4(broker.host, broker.port)
            } catch (t: Throwable) {
                tracer?.log("error", "mqtt.resolve.error",
                    mapOf("host" to broker.host, "error" to t.toString()))
                handleConnectFailure(index, t)
                return@launch
            }
            tracer?.log("mqtt", "mqtt.resolve.ok", mapOf(
                "host" to broker.host,
                "ip" to (address.address?.hostAddress ?: "?"),
                "family" to "IPv4"
            ))

            val clientBuilder = MqttClient.builder()
                .useMqttVersion3()
                .identifier("bluesteal-${role.name.lowercase()}-${UUID.randomUUID().toString().take(8)}")
                .serverAddress(address)

            val builder = if (broker.useTLS) clientBuilder.sslWithDefaultConfig() else clientBuilder
            val client = builder.buildAsync()
            activeClient.set(client)

            val connectStartMs = System.currentTimeMillis()
            val deadline = TimeUnit.SECONDS.toMillis(perBrokerTimeoutSeconds)
            client.connectWith()
                .cleanSession(true)
                .keepAlive(30)
                .send()
                .orTimeout(deadline, TimeUnit.MILLISECONDS)
                .whenComplete { _, error ->
                    val elapsedMs = System.currentTimeMillis() - connectStartMs
                    if (activeClient.get() !== client) {
                        // Stale callback — we've already moved on.
                        tracer?.log("mqtt", "mqtt.connected.ignored",
                            mapOf("reason" to "stale client", "elapsedMs" to elapsedMs))
                        return@whenComplete
                    }
                    if (error != null) {
                        tracer?.log("mqtt", "mqtt.connect.elapsed",
                            mapOf("elapsedMs" to elapsedMs, "outcome" to "failed"))
                        handleConnectFailure(index, error)
                    } else {
                        tracer?.log("mqtt", "mqtt.connect.elapsed",
                            mapOf("elapsedMs" to elapsedMs, "outcome" to "ok"))
                        handleConnectSuccess(client, broker)
                    }
                }
        }
    }

    /**
     * Resolve [host] and return the first IPv4 result wrapped in an
     * [InetSocketAddress] that still reports the original hostname (so HiveMQ
     * presents the correct SNI on the TLS handshake).
     *
     * Throws if DNS fails or the host has no A record.
     */
    private fun resolveIPv4(host: String, port: Int): InetSocketAddress {
        val all = InetAddress.getAllByName(host)
        val v4 = all.firstOrNull { it is Inet4Address }
            ?: throw RuntimeException("No IPv4 address for $host (got ${all.toList()})")
        val labelled = InetAddress.getByAddress(host, v4.address)
        return InetSocketAddress(labelled, port)
    }

    private fun handleConnectSuccess(client: Mqtt3AsyncClient, broker: Broker) {
        isConnected = true
        val root = topicRoot(code)
        val topics = buildList {
            when (role) {
                Role.Host -> {
                    add("$root/answer")
                    add("$root/ice/peer")
                }
                Role.Peer -> {
                    add("$root/offer")
                    add("$root/ice/host")
                }
            }
            add("$root/bye")
        }

        val pubCallback = java.util.function.Consumer<Mqtt3Publish> { publish ->
            val topic = publish.topic.toString()
            val payload = publish.payloadAsBytes.toString(Charsets.UTF_8)
            handleIncoming(topic, payload)
        }
        val futures = topics.map { topic ->
            client.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(pubCallback)
                .send()
        }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, err ->
            tracer?.log(
                "mqtt", "mqtt.connected",
                mapOf("host" to broker.host, "port" to broker.port, "tls" to broker.useTLS)
            )
            tracer?.log("mqtt", "mqtt.subscribed", mapOf("role" to role.name, "topics" to topics))
            if (err != null) {
                tracer?.log("mqtt", "mqtt.subscribe.error", mapOf("error" to err.toString()))
            }
            onConnect?.invoke()
        }
    }

    private fun handleConnectFailure(index: Int, error: Throwable) {
        val broker = brokers[index]
        tracer?.log(
            "error", "mqtt.connect.failed",
            mapOf("index" to index, "host" to broker.host, "error" to error.toString())
        )
        val dying = activeClient.getAndSet(null)
        try { dying?.disconnect() } catch (_: Throwable) { }
        if (torndown) return
        val next = (index + 1) % brokers.size
        attempt(next)
    }

    private fun handleIncoming(topic: String, payload: String) {
        if (isOwnOrigin(topic)) {
            tracer?.log(
                "signaling", "signaling.receive.filtered",
                mapOf("topic" to topic, "payloadBytes" to payload.toByteArray().size, "reason" to "ownOrigin")
            )
            return
        }
        if (payload.isEmpty()) {
            tracer?.log("signaling", "signaling.receive.empty", mapOf("topic" to topic))
            return
        }
        val message = try {
            json.decodeFromString(SignalingMessage.serializer(), payload)
        } catch (t: Throwable) {
            tracer?.log(
                "error", "signaling.parse.error",
                mapOf("topic" to topic, "error" to t.toString(), "payloadPreview" to preview(payload))
            )
            return
        }
        tracer?.log(
            "signaling", "signaling.receive",
            mapOf(
                "topic" to topic, "payloadBytes" to payload.toByteArray().size,
                "parsedType" to message.typeName()
            )
        )
        onMessage?.invoke(message)
    }

    private fun isOwnOrigin(topic: String): Boolean = when (role) {
        Role.Host -> topic.endsWith("/ice/host") || topic.endsWith("/offer")
        Role.Peer -> topic.endsWith("/ice/peer") || topic.endsWith("/answer")
    }
}
