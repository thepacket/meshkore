package org.thepacket.meshcore.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.thepacket.meshcore.app.ui.ConnectScreen
import org.thepacket.meshcore.app.ui.ConversationScreen
import org.thepacket.meshcore.app.ui.MainScaffold
import org.thepacket.meshcore.app.ui.MeshCoreTheme

class MainActivity : ComponentActivity() {

    /** Permissions differ by API level: 31+ uses BLUETOOTH_SCAN/CONNECT; 26–30 needs location. */
    private val blePermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: ConnectionViewModel = viewModel()
            val state by vm.ui.collectAsStateWithLifecycle()
            val self by vm.session.self.collectAsStateWithLifecycle()
            val channels by vm.session.channels.collectAsStateWithLifecycle()
            val contacts by vm.session.contacts.collectAsStateWithLifecycle()
            val messages by vm.session.messages.collectAsStateWithLifecycle()
            val packets by vm.session.packets.collectAsStateWithLifecycle()
            val radio by vm.session.radioStats.collectAsStateWithLifecycle()
            val core by vm.session.coreStats.collectAsStateWithLifecycle()
            val packetStats by vm.session.packetStats.collectAsStateWithLifecycle()
            val noise by vm.session.noiseHistory.collectAsStateWithLifecycle()

            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants -> if (grants.values.all { it }) vm.startScan() }

            MeshCoreTheme {
                when (val screen = state.screen) {
                    is Screen.Connect -> ConnectScreen(
                        state = state,
                        onScanToggle = {
                            if (state.scanning) vm.stopScan() else permLauncher.launch(blePermissions)
                        },
                        onConnect = vm::connect,
                    )
                    is Screen.Main -> MainScaffold(
                        tab = state.tab,
                        onTab = vm::setTab,
                        onDisconnect = vm::disconnect,
                        self = self,
                        channels = channels,
                        contacts = contacts,
                        packets = packets,
                        radio = radio,
                        core = core,
                        packetStats = packetStats,
                        noiseHistory = noise,
                        onOpenConversation = vm::openConversation,
                    )
                    is Screen.Conversation -> ConversationScreen(
                        title = screen.title,
                        messages = messages[screen.conversationId].orEmpty(),
                        onBack = vm::backToHome,
                        onSend = { text -> vm.sendMessage(screen.conversationId, text) },
                    )
                }
            }
        }
    }
}
