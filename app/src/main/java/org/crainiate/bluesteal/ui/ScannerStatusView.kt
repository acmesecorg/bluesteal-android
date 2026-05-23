package org.crainiate.bluesteal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.crainiate.bluesteal.services.AdvertStore
import org.crainiate.bluesteal.services.BleAdapterState
import org.crainiate.bluesteal.services.CableScanner
import org.crainiate.bluesteal.services.ScanMode

@Composable
fun ScannerStatusView(scanner: CableScanner, store: AdvertStore) {
    val state by scanner.state.collectAsState()
    val isScanning by scanner.isScanning.collectAsState()
    val mode by scanner.mode.collectAsState()
    val total by store.totalDetections.collectAsState()
    val unique by store.adverts.collectAsState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(adapterState = state, isScanning = isScanning)
            Text(
                stateLabel(state, isScanning),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "· ${unique.size}/$total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            // `Modifier.weight(1f)` on the Spacer is what pushes the mode
            // picker + trash icon to the trailing edge. An earlier draft used
            // `Modifier.fillMaxWidth(0.001f)` which compiles to a 0-width
            // spacer and quietly left those controls glued to the totals on
            // the leading edge.
            Spacer(modifier = Modifier.weight(1f))
            ModePicker(mode, scanner::setMode)
            IconButton(onClick = { store.clear() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear adverts")
            }
        }
    }
}

@Composable
private fun StatusDot(adapterState: BleAdapterState, isScanning: Boolean) {
    val color = when {
        adapterState != BleAdapterState.PoweredOn -> Color.Gray
        isScanning -> Color(0xFF4CAF50)
        else -> Color(0xFFFFC107)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun stateLabel(state: BleAdapterState, scanning: Boolean): String = when (state) {
    BleAdapterState.Unknown -> "Bluetooth · unknown"
    BleAdapterState.Unsupported -> "Bluetooth · unsupported"
    BleAdapterState.Unauthorized -> "Bluetooth · permission required"
    BleAdapterState.PoweredOff -> "Bluetooth · off"
    BleAdapterState.PoweredOn -> if (scanning) "Scanning" else "Idle"
}

@Composable
private fun ModePicker(current: ScanMode, onPick: (ScanMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(current.label, style = MaterialTheme.typography.bodySmall)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        for (m in ScanMode.entries) {
            DropdownMenuItem(
                text = { Text(m.label) },
                onClick = {
                    expanded = false
                    onPick(m)
                }
            )
        }
    }
}
