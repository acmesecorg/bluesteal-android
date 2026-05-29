package org.crainiate.bluesteal

import android.app.Application
import android.os.Build
import org.crainiate.bluesteal.logging.JsonlLogger
import org.crainiate.bluesteal.logging.RelayTracer
import org.crainiate.bluesteal.services.AdvertRelay
import org.crainiate.bluesteal.services.AdvertStore
import org.crainiate.bluesteal.services.BleAdvertiser
import org.crainiate.bluesteal.services.CableScanner
import org.crainiate.bluesteal.services.RebroadcastService
import org.crainiate.bluesteal.services.RelayClient
import org.crainiate.bluesteal.services.tunnel.TunnelTransport

/**
 * Application-singleton DI container. The same singletons that live in
 * `bluestealApp.swift` on macOS — tracer, scanner, store, relay, advertiser,
 * rebroadcast — are constructed here once per process and shared by
 * [org.crainiate.bluesteal.ui.MainActivity].
 */
class BluestealApp : Application() {
    lateinit var tracer: RelayTracer
        private set
    lateinit var scanner: CableScanner
        private set
    lateinit var store: AdvertStore
        private set
    lateinit var relay: RelayClient
        private set
    lateinit var advertRelay: AdvertRelay
        private set
    lateinit var bleAdvertiser: BleAdvertiser
        private set
    lateinit var tunnel: TunnelTransport
        private set
    lateinit var rebroadcast: RebroadcastService
        private set

    override fun onCreate() {
        super.onCreate()

        // Bias the JVM resolver toward IPv4 *before* anything else touches
        // networking. On the Pixel 5 we observed Netty (HiveMQ's transport)
        // taking the AAAA record first on networks that route IPv4 fine but
        // silently black-hole IPv6 to these public brokers — every connect
        // attempt would time out at the 6 s budget. These properties must be
        // set before InetAddress / Netty's DnsNameResolver caches anything.
        System.setProperty("java.net.preferIPv6Addresses", "false")
        System.setProperty("java.net.preferIPv4Stack", "true")

        tracer = RelayTracer(this)
        tracer.log("app", "app.start", mapOf(
            "applicationId" to packageName,
            "versionName" to runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull(),
            "android" to "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "logFile" to tracer.logFile.absolutePath
        ))

        scanner = CableScanner(this)
        store = AdvertStore(JsonlLogger(this))
        relay = RelayClient(this, tracer)
        advertRelay = AdvertRelay(store = store, relay = relay)
        bleAdvertiser = BleAdvertiser(this, tracer)
        // caBLE tunnel transport (parallel to WebRTC). Inbound decrypted frames
        // are routed into the same advert path as the WebRTC channel.
        tunnel = TunnelTransport(tracer)
        tunnel.onFrame = { bytes -> advertRelay.handleTunnelFrame(bytes) }
        rebroadcast = RebroadcastService(
            advertRelay = advertRelay,
            advertiser = bleAdvertiser,
            relay = relay,
            tunnel = tunnel,
            tracer = tracer
        )
        // Loop-guard: AdvertStore must drop scanner events that match a
        // payload we're currently re-broadcasting ourselves, otherwise the
        // local scanner would re-ingest its own emission and forward it back
        // to the peer.
        store.selfPayloadFilter = { hex -> rebroadcast.isSelfPublished(hex) }

        // Wire the scanner output into the store. The scanner runs on a BLE
        // worker thread; AdvertStore is thread-safe.
        scanner.onEvent = { event -> store.ingest(event) }
    }
}
