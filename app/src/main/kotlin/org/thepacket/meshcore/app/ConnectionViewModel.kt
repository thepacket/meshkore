package org.thepacket.meshcore.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.thepacket.meshcore.ble.LinkState
import org.thepacket.meshcore.ble.ScannedDevice

/** Which screen the UI is showing. */
sealed interface Screen {
    data object Connect : Screen
    data object Main : Screen
    data class Conversation(val conversationId: String, val title: String) : Screen
}

/** Bottom-nav tabs within the connected (Main) screen. */
enum class MainTab { Chats, Heard, Packets, Stats, Map, Tools, Settings }

/** Connection / scanning state (chat + instrumentation state live in [MeshSession]). */
data class UiState(
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val linkState: LinkState = LinkState.Disconnected,
    val error: String? = null,
    val screen: Screen = Screen.Connect,
    val tab: MainTab = MainTab.Chats,
    /** Chats sub-tab: 0 = Contacts, 1 = Channels. Hoisted so it survives entering a conversation. */
    val chatsTab: Int = 0,
    /** When set, the Map tab should centre on these coordinates (lat, lon in degrees). */
    val mapFocus: Pair<Double, Double>? = null,
)

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    init { MeshConnection.init(app) }

    // The link + session are process-scoped (survive Activity recreation, run in the service).
    private val scanner get() = MeshConnection.scanner
    private val link get() = MeshConnection.link
    val session get() = MeshConnection.session

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
                        // Keep discovery order stable while scanning: refresh an existing
                        // device's RSSI in place, append newly-seen ones at the bottom.
                        val idx = st.devices.indexOfFirst { it.device.address == found.device.address }
                        val merged = if (idx >= 0) {
                            st.devices.toMutableList().also { it[idx] = found }
                        } else {
                            st.devices + found
                        }
                        st.copy(devices = merged)
                    }
                }
            } catch (e: CancellationException) {
                // Expected when stopScan()/connect() cancels the scan — not an error.
                throw e
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
                startConnectionService() // keep the link alive in the background + notify
                session.start() // APP_START handshake -> self info -> contacts sync
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun disconnect() {
        stopConnectionService()
        viewModelScope.launch { runCatching { link.disconnect() } }
        session.reset()
        _ui.update { it.copy(screen = Screen.Connect) }
    }

    private fun startConnectionService() {
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(app, Intent(app, MeshConnectionService::class.java))
    }

    private fun stopConnectionService() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, MeshConnectionService::class.java))
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

    /** Re-send a failed message. */
    fun resendMessage(msg: ChatMessage) = session.resend(msg)

    // ---- navigation ----
    fun setTab(tab: MainTab) = _ui.update { it.copy(tab = tab) }

    /** Jump to the Map tab and centre it on a node's coordinates. */
    fun showOnMap(lat: Double, lon: Double) =
        _ui.update { it.copy(tab = MainTab.Map, mapFocus = lat to lon) }

    /** The Map consumed its pending focus target. */
    fun consumeMapFocus() = _ui.update { it.copy(mapFocus = null) }

    fun setChatsTab(index: Int) = _ui.update { it.copy(chatsTab = index) }

    fun openConversation(id: String, title: String) {
        session.setActiveConversation(id)
        // Remember which sub-tab this conversation belongs to, so leaving it returns there.
        val chatsTab = if (id.startsWith("ch:")) 1 else 0
        _ui.update { it.copy(screen = Screen.Conversation(id, title), chatsTab = chatsTab) }
    }

    fun backToHome() {
        session.setActiveConversation(null)
        _ui.update { it.copy(screen = Screen.Main) }
    }
}
