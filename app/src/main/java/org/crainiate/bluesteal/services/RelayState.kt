package org.crainiate.bluesteal.services

/**
 * Session role/state shared by the relay transport. Extracted from the former
 * `RelayClient` (the WebRTC/MQTT path) when that transport was removed — the
 * caBLE tunnel is now the only transport, but it keeps the same lifecycle
 * vocabulary. Mirrors Windows `Services/RelayState.cs`.
 */

/** Which side of a relay/tunnel session this instance is. */
enum class RelayRole { Host, Peer }

/** Lifecycle state of a relay/tunnel session, shared by all transports. */
sealed class RelayState {
    data object Idle : RelayState()
    data class Hosting(val code: String) : RelayState()
    data class Joining(val code: String) : RelayState()
    data object Negotiating : RelayState()
    data class Open(val role: RelayRole) : RelayState()
    data class Closed(val reason: String) : RelayState()
}
