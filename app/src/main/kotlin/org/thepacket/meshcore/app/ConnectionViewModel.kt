package org.thepacket.meshcore.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.thepacket.meshcore.ble.CompanionScanner
import org.thepacket.meshcore.ble.LinkState
import org.thepacket.meshcore.ble.NordicMeshCoreLink
import org.thepacket.meshcore.ble.ScannedDevice
import org.thepacket.meshcore.protocol.Incoming
import org.thepacket.meshcore.protocol.Requests
import org.thepacket.meshcore.protocol.SelfInfo

/**
 * Drives the P0 flow: scan for companions → connect → APP_START handshake →
 * show the device's own [SelfInfo]. The view-model owns the link and decodes
 * the handshake reply; everything else (settings, chat, …) builds on this.
 */
data class UiState(
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val linkState: LinkState = LinkState.Disconnected,
    val self: SelfInfo? = null,
    val error: String? = null,
)

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = CompanionScanner(app)
    private val link = NordicMeshCoreLink(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            link.state.collect { s -> _ui.update { it.copy(linkState = s) } }
        }
        viewModelScope.launch {
            link.incoming.collect(::onFrame)
        }
    }

    fun startScan() {
        if (scanJob != null) return
        _ui.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        scanJob = viewModelScope.launch {
            try {
                scanner.scan().collect { found ->
                    _ui.update { st ->
                        val merged = (st.devices.filterNot { it.device.address == found.device.address } + found)
                            .sortedByDescending { it.rssi }
                        st.copy(devices = merged)
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message, scanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _ui.update { it.copy(scanning = false) }
    }

    fun connect(d: ScannedDevice) {
        stopScan()
        viewModelScope.launch {
            try {
                link.connect(d.device)
                // Handshake: first frame after connect; reply is RESP_CODE_SELF_INFO.
                link.send(Requests.appStart())
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { runCatching { link.disconnect() } }
        _ui.update { it.copy(self = null) }
    }

    private fun onFrame(frame: Incoming) {
        when (frame) {
            is Incoming.Self -> _ui.update { it.copy(self = frame.info, error = null) }
            is Incoming.Err -> _ui.update { it.copy(error = "device error ${frame.code}") }
            Incoming.Disabled -> _ui.update { it.copy(error = "companion mode disabled on device") }
            else -> Unit // other frames handled by feature view-models later
        }
    }
}
