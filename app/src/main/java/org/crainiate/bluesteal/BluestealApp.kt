package org.crainiate.bluesteal

import android.app.Application
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.crainiate.bluesteal.logging.JsonlLogger
import org.crainiate.bluesteal.logging.RelayTracer
import org.crainiate.bluesteal.services.AdvertRelay
import org.crainiate.bluesteal.services.AdvertStore
import org.crainiate.bluesteal.services.BleAdvertiser
import org.crainiate.bluesteal.services.CableScanner
import org.crainiate.bluesteal.services.PairingCode
import org.crainiate.bluesteal.services.QRRelay
import org.crainiate.bluesteal.services.RebroadcastService
import org.crainiate.bluesteal.services.RelayForegroundService
import org.crainiate.bluesteal.services.RelayState
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
    lateinit var advertRelay: AdvertRelay
        private set
    lateinit var bleAdvertiser: BleAdvertiser
        private set
    lateinit var tunnel: TunnelTransport
        private set
    lateinit var rebroadcast: RebroadcastService
        private set
    lateinit var qrRelay: QRRelay
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Bias the JVM resolver toward IPv4 *before* anything else touches
        // networking. On the Pixel 5 we observed connects taking the AAAA
        // record first on networks that route IPv4 fine but silently
        // black-hole IPv6 — every attempt would time out. These must be set
        // before InetAddress caches anything.
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
        advertRelay = AdvertRelay(store = store, tracer = tracer)
        bleAdvertiser = BleAdvertiser(this, tracer)
        // caBLE tunnel transport — the only transport. Inbound decrypted frames
        // are routed to the QR display modal if they're shared QR codes,
        // otherwise into the advert path.
        tunnel = TunnelTransport(tracer)
        qrRelay = QRRelay(tunnel, tracer)
        tunnel.onFrame = { bytes ->
            if (!qrRelay.tryHandle(bytes)) advertRelay.handleTunnelFrame(bytes)
        }
        rebroadcast = RebroadcastService(
            advertRelay = advertRelay,
            advertiser = bleAdvertiser,
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

        // Keep the relay alive while backgrounded / screen-locked: run a
        // connectedDevice foreground service for the duration of a session.
        // Start only on the inactive->active edge (which happens on the user's
        // Host/Join tap, i.e. in the foreground, so the FGS-start is allowed);
        // stop on the active->inactive edge. Self-heal keeps the state active
        // (Joining/Hosting), so a drop never stops or background-restarts it.
        scope.launch {
            var wasActive = false
            tunnel.state.collect { s ->
                val active = s is RelayState.Hosting || s is RelayState.Joining ||
                    s is RelayState.Negotiating || s is RelayState.Open
                if (active && !wasActive) {
                    RelayForegroundService.start(this@BluestealApp)
                } else if (!active && wasActive) {
                    RelayForegroundService.stop(this@BluestealApp)
                }
                wasActive = active
            }
        }
    }

    /** Wire outbound adverts through the tunnel and start hosting. */
    fun beginHost() {
        advertRelay.tunnelSend = { bytes -> tunnel.send(bytes) }
        tunnel.host(PairingCode.make())
    }

    /** Wire outbound adverts through the tunnel and join the given OOB token. */
    fun beginJoin(token: String) {
        advertRelay.tunnelSend = { bytes -> tunnel.send(bytes) }
        tunnel.join(token.trim().uppercase())
    }

    /**
     * Test affordance: host and, once a peer connects, send the URI as a QR —
     * for headless verification of the share path (mirrors iOS `-sendqr`).
     */
    fun beginSendQrOnOpen(uri: String) {
        beginHost()
        scope.launch {
            repeat(120) {
                delay(500)
                if (tunnel.state.value is RelayState.Open) {
                    qrRelay.send(uri)
                    return@launch
                }
            }
        }
    }
}
