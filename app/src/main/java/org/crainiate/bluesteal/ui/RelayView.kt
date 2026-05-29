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
import androidx.compose.material3.FilterChip
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
import org.crainiate.bluesteal.services.RebroadcastService
import org.crainiate.bluesteal.services.RelayClient
import org.crainiate.bluesteal.services.SignalingClient
import org.crainiate.bluesteal.services.tunnel.TunnelKeyDerivation
import org.crainiate.bluesteal.services.tunnel.TunnelTransport

/**
 * Relay control bar. Two transports share this bar: the WebRTC `RelayClient`
 * and the caBLE `TunnelTransport`. The tunnel "owns" the UI whenever it is past
 * idle/closed; otherwise WebRTC drives it. On the host side a picker chooses the
 * transport; on the join side the input shape (5-char code vs 11-char token)
 * routes automatically — mirrors macOS/iOS `RelayView`.
 */
enum class TransportKind(val label: String) { WebRTC("WebRTC"), Tunnel("Tunnel") }

@Composable
fun RelayView(
    relay: RelayClient,
    advertRelay: AdvertRelay,
    rebroadcast: RebroadcastService,
    tunnel: TunnelTransport
) {
    val relayState by relay.state.collectAsState()
    val tunnelState by tunnel.state.collectAsState()

    val tunnelActive = tunnelState !is RelayClient.State.Idle && tunnelState !is RelayClient.State.Closed
    val effectiveState = if (tunnelActive) tunnelState else relayState

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(state = effectiveState, tunnelActive = tunnelActive)
            }

            when (effectiveState) {
                is RelayClient.State.Idle, is RelayClient.State.Closed ->
                    IdleControls(relay = relay, advertRelay = advertRelay, tunnel = tunnel)

                is RelayClient.State.Hosting ->
                    HostingControls(
                        code = (effectiveState as RelayClient.State.Hosting).code,
                        tunnelActive = tunnelActive,
                        token = tunnel.token.collectAsState().value,
                        onCancel = { cancelOrDisconnect(tunnelActive, relay, advertRelay, tunnel, "cancelled") }
                    )

                is RelayClient.State.Joining, is RelayClient.State.Negotiating ->
                    NegotiatingControls(
                        label = stateLabel(effectiveState, tunnelActive),
                        onCancel = { cancelOrDisconnect(tunnelActive, relay, advertRelay, tunnel, "cancelled") }
                    )

                is RelayClient.State.Open ->
                    OpenControls(
                        relay = relay,
                        tunnel = tunnel,
                        rebroadcast = rebroadcast,
                        tunnelActive = tunnelActive,
                        onDisconnect = { cancelOrDisconnect(tunnelActive, relay, advertRelay, tunnel, "user") }
                    )
            }
        }
    }
}

@Composable
private fun StatusBadge(state: RelayClient.State, tunnelActive: Boolean) {
    val color = when (state) {
        is RelayClient.State.Idle, is RelayClient.State.Closed -> Color.Gray
        is RelayClient.State.Hosting,
        is RelayClient.State.Joining,
        is RelayClient.State.Negotiating -> Color(0xFFFF9800)
        is RelayClient.State.Open -> Color(0xFF4CAF50)
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text("Relay · " + stateLabel(state, tunnelActive), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun stateLabel(state: RelayClient.State, tunnelActive: Boolean): String {
    val prefix = if (tunnelActive) "tunnel " else ""
    return when (state) {
        is RelayClient.State.Idle -> "idle"
        is RelayClient.State.Hosting -> "${prefix}hosting"
        is RelayClient.State.Joining -> "${prefix}joining ${state.code}"
        is RelayClient.State.Negotiating -> "${prefix}negotiating"
        is RelayClient.State.Open -> "${prefix}open"
        is RelayClient.State.Closed -> "closed (${state.reason})"
    }
}

// ---- Idle / Hosting / Negotiating / Open ----

@Composable
private fun IdleControls(relay: RelayClient, advertRelay: AdvertRelay, tunnel: TunnelTransport) {
    var transport by remember { mutableStateOf(TransportKind.Tunnel) }
    var showJoinField by remember { mutableStateOf(false) }
    var joinInput by remember { mutableStateOf("") }

    if (showJoinField) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = joinInput,
                onValueChange = { joinInput = it.uppercase().trim() },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("code or token") },
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
                        submitJoin(joinInput, relay, advertRelay, tunnel)
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
            FilterChip(
                selected = transport == TransportKind.WebRTC,
                onClick = { transport = TransportKind.WebRTC },
                label = { Text(TransportKind.WebRTC.label, style = MaterialTheme.typography.labelMedium) }
            )
            FilterChip(
                selected = transport == TransportKind.Tunnel,
                onClick = { transport = TransportKind.Tunnel },
                label = { Text(TransportKind.Tunnel.label, style = MaterialTheme.typography.labelMedium) }
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = { startHost(transport, relay, advertRelay, tunnel) }) { Text("Host") }
            OutlinedButton(onClick = { showJoinField = true }) { Text("Join…") }
        }
    }
}

