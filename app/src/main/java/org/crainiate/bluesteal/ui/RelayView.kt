package org.crainiate.bluesteal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.crainiate.bluesteal.services.AdvertRelay
import org.crainiate.bluesteal.services.PairingCode
import org.crainiate.bluesteal.services.RebroadcastService
import org.crainiate.bluesteal.services.RelayState
import org.crainiate.bluesteal.services.tunnel.TunnelKeyDerivation
import org.crainiate.bluesteal.services.tunnel.TunnelTransport

/**
 * Relay control bar. The caBLE [TunnelTransport] is the only transport: the
 * host generates an OOB token (code-routing) the peer pastes; the join input
 * accepts that token. Mirrors macOS/iOS `RelayView`.
 */
@Composable
fun RelayView(
    advertRelay: AdvertRelay,
    rebroadcast: RebroadcastService,
    tunnel: TunnelTransport
) {
    val state by tunnel.state.collectAsState()

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(state = state)
            }

            when (state) {
                is RelayState.Idle, is RelayState.Closed ->
                    IdleControls(advertRelay = advertRelay, tunnel = tunnel)

                is RelayState.Hosting ->
                    HostingControls(
                        token = tunnel.token.collectAsState().value,
                        onCancel = { cancelOrDisconnect(advertRelay, tunnel) }
                    )

                is RelayState.Joining, is RelayState.Negotiating ->
                    NegotiatingControls(
                        label = stateLabel(state),
                        onCancel = { cancelOrDisconnect(advertRelay, tunnel) }
                    )

                is RelayState.Open ->
                    OpenControls(
                        tunnel = tunnel,
                        rebroadcast = rebroadcast,
                        onDisconnect = { cancelOrDisconnect(advertRelay, tunnel) }
                    )
            }
        }
    }
}

@Composable
private fun StatusBadge(state: RelayState) {
    val color = when (state) {
        is RelayState.Idle, is RelayState.Closed -> Color.Gray
        is RelayState.Hosting,
        is RelayState.Joining,
        is RelayState.Negotiating -> Color(0xFFFF9800)
        is RelayState.Open -> Color(0xFF4CAF50)
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text("Relay · " + stateLabel(state), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun stateLabel(state: RelayState): String = when (state) {
    is RelayState.Idle -> "idle"
    is RelayState.Hosting -> "tunnel hosting"
    is RelayState.Joining -> "tunnel joining ${state.code}"
    is RelayState.Negotiating -> "tunnel negotiating"
    is RelayState.Open -> "tunnel open"
    is RelayState.Closed -> "closed (${state.reason})"
}

// ---- Idle / Hosting / Negotiating / Open ----

@Composable
private fun IdleControls(advertRelay: AdvertRelay, tunnel: TunnelTransport) {
    var showJoinField by remember { mutableStateOf(false) }
    var joinInput by remember { mutableStateOf("") }

    if (showJoinField) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = joinInput,
                onValueChange = { joinInput = it.uppercase().trim() },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("token (e.g. ABCDE-123456)") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    showJoinField = false
                    joinInput = ""
                }) { Text("Cancel") }
                Button(
                    onClick = {
                        submitJoin(joinInput, advertRelay, tunnel)
                        showJoinField = false
                        joinInput = ""
                    },
                    enabled = joinInputValid(joinInput)
                ) { Text("Connect") }
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { startTunnelHost(advertRelay, tunnel) }) { Text("Host") }
            OutlinedButton(onClick = { showJoinField = true }) { Text("Join…") }
        }
    }
}

@Composable
private fun HostingControls(
    token: String?,
    onCancel: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val ready = token != null
    val displayValue = token ?: "waiting…"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Token",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = displayValue,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(displayValue)) },
            enabled = ready
        ) { Text("Copy") }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun NegotiatingControls(label: String, onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun OpenControls(
    tunnel: TunnelTransport,
    rebroadcast: RebroadcastService,
    onDisconnect: () -> Unit
) {
    val rebroadcasting by rebroadcast.isActive.collectAsState()
    val rebroadcastError by rebroadcast.lastError.collectAsState()
    val activeCount by rebroadcast.activeCount.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val sent by tunnel.messagesSent.collectAsState()
            val received by tunnel.messagesReceived.collectAsState()
            val hbt by tunnel.heartbeatCount.collectAsState()
            val hop by tunnel.hopNumber.collectAsState()
            Text("sent $sent", style = MaterialTheme.typography.bodySmall)
            Text("recv $received", style = MaterialTheme.typography.bodySmall)
            Text("hbt $hbt", style = MaterialTheme.typography.bodySmall)
            if (hop > 0) {
                AssistChip(
                    onClick = {},
                    label = { Text("hop $hop", style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color(0x332196F3),
                        labelColor = Color(0xFF2196F3)
                    )
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        if (rebroadcastError != null || rebroadcasting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (rebroadcastError != null) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text("Rebroadcast: $rebroadcastError", style = MaterialTheme.typography.labelSmall)
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0x33F44336),
                            labelColor = Color(0xFFF44336)
                        )
                    )
                } else if (rebroadcasting) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text("Rebroadcasting $activeCount", style = MaterialTheme.typography.labelSmall)
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0x334CAF50),
                            labelColor = Color(0xFF4CAF50)
                        )
                    )
                }
            }
        }
    }
}

// ---- Actions ----

private fun wireTunnelSend(advertRelay: AdvertRelay, tunnel: TunnelTransport) {
    advertRelay.tunnelSend = { bytes -> tunnel.send(bytes) }
}

private fun startTunnelHost(advertRelay: AdvertRelay, tunnel: TunnelTransport) {
    wireTunnelSend(advertRelay, tunnel)
    tunnel.host(PairingCode.make())
}

private fun submitJoin(rawInput: String, advertRelay: AdvertRelay, tunnel: TunnelTransport) {
    val raw = rawInput.trim()
    if (!TunnelKeyDerivation.isTunnelToken(raw)) return
    wireTunnelSend(advertRelay, tunnel)
    tunnel.join(raw.uppercase())
}

private fun cancelOrDisconnect(advertRelay: AdvertRelay, tunnel: TunnelTransport) {
    advertRelay.tunnelSend = null
    tunnel.disconnect()
}

private fun joinInputValid(input: String): Boolean =
    TunnelKeyDerivation.isTunnelToken(input.trim())
