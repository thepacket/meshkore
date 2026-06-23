package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BarChart
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
import androidx.compose.ui.Modifier
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
    packets: List<RxLog>,
    heard: List<org.thepacket.meshcore.app.HeardEntry>,
    radio: RadioStats?,
    core: CoreStats?,
    packetStats: PacketStats?,
    noiseHistory: List<Int>,
    telemetry: List<org.thepacket.meshcore.protocol.Lpp.Reading>,
    onOpenConversation: (id: String, title: String) -> Unit,
) {
    val title = when (tab) {
        MainTab.Chats -> self?.name?.ifBlank { "MeshCore" } ?: "MeshCore"
        MainTab.Heard -> "Heard"
        MainTab.Packets -> "Packet monitor"
        MainTab.Stats -> "Statistics"
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
                    selected = tab == MainTab.Chats,
                    onClick = { onTab(MainTab.Chats) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    label = { Text("Chats") },
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
                    selected = tab == MainTab.Stats,
                    onClick = { onTab(MainTab.Stats) },
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text("Stats") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Map,
                    onClick = { onTab(MainTab.Map) },
                    icon = { Icon(Icons.Default.Map, contentDescription = null) },
                    label = { Text("Map") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Tools,
                    onClick = { onTab(MainTab.Tools) },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Tools") },
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
            MainTab.Chats -> HomeContent(self, channels, contacts, onOpenConversation, m)
            MainTab.Heard -> HeardContent(heard, contacts, self, m)
            MainTab.Packets -> PacketMonitorContent(packets, contacts, self, m)
            MainTab.Stats -> StatsContent(radio, core, packetStats, noiseHistory, telemetry, session::refreshTelemetry, m)
            MainTab.Map -> MapContent(self, contacts, heard, m)
            MainTab.Tools -> ToolsContent(session, self, m)
            MainTab.Settings -> SettingsContent(session, self, m)
        }
    }
}
