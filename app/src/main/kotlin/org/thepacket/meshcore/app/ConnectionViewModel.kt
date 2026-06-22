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

/** Which screen the UI is showing. */
sealed interface Screen {
    data object Connect : Screen
    data object Main : Screen
    data class Conversation(val conversationId: String, val title: String) : Screen
}

/** Bottom-nav tabs within the connected (Main) screen. */
enum class MainTab { Chats, Heard, Packets, Stats, Map }

/** Connection / scanning state (chat + instrumentation state live in [MeshSession]). */
data class UiState(
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val linkState: LinkState = LinkState.Disconnected,
    val error: String? = null,
    val screen: Screen = Screen.Connect,
    val tab: MainTab = MainTab.Chats,
)

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = CompanionScanner(app)
    private val link = NordicMeshCoreLink(app)
    val session = MeshSession(link, viewModelScope)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            link.state.collect { s ->
                _ui.update { st ->
                    val screen = when {
                        s == LinkState.Connected && st.screen is Screen.Connect -> Screen.Main
                        s == LinkState.Disconnected || s == LinkState.Failed -> Screen.Connect
                        else -> st.screen
                    }
                    st.copy(linkState = s, screen = screen)
                }
            }
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
                session.start() // APP_START handshake -> self info -> contacts sync
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { runCatching { link.disconnect() } }
        session.reset()
        _ui.update { it.copy(screen = Screen.Connect) }
    }

    /** Route a send by conversation id: "ch:N" → channel, else → the matching contact DM. */
    fun sendMessage(conversationId: String, text: String) {
        if (conversationId.startsWith("ch:")) {
            val idx = conversationId.removePrefix("ch:").toIntOrNull() ?: return
            session.sendChannel(idx, text)
        } else {
            val contact = session.contacts.value.firstOrNull { Conversation.dmId(it) == conversationId } ?: return
            session.sendDirect(contact, text)
        }
    }

    // ---- navigation ----
    fun setTab(tab: MainTab) = _ui.update { it.copy(tab = tab) }

    fun openConversation(id: String, title: String) =
        _ui.update { it.copy(screen = Screen.Conversation(id, title)) }

    fun backToHome() = _ui.update { it.copy(screen = Screen.Main) }
}
