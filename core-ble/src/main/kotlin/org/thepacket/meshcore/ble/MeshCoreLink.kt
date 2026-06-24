package org.thepacket.meshcore.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.thepacket.meshcore.protocol.Incoming

/** Connection lifecycle for a companion link. */
enum class LinkState { Disconnected, Connecting, Bonding, Connected, Disconnecting, Failed }

/**
 * Transport-agnostic view of a MeshCore companion connection. The app and any
 * view-models depend only on this — never on Nordic/BLE types — so the underlying
 * transport (BLE today, USB/Wi-Fi later) can be swapped without touching callers.
 *
 * Decoded frames arrive on [incoming] as [Incoming] values (see core-protocol).
 */
interface MeshCoreLink {
    val state: StateFlow<LinkState>

    /** Decoded frames from the device, including unsolicited pushes. Hot, replay 0. */
    val incoming: SharedFlow<Incoming>

    /** Begin connecting. Completes (state -> Connected) once the NUS notifications are enabled. */
    suspend fun connect()

    suspend fun disconnect()

    /** Send one already-encoded request frame (see protocol Requests.*). */
    suspend fun send(frame: ByteArray)
}
