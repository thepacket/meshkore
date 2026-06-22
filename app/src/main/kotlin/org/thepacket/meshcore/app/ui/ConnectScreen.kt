package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.thepacket.meshcore.app.UiState
import org.thepacket.meshcore.ble.LinkState
import org.thepacket.meshcore.ble.ScannedDevice
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.toHex

@Composable
fun ConnectScreen(
    state: UiState,
    onScanToggle: () -> Unit,
    onConnect: (ScannedDevice) -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("MeshCore") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.self != null) {
                ConnectedCard(state.self, onDisconnect)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onScanToggle, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Text(if (state.scanning) "  Stop scan" else "  Scan for devices")
                    }
                    if (state.linkState == LinkState.Connecting) CircularProgressIndicator(Modifier.padding(4.dp))
                }
                state.error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                DeviceList(state.devices, onConnect)
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<ScannedDevice>, onConnect: (ScannedDevice) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices, key = { it.device.address }) { d ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(d.name ?: "(unnamed)", style = MaterialTheme.typography.titleMedium)
                        Text("${d.device.address}   ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { onConnect(d) }) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun ConnectedCard(self: SelfInfo, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(self.name.ifBlank { "(unnamed node)" }, style = MaterialTheme.typography.headlineSmall)
            Field("Public key", self.publicKey.copyOf(8).toHex() + "…")
            Field("Frequency", "${self.radioFreqKhz / 1000.0} MHz")
            Field("Bandwidth", "${self.radioBwKhz} kHz")
            Field("Spreading factor", "SF${self.radioSf}")
            Field("Coding rate", "4/${self.radioCr}")
            Field("TX power", "${self.txPower} dBm (max ${self.maxTxPower})")
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.padding(top = 8.dp)) { Text("Disconnect") }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontFamily = FontFamily.Monospace)
    }
}
