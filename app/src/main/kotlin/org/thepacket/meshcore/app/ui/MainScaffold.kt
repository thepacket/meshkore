package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.ChannelEntry
import org.thepacket.meshcore.app.MainTab
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.CoreStats
import org.thepacket.meshcore.protocol.PacketStats
import org.thepacket.meshcore.protocol.RadioStats
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.SelfInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    onDisconnect: () -> Unit,
    session: MeshSession,
    self: SelfInfo?,
    channels: List<ChannelEntry>,
    contacts: List<Contact>,
    allContacts: List<Contact>,
    heard: List<org.thepacket.meshcore.app.HeardEntry>,
    radio: RadioStats?,
    core: CoreStats?,
    packetStats: PacketStats?,
    noiseHistory: List<Int>,
    telemetry: List<org.thepacket.meshcore.protocol.Lpp.Reading>,
    onOpenConversation: (id: String, title: String) -> Unit,
    mapFocus: Pair<Double, Double>? = null,
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    onMapFocusConsumed: () -> Unit = {},
    contactsTab: Int = 0,
    onContactsTab: (Int) -> Unit = {},
    packetFilter: PacketFilter = PacketFilter(),
    onPacketFilter: (PacketFilter) -> Unit = {},
    packetMonitorPaused: Boolean = false,
    onPacketMonitorPaused: (Boolean) -> Unit = {},
) {
    // Scope the map to the globally-selected region (Settings), Home resolving region-less nodes.
    val mapRegion by session.region.collectAsStateWithLifecycle()
    val mapHome by session.homeRegion.collectAsStateWithLifecycle()
    val title = when (tab) {
        MainTab.Channels -> "Chat"
        MainTab.Contacts -> "Contact"
        MainTab.Heard -> "Heard"
        MainTab.Packets -> "Packet monitor"
        MainTab.Map -> "Map"
        MainTab.Tools -> "Tools"
        MainTab.Settings -> "Settings"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = { TextButton(onClick = onDisconnect) { Text("Disconnect") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.Channels,
                    onClick = { onTab(MainTab.Channels) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    label = { Text("Chat") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Contacts,
                    onClick = { onTab(MainTab.Contacts) },
                    icon = { Icon(Icons.Default.Contacts, contentDescription = null) },
                    label = { Text("Contact") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Heard,
                    onClick = { onTab(MainTab.Heard) },
                    icon = { Icon(Icons.Default.Hearing, contentDescription = null) },
                    label = { Text("Heard") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Packets,
                    onClick = { onTab(MainTab.Packets) },
                    icon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                    label = { Text("Packets") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Tools,
                    onClick = { onTab(MainTab.Tools) },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Tools") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Map,
                    onClick = { onTab(MainTab.Map) },
                    icon = { Icon(Icons.Default.Map, contentDescription = null) },
                    label = { Text("Map") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Settings,
                    onClick = { onTab(MainTab.Settings) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            MainTab.Channels -> ChannelsContent(session, channels, onOpenConversation, m)
            MainTab.Contacts -> ContactsContent(session, self, contacts, onOpenConversation, m, onShowOnMap, contactsTab, onContactsTab)
            MainTab.Heard -> HeardContent(heard, contacts, self, session, m, onShowOnMap)
            MainTab.Packets -> PacketMonitorContent(
                // Use the aggregate (global) address book so names resolve for nodes not on this device.
                allContacts, self, session, m, onShowOnMap,
                filter = packetFilter, onFilterChange = onPacketFilter,
                paused = packetMonitorPaused, onPausedChange = onPacketMonitorPaused,
            )
            MainTab.Map -> MapContent(
                self, allContacts, heard, m,
                selectedRegion = mapRegion, homeRegion = mapHome,
                focus = mapFocus, onFocusConsumed = onMapFocusConsumed,
            )
            MainTab.Tools -> ToolsContent(session, self, m, onShowOnMap)
            MainTab.Settings -> SettingsContent(session, self, m)
        }
    }
}
