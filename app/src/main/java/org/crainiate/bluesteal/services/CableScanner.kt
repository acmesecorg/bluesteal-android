package org.crainiate.bluesteal.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * One raw discovery event surfaced from `BluetoothLeScanner`. The store
 * handles dedup and classification.
 *
 * Notes vs CoreBluetooth:
 * - [peripheralID] is a UUID derived deterministically from the device MAC
 *   (Android's per-app pseudo-MAC is unstable across scans on API 31+ unless
 *   pairing has occurred), so the dedupe key still has UUID shape.
 * - [serviceData] keys are the ParcelUuid form, e.g. `0000fff9-…`.
 * - [manufacturerData] is the **prefixed** form `[companyId(LE) ‖ …]` to
 *   match the Swift wire shape; the scanner prepends company-id bytes before
 *   surfacing the event.
 */
data class RawAdvertEvent(
    val timestampMillis: Long,
    val peripheralID: UUID,
    val serviceData: Map<ParcelUuid, ByteArray>,
    val advertisedServiceUUIDs: List<String>,   // short or long form, upper case
    val localName: String?,
    val manufacturerData: ByteArray?,
    val txPowerLevel: Int?,
    val isConnectable: Boolean?,
    val rssi: Int
)

/** Display label for the BLE adapter state — matches `CableScanner` on macOS. */
enum class BleAdapterState { Unknown, Unsupported, Unauthorized, PoweredOff, PoweredOn }

enum class ScanMode(val label: String) {
    /** No service filter — catches every advert (including manufacturer-data). */
    Promiscuous("Promiscuous"),
    /** Filter on FFF9/FDE2 service UUIDs only. Lowest CPU. */
    Filtered("Filtered (FFF9/FDE2)"),
    /** Promiscuous + dump every advert to the diagnostic log (for offline triage). */
    Diagnostic("Diagnostic")
}

/**
 * Owns the [BluetoothLeScanner]. Filters every discovery callback for
 * service data under a caBLE UUID or a Google manufacturer-data v1 blob and
 * forwards the rest to [onEvent]. Exposes scanner state + scan mode as
 * [StateFlow]s so Compose can observe.
 *
 * Designed to be a singleton owned by the Application.
 */
class CableScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(BleAdapterState.Unknown)
    val state: StateFlow<BleAdapterState> = _state.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _mode = MutableStateFlow(ScanMode.Promiscuous)
    val mode: StateFlow<ScanMode> = _mode.asStateFlow()

    var onEvent: ((RawAdvertEvent) -> Unit)? = null

    private var leScanner: BluetoothLeScanner? = null
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handleScanResult(r)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: errorCode=$errorCode")
            _isScanning.value = false
        }
    }

    init {
        refreshAdapterState()
    }

    fun setMode(mode: ScanMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        if (_isScanning.value) restart()
    }

    /** Call after the user grants BLUETOOTH_SCAN / BLUETOOTH_CONNECT. */
    fun refreshAdapterState() {
        _state.value = when {
            adapter == null -> BleAdapterState.Unsupported
            !adapter.isEnabled -> BleAdapterState.PoweredOff
            else -> BleAdapterState.PoweredOn
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (_isScanning.value) return
        val a = adapter ?: return
        if (!a.isEnabled) {
            _state.value = BleAdapterState.PoweredOff
            return
        }
        val scanner = a.bluetoothLeScanner ?: return
        leScanner = scanner

        // Filtered mode: narrow the OS-level filter to caBLE UUIDs. Promiscuous
        // and diagnostic both scan with an empty filter list so 128-bit UUIDs
        // and manufacturer-data adverts (which the kernel filter ignores by
        // default) all reach us.
        val filters: List<ScanFilter> = when (_mode.value) {
            ScanMode.Filtered -> AdvertClassifier.cableUuids.map {
                ScanFilter.Builder().setServiceUuid(it).build()
            }
            ScanMode.Promiscuous, ScanMode.Diagnostic -> emptyList()
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            // Foreground duplicate-allowed reporting — matches CoreBluetooth's
            // `CBCentralManagerScanOptionAllowDuplicatesKey: true`.
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0L)
            .build()

        scanner.startScan(filters, settings, callback)
        _state.value = BleAdapterState.PoweredOn
        _isScanning.value = true
        Log.d(TAG, "scan start, mode=${_mode.value}")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        leScanner?.stopScan(callback)
        _isScanning.value = false
        Log.d(TAG, "scan stop")
    }

    private fun restart() {
        stop()
        start()
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return

        val serviceData: Map<ParcelUuid, ByteArray> = record.serviceData ?: emptyMap()
        val hasCableServiceData = serviceData.keys.any(AdvertClassifier::isCableUuid)

        // Re-prefix Google manufacturer data so the classifier sees the same
        // shape Swift CoreBluetooth surfaces.
        val rawGoogle = record.manufacturerSpecificData
            ?.get(AdvertClassifier.GOOGLE_COMPANY_ID)
        val prefixedMfg: ByteArray? = rawGoogle?.let { body ->
            val out = ByteArray(2 + body.size)
            out[0] = (AdvertClassifier.GOOGLE_COMPANY_ID and 0xff).toByte()
            out[1] = ((AdvertClassifier.GOOGLE_COMPANY_ID ushr 8) and 0xff).toByte()
            System.arraycopy(body, 0, out, 2, body.size)
            out
        }
        val hasCableMfgData = prefixedMfg != null &&
            AdvertClassifier.classifyManufacturerData(prefixedMfg) != null

        if (!hasCableServiceData && !hasCableMfgData) return

        val advertisedUuids: List<String> =
            (record.serviceUuids ?: emptyList()).map(AdvertClassifier::toShortForm)

        val event = RawAdvertEvent(
            timestampMillis = System.currentTimeMillis(),
            peripheralID = macToUuid(result.device.address),
            serviceData = serviceData,
            advertisedServiceUUIDs = advertisedUuids,
            localName = record.deviceName,
            manufacturerData = prefixedMfg,
            txPowerLevel = record.txPowerLevel.takeIf { it != Int.MIN_VALUE },
            isConnectable = isConnectableSafe(result),
            rssi = result.rssi
        )
        onEvent?.invoke(event)
    }

    private fun isConnectableSafe(result: ScanResult): Boolean? = try {
        // API 26+; on API 25 this throws.
        result.isConnectable
    } catch (t: Throwable) {
        null
    }

    /** Stable v3 UUID derived from the device MAC. Matches the CoreBluetooth shape. */
    private fun macToUuid(mac: String): UUID =
        UUID.nameUUIDFromBytes("ble-mac:$mac".toByteArray())

    companion object {
        private const val TAG = "CableScanner"

        /** Runtime permissions that must be granted before [start] will work. */
        val requiredPermissions: Array<String> = if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
}
