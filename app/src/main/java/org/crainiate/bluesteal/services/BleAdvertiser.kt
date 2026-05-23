package org.crainiate.bluesteal.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.crainiate.bluesteal.logging.RelayTracer

/**
 * Thin wrapper around [BluetoothLeAdvertiser] that broadcasts a single BLE
 * advertisement at a time.
 *
 * Unlike CoreBluetooth on macOS (which only accepts service-UUID + local-name
 * for third-party apps), Android exposes the full advert API: service-data,
 * manufacturer-data, TX power, etc. That means we can faithfully re-broadcast
 * every caBLE variant — including [AdvertVariant.GoogleMfgV1] — without the
 * UUID-packing trick the macOS port uses.
 *
 * The caller (typically [RebroadcastService]) is responsible for building the
 * [AdvertiseData] for the variant they want to emit; this class just owns the
 * `BluetoothLeAdvertiser` lifecycle and reports status.
 */
class BleAdvertiser(context: Context, private val tracer: RelayTracer) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Fires after every state transition. `RebroadcastService` listens to surface errors. */
    var onStatusChanged: ((active: Boolean, error: String?) -> Unit)? = null

    private val advertiser: BluetoothLeAdvertiser?
        get() = adapter?.bluetoothLeAdvertiser

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _isAdvertising.value = true
            _lastError.value = null
            tracer.log("ble.pub", "ble.pub.started", mapOf(
                "txPowerLevel" to (settingsInEffect?.txPowerLevel ?: -1),
                "mode" to (settingsInEffect?.mode ?: -1)
            ))
            onStatusChanged?.invoke(true, null)
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            val msg = describeError(errorCode)
            _lastError.value = msg
            tracer.log("ble.pub", "ble.pub.error", mapOf("error" to msg, "code" to errorCode))
            onStatusChanged?.invoke(false, msg)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(data: AdvertiseData, settings: AdvertiseSettings = defaultSettings()) {
        val a = advertiser ?: run {
            val msg = "BluetoothLeAdvertiser unavailable"
            _lastError.value = msg
            tracer.log("ble.pub", "ble.pub.error", mapOf("error" to msg))
            onStatusChanged?.invoke(false, msg)
            return
        }
        // Stop any previous advert before starting a new one. Android's
        // advertiser is single-slot per app and replacing requires an explicit
        // stop on the previous callback instance.
        a.stopAdvertising(callback)
        tracer.log("ble.pub", "ble.pub.start", mapOf(
            "serviceUUIDs" to data.serviceUuids.map { it.uuid.toString() }
        ))
        a.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val a = advertiser ?: return
        a.stopAdvertising(callback)
        if (_isAdvertising.value) {
            _isAdvertising.value = false
            tracer.log("ble.pub", "ble.pub.stop")
            onStatusChanged?.invoke(false, _lastError.value)
        }
    }

    companion object {
        fun defaultSettings(): AdvertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        fun describeError(code: Int): String = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advert too large (31-byte limit)"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many active advertisers"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already advertising"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal BLE error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising unsupported"
            else -> "advertise error $code"
        }
    }
}
