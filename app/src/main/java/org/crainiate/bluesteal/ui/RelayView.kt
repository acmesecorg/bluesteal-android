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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.crainiate.bluesteal.services.AdvertRelay
import org.crainiate.bluesteal.services.RebroadcastService
import org.crainiate.bluesteal.services.RelayClient
import org.crainiate.bluesteal.services.SignalingClient

/**
 * Relay control bar.
 *
 * On a phone the macOS one-row layout doesn't fit, so this is a Column:
 *
 * - Row 1: `[Status badge]    [optional Disconnect / Cancel right-aligned]`
 * - Row 2: state-specific controls (Host/Join, code display, counters, etc.)
 *
 * The state-specific row is laid out with `Modifier.weight(1f)` spacers so
 * primary action buttons reliably sit at the trailing edge regardless of
 * device width.
 */
@Composable
fun RelayView(
    relay: RelayClient,
    advertRelay: AdvertRelay,
    rebroadcast: RebroadcastService
) {
    val state by relay.state.collectAsState()

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
                is RelayClient.State.Idle, is RelayClient.State.Closed ->
                    IdleControls(relay = relay)

                is RelayClient.State.Hosting ->
                    HostingControls(
                        code = (state as RelayClient.State.Hosting).code,
                        onCancel = { relay.disconnect(reason = "cancelled") }
                    )

                is RelayClient.State.Joining, is RelayClient.State.Negotiating ->
                    NegotiatingControls(
                        label = stateLabel(state),
                        onCancel = { relay.disconnect(reason = "cancelled") }
                    )

                is RelayClient.State.Open ->
                    OpenControls(relay = relay, rebroadcast = rebroadcast)
            }
        }
    }
}

@Composable
private fun StatusBadge(state: RelayClient.State) {
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
        Text("Relay · " + stateLabel(state), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun stateLabel(state: RelayClient.State): String = when (state) {
    is RelayClient.State.Idle -> "idle"
    is RelayClient.State.Hosting -> "hosting"
    is RelayClient.State.Joining -> "joining ${state.code}"
    is RelayClient.State.Negotiating -> "negotiating"
    is RelayClient.State.Open -> "open"
    is RelayClient.State.Closed -> "closed (${state.reason})"
}

// ---- Idle / Hosting / Negotiating / Open ----

@Composable
private fun IdleControls(relay: RelayClient) {
    var showJoinField by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }

    if (showJoinField) {
        // Joining: TextField gets the full width, Connect + Cancel sit on the
        // next row. Hides the Host button — user is choosing the other path.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = joinCode,
                onValueChange = { raw -> joinCode = normaliseCode(raw) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("${SignalingClient.codeLength}-letter code") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters
                )
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    showJoinField = false
                    joinCode = ""
                }) { Text("Cancel") }
                Button(
                    onClick = {
                        if (joinCode.length == SignalingClient.codeLength) {
                            relay.join(joinCode)
                            showJoinField = false
                            joinCode = ""
                        }
                    },
                    enabled = joinCode.length == SignalingClient.codeLength
                ) { Text("Connect") }
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { relay.host() }) { Text("Host") }
            OutlinedButton(onClick = { showJoinField = true }) { Text("Join…") }
        }
    }
}

@Composable
private fun HostingControls(code: String, onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Code",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Text(
            "waiting for peer…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
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
private fun OpenControls(relay: RelayClient, rebroadcast: RebroadcastService) {
    val sent by relay.messagesSent.collectAsState()
    val received by relay.messagesReceived.collectAsState()
    val hbt by relay.heartbeatCount.collectAsState()
    val turn by relay.isUsingTurn.collectAsState()
    val rebroadcasting by rebroadcast.isActive.collectAsState()
    val rebroadcastError by rebroadcast.lastError.collectAsState()
    val activeCount by rebroadcast.activeCount.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("sent $sent", style = MaterialTheme.typography.bodySmall)
            Text("recv $received", style = MaterialTheme.typography.bodySmall)
            Text("hbt $hbt", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { relay.disconnect() }) { Text("Disconnect") }
        }
        if (turn || rebroadcastError != null || rebroadcasting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                if (rebroadcastError != null) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "Rebroadcast: $rebroadcastError",
                                style = MaterialTheme.typography.labelSmall
                            )
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
                            Text(
                                "Rebroadcasting $activeCount",
                                style = MaterialTheme.typography.labelSmall
                            )
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

/**
 * Force the typed code into the pairing alphabet `ABCDEFGHJKMNPQRSTUVWXYZ`.
 * Strips spaces, ambiguous I/L/O, digits, and anything else the IME emits
 * regardless of the `KeyboardCapitalization.Characters` hint (Gboard ignores
 * it). Truncates to [SignalingClient.codeLength] so the field can never hold
 * more than the wire format accepts.
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
