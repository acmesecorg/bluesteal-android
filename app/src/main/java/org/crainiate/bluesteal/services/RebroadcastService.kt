package org.crainiate.bluesteal.services

import android.bluetooth.le.AdvertiseData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.crainiate.bluesteal.logging.RelayTracer
import org.crainiate.bluesteal.models.AdvertVariant
import org.crainiate.bluesteal.models.CableAdvert
import org.crainiate.bluesteal.services.tunnel.TunnelTransport
import java.time.Duration
import java.time.Instant

/**
 * Re-emits adverts received from a paired peer over the local BLE peripheral
 * so a nearby CTAP hybrid client (e.g. Chrome on a laptop) can observe them.
 *
 * Latest-wins: a single advertise slot at a time, switching to the freshest
 * non-stale peer advert. TTL is 8 s since `lastSeen`; sweep runs every 2 s.
 * Self-published payloads are tracked so the local scanner doesn't ingest its
 * own emission and forward it back to the peer.
 *
 * Mirrors `bluesteal-macos/bluesteal/Services/RebroadcastService.swift`.
 *
 * Android-specific note vs macOS: [BluetoothLeAdvertiser] accepts raw
 * service-data and manufacturer-data, so we re-emit every variant faithfully
 * — including [AdvertVariant.GoogleMfgV1], which macOS has to skip.
 */
class RebroadcastService(
    private val advertRelay: AdvertRelay,
    private val advertiser: BleAdvertiser,
    private val tunnel: TunnelTransport,
    private val tracer: RelayTracer
) {
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val _activeAdvertID = MutableStateFlow<String?>(null)
    val activeAdvertID: StateFlow<String?> = _activeAdvertID.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val selfPublishedPayloadHex: MutableSet<String> = HashSet()
    @Volatile private var currentPayloadHex: String? = null
    @Volatile private var lastSwapTime: Instant = Instant.EPOCH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sweepJob: Job? = null

    init {
        advertiser.onStatusChanged = { active, error ->
            _isActive.value = active
            _lastError.value = error
        }
        // The sweep runs while the caBLE tunnel is open — that gates peer
        // advert re-broadcasting.
        tunnel.addStateListener { handleStateChange() }
        if (tunnelOpen()) startSweep()
    }

    private fun tunnelOpen(): Boolean = tunnel.state.value is RelayState.Open

    private fun handleStateChange() {
        if (tunnelOpen()) startSweep() else stopSweep()
    }

    /**
     * Loop-guard predicate handed to [AdvertStore.selfPayloadFilter]. Returns
     * true when the scanner observes a payload we are currently publishing
     * ourselves, so the store can drop it before it ever enters the local
     * list (and before it gets forwarded back to the peer).
     */
    fun isSelfPublished(payloadHex: String): Boolean = synchronized(selfPublishedPayloadHex) {
        selfPublishedPayloadHex.contains(payloadHex.lowercase())
    }

    // ---- Sweep ----

    private fun startSweep() {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            // Evaluate immediately so the first peer advert isn't delayed by a
            // full sweep interval, then settle into the periodic cadence.
            evaluateLatestWins()
            while (isActive) {
                delay(STALE_SWEEP_MS)
                evaluateLatestWins()
            }
        }
        tracer.log("rebroadcast", "rebroadcast.sweep.start")
    }

    private fun stopSweep() {
        sweepJob?.cancel()
        sweepJob = null
        stopPublisher("tunnel closed")
        _activeCount.value = 0
        tracer.log("rebroadcast", "rebroadcast.sweep.stop")
    }

    private fun evaluateLatestWins() {
        val now = Instant.now()
        val cutoff = now.minus(Duration.ofMillis(STALE_AFTER_MS))

        var best: CableAdvert? = null
        var alive = 0
        for (advert in advertRelay.received.value) {
            if (advert.lastSeen.isBefore(cutoff)) continue
            alive += 1
            if (best == null || advert.lastSeen.isAfter(best.lastSeen)) {
                best = advert
            }
        }
        _activeCount.value = alive

        if (best == null) {
            if (currentPayloadHex != null) stopPublisher("no fresh peer adverts")
            return
        }
        if (best.payloadHex == currentPayloadHex) return
        if (Duration.between(lastSwapTime, now).toMillis() < DEBOUNCE_MS) return

        val data = AdvertBuilder.buildAdvertiseData(best)
        if (data == null) {
            tracer.log("rebroadcast", "rebroadcast.skip", mapOf(
                "reason" to "unsupported variant",
                "variant" to best.variant.wireName,
                "id" to best.id
            ))
            return
        }

        // Roll the self-published set before the publisher goes on-air, so the
        // loop-guard in AdvertStore sees the new payload immediately.
        synchronized(selfPublishedPayloadHex) {
            currentPayloadHex?.let { selfPublishedPayloadHex.remove(it) }
            val hex = best.payloadHex.lowercase()
            selfPublishedPayloadHex.add(hex)
            currentPayloadHex = hex
        }
        _activeAdvertID.value = best.id
        lastSwapTime = now

        advertiser.start(data)
        tracer.log("rebroadcast", "rebroadcast.publish", mapOf(
            "variant" to best.variant.wireName,
            "id" to best.id,
            "alive" to alive
        ))
    }

    private fun stopPublisher(reason: String) {
        synchronized(selfPublishedPayloadHex) {
            currentPayloadHex?.let { selfPublishedPayloadHex.remove(it) }
            currentPayloadHex = null
        }
        _activeAdvertID.value = null
        advertiser.stop()
        tracer.log("rebroadcast", "rebroadcast.unpublish", mapOf("reason" to reason))
    }

    companion object {
        private const val STALE_SWEEP_MS: Long = 2_000
        private const val STALE_AFTER_MS: Long = 8_000
        private const val DEBOUNCE_MS: Long = 250
    }
}

/**
 * Builds an [AdvertiseData] payload faithful to the source caBLE variant.
 *
 * Unlike the macOS port — which has to pack EID bytes into a 128-bit service
 * UUID because `CBPeripheralManager` rejects raw service-data — Android can
 * emit the original wire format for every variant.
 */
internal object AdvertBuilder {

    fun buildAdvertiseData(advert: CableAdvert): AdvertiseData? = when (advert.variant) {
        AdvertVariant.FidoV2 -> serviceDataAdvert(AdvertClassifier.fidoCableUuid, advert.payload)
        AdvertVariant.GoogleV1, AdvertVariant.GoogleV2 ->
            serviceDataAdvert(AdvertClassifier.googleCableUuid, advert.payload)
        AdvertVariant.GoogleMfgV1 -> manufacturerDataAdvert(advert)
        is AdvertVariant.Unknown -> null
    }

    private fun serviceDataAdvert(
        uuid: android.os.ParcelUuid,
        payload: ByteArray
    ): AdvertiseData = AdvertiseData.Builder()
        .addServiceUuid(uuid)
        .addServiceData(uuid, payload)
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .build()

    private fun manufacturerDataAdvert(advert: CableAdvert): AdvertiseData? {
        // Stored manufacturerData includes the 2-byte LE company-ID prefix.
        // Android's addManufacturerData() prepends the company ID itself, so
        // we strip the prefix before handing the body over.
        val full = advert.manufacturerData ?: return null
        if (full.size < 2) return null
        val body = full.copyOfRange(2, full.size)
        return AdvertiseData.Builder()
            .addManufacturerData(AdvertClassifier.GOOGLE_COMPANY_ID, body)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
    }
}
