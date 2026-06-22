package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.thepacket.meshcore.app.HeardEntry
import org.thepacket.meshcore.app.haversineKm
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.toHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared bottom sheet showing everything known about a node. Used by both the Map
 * (tap a marker) and Heard (tap a row). All fields are selectable for copy-paste.
 *
 * @param self  our own node's SelfInfo — used for distance, and (when [isSelf]) for the radio block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailSheet(
    name: String,
    type: Int,
    isSelf: Boolean,
    contact: Contact?,
    heard: HeardEntry?,
    self: SelfInfo?,
    onDismiss: () -> Unit,
) {
    val coords: Pair<Double, Double>? = when {
        isSelf && self != null && (self.advLat != 0 || self.advLon != 0) -> self.advLat / 1e6 to self.advLon / 1e6
        contact != null && (contact.gpsLat != 0 || contact.gpsLon != 0) -> contact.gpsLat / 1e6 to contact.gpsLon / 1e6
        heard != null && heard.hasGps -> heard.latDeg to heard.lonDeg
        else -> null
    }
    val selfHasGps = self != null && (self.advLat != 0 || self.advLon != 0)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    name + if (isSelf) "  (this node)" else "",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                )
                Text(nodeTypeLabel(type), color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))

                coords?.let { kvRow("Coordinates", "%.5f, %.5f".format(it.first, it.second)) }
                if (!isSelf && selfHasGps && coords != null) {
                    val km = haversineKm(self!!.advLat / 1e6, self.advLon / 1e6, coords.first, coords.second)
                    kvRow("Distance", if (km < 1) "${(km * 1000).toInt()} m" else "%.1f km".format(km))
                }
                heard?.snrDb?.let { kvRow("SNR", "$it dB") }
                heard?.rssi?.let { kvRow("RSSI", "$it dBm") }
                heard?.let { kvRow("Last heard", relAge(it.lastHeardMs)) }
                contact?.let { if (it.lastAdvert > 0) kvRow("Last advert", epochStr(it.lastAdvert)) }
                contact?.let {
                    kvRow("Path", if (it.outPathLen in 0..63) "${it.outPathLen} hop(s)" else "flood / unknown")
                }

                if (isSelf) self?.let {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    kvRow("Frequency", "${it.freqMhz} MHz")
                    kvRow("Bandwidth", "${it.bwKhz} kHz")
                    kvRow("Spreading factor", "SF${it.radioSf}")
                    kvRow("Coding rate", "4/${it.radioCr}")
                    kvRow("TX power", "${it.txPower} dBm")
                }

                val key = contact?.publicKey?.toHex()
                    ?: (if (isSelf) self?.publicKey?.toHex() else null)
                    ?: heard?.pubKeyHex
                key?.let {
                    Text("Public key", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun kvRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun nodeTypeLabel(type: Int) = when (type) {
    ContactType.CHAT -> "Contact"
    ContactType.REPEATER -> "Repeater"
    ContactType.ROOM -> "Room"
    ContactType.SENSOR -> "Sensor"
    else -> "Node"
}

private fun relAge(ms: Long): String {
    val secs = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86400 -> "${secs / 3600}h ago"
        else -> "${secs / 86400}d ago"
    }
}

private fun epochStr(sec: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))
