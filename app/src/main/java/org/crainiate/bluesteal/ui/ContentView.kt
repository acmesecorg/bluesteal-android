package org.crainiate.bluesteal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.crainiate.bluesteal.BluestealApp
import org.crainiate.bluesteal.services.RelayState

/**
 * Top-level screen. Mirrors the macOS `ContentView`:
 *
 * - `ScannerStatusView` — adapter state, mode picker, totals, clear button.
 * - `RelayView`         — host/join/disconnect controls, counters,
 *                         re-broadcast chip.
 * - The list           — "Local" (this device's scanned adverts) + "From peer"
 *                         (adverts pushed by a paired host).
 *
 * Compose composables read state via `StateFlow.collectAsState`; service
 * singletons live on [BluestealApp] (Application).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentView(app: BluestealApp) {
    val clipboard = LocalClipboardManager.current
    val tunnelState by app.tunnel.state.collectAsState()
    val canSend = tunnelState is RelayState.Open
    var scanned by remember { mutableStateOf<String?>(null) }
    val received by app.qrRelay.received.collectAsState()

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null && FidoQR.isFidoUri(contents)) scanned = contents
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Blue Steal") },
                actions = {
                    IconButton(onClick = {
                        scanLauncher.launch(ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Point at a FIDO QR code")
                            setBeepEnabled(false)
                            setOrientationLocked(false)
                        })
                    }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR code")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScannerStatusView(scanner = app.scanner, store = app.store)
            HorizontalDivider()
            RelayView(
                advertRelay = app.advertRelay,
                rebroadcast = app.rebroadcast,
                tunnel = app.tunnel
            )
            HorizontalDivider()
            AdvertList(app = app)
        }
    }

    // Result of a scan: render + Copy / Send.
    scanned?.let { uri ->
        ScannedResultSheet(
            uri = uri,
            canSend = canSend,
            onCopy = { clipboard.setText(AnnotatedString(uri)) },
            onSend = { app.qrRelay.send(uri) },
            onDismiss = { scanned = null }
        )
    }
    // A QR pushed by the peer: slide-up display modal.
    received?.let { uri ->
        ReceivedQrSheet(uri = uri, onDismiss = { app.qrRelay.dismiss() })
    }
}

@Composable
private fun AdvertList(app: BluestealApp) {
    val local by app.store.adverts.collectAsState()
    val peer by app.advertRelay.received.collectAsState()
    val activeId by app.rebroadcast.activeAdvertID.collectAsState()

    if (local.isEmpty() && peer.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SettingsInputAntenna,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Waiting for caBLE adverts",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Listening for service data under UUIDs 0xFFF9 (FIDO) and 0xFDE2 (Google). " +
                "Start a hybrid sign-in flow on a nearby phone or laptop.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (local.isNotEmpty()) {
                item {
                    SectionHeader("Local")
                }
                items(local, key = { "local|" + it.id }) { advert ->
                    AdvertRowView(
                        advert = advert,
                        source = AdvertSource.Local,
                        isRebroadcasting = false
                    )
                    HorizontalDivider()
                }
            }
            if (peer.isNotEmpty()) {
                item {
                    SectionHeader("From peer")
                }
                items(peer, key = { "peer|" + it.id }) { advert ->
                    AdvertRowView(
                        advert = advert,
                        source = AdvertSource.Relay,
                        isRebroadcasting = activeId == advert.id
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge
    )
}
