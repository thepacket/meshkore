package org.thepacket.meshcore.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
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

    /** Conversation (id, title) to open from a tapped message notification; consumed once. */
    private val pendingConversation = mutableStateOf<Pair<String, String>?>(null)

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNotificationIntent(intent)
    }

    private fun readNotificationIntent(intent: Intent?) {
        val id = intent?.getStringExtra(MeshConnectionService.EXTRA_CONVERSATION_ID) ?: return
        val title = intent.getStringExtra(MeshConnectionService.EXTRA_CONVERSATION_TITLE) ?: id
        pendingConversation.value = id to title
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readNotificationIntent(intent)

        // Ask for notification permission (Android 13+) so background messages can be shown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val vm: ConnectionViewModel = viewModel()

            // Navigate to a conversation when launched from a message notification.
            val pending by pendingConversation
            LaunchedEffect(pending) {
                pending?.let { (id, title) ->
                    vm.openConversation(id, title)
                    pendingConversation.value = null
                }
            }

            val state by vm.ui.collectAsStateWithLifecycle()
            val self by vm.session.self.collectAsStateWithLifecycle()
            val channels by vm.session.channels.collectAsStateWithLifecycle()
            val contacts by vm.session.contacts.collectAsStateWithLifecycle()
            val allContacts by vm.session.allContacts.collectAsStateWithLifecycle()
            val messages by vm.session.messages.collectAsStateWithLifecycle()
            val packets by vm.session.packets.collectAsStateWithLifecycle()
            val heard by vm.session.heard.collectAsStateWithLifecycle()
            val radio by vm.session.radioStats.collectAsStateWithLifecycle()
            val core by vm.session.coreStats.collectAsStateWithLifecycle()
            val packetStats by vm.session.packetStats.collectAsStateWithLifecycle()
            val noise by vm.session.noiseHistory.collectAsStateWithLifecycle()
            val telemetry by vm.session.telemetry.collectAsStateWithLifecycle()
            val repeaters by vm.session.repeaters.collectAsStateWithLifecycle()

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
                        onObserve = vm::enterObserver,
                    )
                    is Screen.Main -> MainScaffold(
                        tab = state.tab,
                        onTab = vm::setTab,
                        onDisconnect = vm::disconnect,
                        session = vm.session,
                        self = self,
                        channels = channels,
                        contacts = contacts,
                        allContacts = allContacts,
                        packets = packets,
                        heard = heard,
                        radio = radio,
                        core = core,
                        packetStats = packetStats,
                        noiseHistory = noise,
                        telemetry = telemetry,
                        onOpenConversation = vm::openConversation,
                        mapFocus = state.mapFocus,
                        onShowOnMap = vm::showOnMap,
                        onMapFocusConsumed = vm::consumeMapFocus,
                        chatsTab = state.chatsTab,
                        onChatsTab = vm::setChatsTab,
                    )
                    is Screen.Conversation -> {
                        // A room-server conversation gets a login banner + per-post authors.
                        val roomContact = contacts.firstOrNull {
                            it.type == org.thepacket.meshcore.protocol.ContactType.ROOM &&
                                Conversation.dmId(it) == screen.conversationId
                        }
                        // Silently log back in to the room with the remembered password, so
                        // posts sync without re-typing it (saved on the first successful login).
                        LaunchedEffect(screen.conversationId, roomContact?.keyPrefixHex) {
                            roomContact?.let { vm.session.autoLoginIfSaved(it) }
                        }
                        ConversationScreen(
                            title = screen.title,
                            messages = messages[screen.conversationId].orEmpty(),
                            contacts = contacts,
                            heard = heard,
                            self = self,
                            roomLogin = roomContact?.let {
                                repeaters[screen.conversationId]?.login ?: RepeaterLogin.None
                            },
                            isChannel = screen.conversationId.startsWith("ch:"),
                            onBack = vm::backToHome,
                            onSend = { text -> vm.sendMessage(screen.conversationId, text) },
                            onLogin = { pw -> roomContact?.let { vm.session.loginRepeater(it, pw) } },
                            onResend = vm::resendMessage,
                        )
                    }
                }
            }
        }
    }
}
