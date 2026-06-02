package org.crainiate.bluesteal.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.crainiate.bluesteal.BluestealApp

/**
 * Single-activity host. Mirrors the macOS `WindowGroup { ContentView() }`
 * shape — Compose `ContentView` is the only screen.
 *
 * On first launch we ask for the BLE runtime permissions appropriate to the
 * OS version (API 31+ uses the new BLUETOOTH_* triple; older devices need
 * ACCESS_FINE_LOCATION because BLE scan results carry location data).
 */
class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BluestealApp

        handleAutoDrive(app)

        setContent {
            var granted by remember { mutableStateOf(hasAllPermissions()) }
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                granted = results.values.all { it } || hasAllPermissions()
                if (granted) {
                    app.scanner.refreshAdapterState()
                    app.scanner.start()
                }
            }

            LaunchedEffect(Unit) {
                if (granted) {
                    app.scanner.refreshAdapterState()
                    app.scanner.start()
                } else {
                    launcher.launch(requiredPermissions)
                }
            }

            BluestealTheme {
                ContentView(app = app)
            }
        }
    }

    override fun onDestroy() {
        // The Application keeps service singletons alive across config changes;
        // only stop scanning when the activity itself is being destroyed.
        if (isFinishing) {
            (application as BluestealApp).scanner.stop()
        }
        super.onDestroy()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Auto-drives Host/Join from launch-intent extras so the flow can be driven
     * over adb without tapping the UI, e.g.:
     *   adb shell am start -n org.crainiate.bluesteal/.ui.MainActivity --ez host true
     *   adb shell am start -n org.crainiate.bluesteal/.ui.MainActivity --es join ABCDE-123456
     */
    private fun handleAutoDrive(app: BluestealApp) {
        val sendqr = intent?.getStringExtra("sendqr")
        val showqr = intent?.getStringExtra("showqr")
        when {
            sendqr != null -> {
                app.tracer.log("app", "app.autosendqr", mapOf("len" to sendqr.length))
                app.beginSendQrOnOpen(sendqr)
            }
            showqr != null -> {
                app.tracer.log("app", "app.autoshowqr", mapOf("len" to showqr.length))
                app.qrRelay.show(showqr)
            }
            intent?.getBooleanExtra("host", false) == true -> {
                app.tracer.log("app", "app.autohost")
                app.beginHost()
            }
            else -> intent?.getStringExtra("join")?.let { token ->
                app.tracer.log("app", "app.autojoin", mapOf("token" to token))
                app.beginJoin(token)
            }
        }
    }
}