@Composable
private fun HostingControls(
    code: String,
    tunnelActive: Boolean,
    token: String?,
    onCancel: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val label = if (tunnelActive) "Token" else "Code"
    val ready = !tunnelActive || token != null
    val displayValue = if (tunnelActive) (token ?: "waiting…") else code

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            label,
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
    relay: RelayClient,
    tunnel: TunnelTransport,
    rebroadcast: RebroadcastService,
    tunnelActive: Boolean,
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
            if (tunnelActive) {
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
            } else {
                val sent by relay.messagesSent.collectAsState()
                val received by relay.messagesReceived.collectAsState()
                val hbt by relay.heartbeatCount.collectAsState()
                val turn by relay.isUsingTurn.collectAsState()
                Text("sent $sent", style = MaterialTheme.typography.bodySmall)
                Text("recv $received", style = MaterialTheme.typography.bodySmall)
                Text("hbt $hbt", style = MaterialTheme.typography.bodySmall)
                if (turn) {
                    AssistChip(
                        onClick = {},
                        label = { Text("TURN", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0x33FF9800),
                            labelColor = Color(0xFFFF9800)
                        )
                    )
                }
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

private fun startHost(
    transport: TransportKind,
    relay: RelayClient,
    advertRelay: AdvertRelay,
    tunnel: TunnelTransport
) {
    when (transport) {
        TransportKind.WebRTC -> relay.host()
        TransportKind.Tunnel -> {
            val code = SignalingClient.makeCode()
            wireTunnelSend(advertRelay, tunnel)
            tunnel.host(code)
        }
    }
}

private fun submitJoin(
    rawInput: String,
    relay: RelayClient,
    advertRelay: AdvertRelay,
    tunnel: TunnelTransport
) {
    val raw = rawInput.trim()
    if (TunnelKeyDerivation.isTunnelToken(raw)) {
        wireTunnelSend(advertRelay, tunnel)
        tunnel.join(raw.uppercase())
    } else {
        val code = normaliseCode(raw)
        if (code.length == SignalingClient.codeLength) relay.join(code)
    }
}

private fun cancelOrDisconnect(
    tunnelActive: Boolean,
    relay: RelayClient,
    advertRelay: AdvertRelay,
    tunnel: TunnelTransport,
    reason: String
) {
    if (tunnelActive) {
        advertRelay.tunnelSend = null
        tunnel.disconnect()
    } else {
        relay.disconnect(reason)
    }
}

private fun joinInputValid(input: String): Boolean =
    TunnelKeyDerivation.isTunnelToken(input) ||
        normaliseCode(input).length == SignalingClient.codeLength

/**
 * Force the typed code into the pairing alphabet `ABCDEFGHJKMNPQRSTUVWXYZ`.
 * Strips spaces, ambiguous I/L/O, digits, and anything else the IME emits.
 */
private fun normaliseCode(raw: String): String {
    val allowed = SignalingClient.codeAlphabet.toCharSet()
    val sb = StringBuilder()
    for (c in raw.uppercase()) {
        if (sb.length >= SignalingClient.codeLength) break
        if (allowed.contains(c)) sb.append(c)
    }
    return sb.toString()
}

private fun CharArray.toCharSet(): HashSet<Char> {
    val s = HashSet<Char>(size)
    for (c in this) s.add(c)
    return s
}
