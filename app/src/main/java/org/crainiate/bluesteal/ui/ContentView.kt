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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.crainiate.bluesteal.BluestealApp

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
@Composable
fun ContentView(app: BluestealApp) {
    Scaffold { padding ->
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
