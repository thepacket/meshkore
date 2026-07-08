package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.thepacket.meshcore.app.HeardEntry
import org.thepacket.meshcore.app.haversineKm
import org.thepacket.meshcore.protocol.AdvertPathInfo
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.Lpp
import org.thepacket.meshcore.protocol.PathDiscoveryResult
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
    onShare: (() -> Unit)? = null,
    onResetPath: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onRequestTelemetry: (() -> Unit)? = null,
    telemetry: List<Lpp.Reading>? = null,
    onManage: (() -> Unit)? = null,
    /** Shown (with the node's coordinates) only when the node has a known position. */
    onShowOnMap: ((lat: Double, lon: Double) -> Unit)? = null,
    /** "Get path to node": triggers route discovery; [pathResult] holds the latest reply. */
    onDiscoverPath: (() -> Unit)? = null,
    pathResult: PathDiscoveryResult? = null,
    /** Cached advert-path lookup (GET_ADVERT_PATH): requested on open, reply in [advertPath]. */
    onRequestAdvertPath: (() -> Unit)? = null,
    advertPath: AdvertPathInfo? = null,
    /** True once the advert-path query has resolved (found or "none stored"). */
    advertPathLoaded: Boolean = false,
) {
    val coords: Pair<Double, Double>? = when {
        isSelf && self != null && (self.advLat != 0 || self.advLon != 0) -> self.advLat / 1e6 to self.advLon / 1e6
        contact != null && (contact.gpsLat != 0 || contact.gpsLon != 0) -> contact.gpsLat / 1e6 to contact.gpsLon / 1e6
        heard != null && heard.hasGps -> heard.latDeg to heard.lonDeg
        else -> null
    }
    val selfHasGps = self != null && (self.advLat != 0 || self.advLon != 0)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Back", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
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

                if (onShowOnMap != null && coords != null) {
                    OutlinedButton(
                        onClick = { onShowOnMap(coords.first, coords.second); onDismiss() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text("Show on map") }
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

                if (onManage != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Button(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                        Text(if (type == ContactType.ROOM) "Manage room" else "Manage repeater")
                    }
                }

                if (onDiscoverPath != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    PathSection(pathResult, onDiscoverPath)
                }

                if (onRequestAdvertPath != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    AdvertPathSection(advertPath, advertPathLoaded, contact, onRequestAdvertPath)
                }

                if (onRequestTelemetry != null) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    TelemetrySection(telemetry, onRequestTelemetry)
                }

                if (onShare != null || onResetPath != null || onExport != null || onRemove != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ContactActions(onShare, onResetPath, onExport, onRemove)
                }
            }
        }
    }
}

/**
 * "Get path to node" control + result, shared by the node sheet and the packet dialog.
 * [hopName] resolves a 1-byte hop hash to a display name (defaults to hex).
 */
@Composable
internal fun PathSection(
    result: PathDiscoveryResult?,
    onDiscover: () -> Unit,
    hopName: (Int) -> String = { "0x%02X".format(it) },
) {
    var pending by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var reqId by remember { mutableStateOf(0) }
    // A new result clears pending; otherwise time out (the node never answered the flood request).
    LaunchedEffect(result) { if (result != null) { pending = false; timedOut = false } }
    LaunchedEffect(reqId) {
        if (reqId > 0) { kotlinx.coroutines.delay(45_000); if (pending) { pending = false; timedOut = true } }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Path to node", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            enabled = !pending,
            onClick = { pending = true; timedOut = false; reqId++; onDiscover() },
        ) {
            Text(if (pending) "Requesting…" else if (result == null) "Get path" else "Refresh")
        }
    }
    when {
        pending -> kvHint("Requesting route… waiting for the node to reply (this can take a while).")
        timedOut -> kvHint("No response — the node may be offline, out of range, or not answering.")
        result == null -> kvHint("Tap Get path to ask the device for the route to this node.")
        else -> {
            kvRow("Outbound", pathLabel(result.outPath, hopName))
            kvRow("Return", pathLabel(result.inPath, hopName))
        }
    }
}

private fun pathLabel(path: List<Int>, hopName: (Int) -> String): String =
    if (path.isEmpty()) "direct (0 hops)"
    else "${path.size} hop(s): " + path.joinToString(" → ", transform = hopName)

/**
 * The device's cached advert path to this contact (GET_ADVERT_PATH) — the route the contact's
 * last advert took to reach us, and when. Auto-requested when the sheet opens; instant local
 * lookup, so a short spinner then either the path or a "none stored" hint.
 */
@Composable
private fun AdvertPathSection(
    advertPath: AdvertPathInfo?,
    loaded: Boolean,
    contact: Contact?,
    onRequest: () -> Unit,
    hopName: (Int) -> String = { "0x%02X".format(it) },
) {
    var requested by remember(contact?.keyPrefixHex) { mutableStateOf(false) }
    // Request once when this sheet opens for this contact (unless we already have a fresh result).
    LaunchedEffect(contact?.keyPrefixHex) {
        if (!loaded && !requested) { requested = true; onRequest() }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advert path", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { requested = true; onRequest() }) { Text("Refresh") }
    }
    when {
        advertPath != null -> {
            val hops = advertPath.singleByteHops
            val label = when {
                advertPath.hopCount == 0 -> "direct (0 hops)"
                hops != null -> "${advertPath.hopCount} hop(s): " + hops.joinToString(" → ", transform = hopName)
                else -> "${advertPath.hopCount} hop(s): ${advertPath.hex}" // multi-byte hop hashes
            }
            kvRow("Route", label)
            if (advertPath.recvTimestamp > 0) kvRow("Advert received", epochStr(advertPath.recvTimestamp))
        }
        loaded -> kvHint("No advert path stored on the device for this contact.")
        else -> kvHint("Looking up the cached advert path…")
    }
}

@Composable
private fun TelemetrySection(telemetry: List<Lpp.Reading>?, onRequest: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Telemetry", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onRequest) { Text(if (telemetry == null) "Request" else "Refresh") }
    }
    when {
        telemetry == null -> kvHint("Tap Request to ask this node for telemetry.")
        telemetry.isEmpty() -> kvHint("No telemetry returned.")
        else -> {
            val byChannel = telemetry.groupBy { it.channel }.toSortedMap()
            byChannel.forEach { (ch, readings) ->
                if (byChannel.size > 1) {
                    Text("Channel $ch", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
                readings.forEach { kvRow(telemetryLabel(it), telemetryValue(it)) }
            }
        }
    }
}

@Composable
private fun kvHint(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        style = MaterialTheme.typography.bodySmall)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactActions(
    onShare: (() -> Unit)?,
    onResetPath: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        onShare?.let { OutlinedButton(onClick = it) { Text("Share") } }
        onResetPath?.let { OutlinedButton(onClick = it) { Text("Reset path") } }
        onExport?.let { OutlinedButton(onClick = it) { Text("Export") } }
        onRemove?.let {
            OutlinedButton(
                onClick = { confirmRemove = true },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove") }
        }
    }
    if (confirmRemove && onRemove != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove contact?") },
            text = { Text("This deletes the contact from the device. You can re-add it from a future advert.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
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
