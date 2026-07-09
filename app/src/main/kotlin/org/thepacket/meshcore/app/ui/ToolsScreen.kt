package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.DiscoveredNode
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.hexToBytes
import org.thepacket.meshcore.protocol.toHex

@Composable
fun ToolsContent(
    session: MeshSession,
    self: SelfInfo?,
    modifier: Modifier = Modifier,
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
) {
    val contacts by session.contacts.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    // "Advert — To Clipboard" exports this node's own card; the hex arrives asynchronously.
    // Android shows its own clipboard confirmation, so no extra message is needed here.
    LaunchedEffect(session) {
        session.exportedContact.collect { card -> copyToClipboard(ctx, "MeshCore advert", card) }
    }

    Box(modifier.fillMaxSize()) {
        when (open) {
            "trace" -> TraceTool(session, contacts, self) { open = null }
            "discover" -> DiscoverTool(session, self, onShowOnMap) { open = null }
            "rawdata" -> RawDataTool(session, contacts, ::notify) { open = null }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolRow(Icons.Default.Route, "Trace path",
                    "Trace a path through chosen repeaters; see each hop's SNR.") { open = "trace" }
                ToolRow(Icons.Default.Sensors, "Discover nodes",
                    "Find nearby (one-hop) nodes — repeaters, room servers, sensors and companions.") { open = "discover" }
                ToolRow(Icons.Default.Campaign, "Advert — Zero Hop",
                    "Announce this node to direct (one-hop) neighbours.") {
                    session.sendSelfAdvert(flood = false); notify("Zero-hop advert sent")
                }
                ToolRow(Icons.Default.Campaign, "Advert — Flood Routed",
                    "Announce this node across the whole mesh (flood-routed).") {
                    session.sendSelfAdvert(flood = true); notify("Flood advert sent")
                }
                ToolRow(Icons.Default.ContentCopy, "Advert — To Clipboard",
                    "Copy this node's advert card to the clipboard for sharing.") {
                    session.exportSelfAdvert()
                }
                ToolRow(Icons.Default.DataObject, "Raw data",
                    "Send a raw custom-payload packet to a contact, and view received raw data.") {
                    open = "rawdata"
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawDataTool(
    session: MeshSession,
    contacts: List<Contact>,
    onNotify: (String) -> Unit,
    onBack: () -> Unit,
) {
    val received by session.rawData.collectAsStateWithLifecycle()
    // Recipient list sorted alphabetically by display name (case-insensitive).
    val sortedContacts = remember(contacts) {
        contacts.sortedBy { (it.name.ifBlank { it.keyPrefixHex }).lowercase() }
    }
    var selected by remember(sortedContacts) { mutableStateOf(sortedContacts.firstOrNull()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var asHex by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    // Encode the payload from the chosen mode. Null = malformed hex.
    val payload: ByteArray? = remember(input, asHex) {
        if (asHex) {
            val c = input.trim().replace(" ", "").lowercase()
            if (c.isNotEmpty() && c.length % 2 == 0 && c.all { it in "0123456789abcdef" }) c.hexToBytes() else null
        } else input.toByteArray(Charsets.UTF_8)
    }
    val tooShort = payload != null && payload.size < 4
    val canSend = selected != null && payload != null && payload.size >= 4

    // Surface the OK/ERR that CMD_SEND_RAW_DATA elicits (routed via settingsResult, label "Raw data").
    LaunchedEffect(session) {
        session.settingsResult.collect { r ->
            if (r.label == "Raw data") onNotify(if (r.ok) "Raw data sent" else "Send failed (flood/queue?)")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ToolHeader("Raw data", onBack)

        Text(
            "Send a PAYLOAD_TYPE_RAW_CUSTOM packet (your own bytes/format) to a contact. Routing is " +
                "direct only — a known path if we have one, otherwise zero-hop to a direct neighbour. " +
                "Minimum 4 bytes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Recipient picker.
            ExposedDropdownMenuBox(expanded = pickerOpen, onExpandedChange = { pickerOpen = it }) {
                OutlinedTextField(
                    value = selected?.let { it.name.ifBlank { it.keyPrefixHex } } ?: "No contacts",
                    onValueChange = {}, readOnly = true, label = { Text("Recipient") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(pickerOpen) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    sortedContacts.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.name.ifBlank { c.keyPrefixHex }) },
                            onClick = { selected = c; pickerOpen = false },
                        )
                    }
                }
            }
            selected?.let { c ->
                val routing = if (c.outPathLen in 0..63)
                    "direct · ${c.outPathLen}-hop path" else "zero-hop (direct neighbour only)"
                Text("Routing: $routing", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }

            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hex payload")
                Switch(checked = asHex, onCheckedChange = { asHex = it })
            }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text(if (asHex) "Payload (hex)" else "Payload (text)") },
                singleLine = false, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                // Only flag an error once the user has typed something invalid/too short.
                isError = input.isNotBlank() && (payload == null || tooShort),
            )
            // Neutral helper while empty/valid; red only for a real problem after typing.
            val (helper, isErr) = when {
                input.isBlank() ->
                    (if (asHex) "Enter hex bytes — at least 4 bytes (8 hex chars)."
                    else "Enter text — at least 4 bytes.") to false
                payload == null -> "Invalid hex (need an even number of 0-9 a-f)." to true
                tooShort -> "${payload.size}/4 bytes — need at least 4." to true
                else -> "${payload.size} bytes ✓" to false
            }
            Text(helper, style = MaterialTheme.typography.labelSmall,
                color = if (isErr) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Button(
                onClick = {
                    val c = selected; val p = payload
                    if (c != null && p != null) { session.sendRawData(c, p); input = "" }
                },
                enabled = canSend, modifier = Modifier.fillMaxWidth(),
            ) { Text("Send raw data") }
        }

        // Received raw frames.
        Text("Received (${received.size})", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (received.isEmpty()) {
            Text("No raw data received yet.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 12.dp))
        } else {
            received.forEach { RawDataRow(it) }
        }
    }
}

@Composable
private fun RawDataRow(frame: org.thepacket.meshcore.protocol.RawDataFrame) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SNR ${frame.snrDb} dB · RSSI ${frame.rssi}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
                Text("${frame.payload.size} B", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Text(frame.hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            val ascii = frame.payload.map { b ->
                val v = b.toInt() and 0xFF
                if (v in 32..126) v.toChar() else '·'
            }.joinToString("")
            Text(ascii, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
        }
    }
}

private fun copyToClipboard(ctx: android.content.Context, label: String, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

@Composable
private fun ToolRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ToolHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceTool(session: MeshSession, contacts: List<Contact>, self: SelfInfo?, onBack: () -> Unit) {
    val result by session.traceResult.collectAsStateWithLifecycle()
    val heard by session.heard.collectAsStateWithLifecycle()
    val allContacts by session.allContacts.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<Contact>() } // ordered path (duplicates allowed)
    val r = result

    Box(Modifier.fillMaxSize()) {
        MapContent(
            self = self, contacts = allContacts, heard = heard,
            modifier = Modifier.fillMaxSize(),
            traceMode = true,
            tracePath = selected,
            onAddTrace = { c -> selected.add(c) }, // tap appends; the same node may repeat
        )

        // Top bar: back + title (overlaid, the map fills behind it).
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Trace path", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) TextButton(onClick = { selected.clear() }) { Text("Clear") }
            }
        }

        // Bottom panel: instructions + Trace button, or the result legend + New trace.
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (r == null) {
                    Text(
                        "Tap a node to add it to the path (taps add; a node may repeat). Long-press a node for its details.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    if (selected.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            selected.forEachIndexed { i, c ->
                                AssistChip(
                                    onClick = { selected.removeAt(i) },
                                    label = { Text("${i + 1}. ${c.name.ifBlank { c.keyPrefixHex }}") },
                                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { session.sendTrace(selected.map { it.publicKey[0] }.toByteArray()) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Trace ${selected.size} hop(s)") }
                } else {
                    Text("Result", style = MaterialTheme.typography.titleMedium)
                    r.hops.forEachIndexed { i, h ->
                        val name = contacts.firstOrNull { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == h.hashByte }
                            ?.name?.ifBlank { null } ?: "0x%02X".format(h.hashByte)
                        kvRowMono("${i + 1}. $name", "SNR ${h.snrDb} dB")
                    }
                    kvRowMono("→ this node", "SNR ${r.finalSnrDb} dB")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Edit keeps the current path for tweaking; New clears it to start fresh.
                        OutlinedButton(onClick = { session.clearTrace() }, modifier = Modifier.weight(1f)) { Text("Edit Path") }
                        Button(onClick = { session.clearTrace(); selected.clear() }, modifier = Modifier.weight(1f)) { Text("New Path") }
                    }
                }
            }
        }
    }
}

// Bitmask of every node type to ask for in a discovery request (1 shl ContactType.*).
private val ALL_NODE_TYPES =
    (1 shl ContactType.CHAT) or (1 shl ContactType.REPEATER) or
        (1 shl ContactType.ROOM) or (1 shl ContactType.SENSOR)

@Composable
private fun DiscoverTool(
    session: MeshSession,
    self: SelfInfo?,
    onShowOnMap: (lat: Double, lon: Double) -> Unit,
    onBack: () -> Unit,
) {
    // Blind, zero-hop node-discovery: direct neighbours answer our request. The list is only
    // (re)populated when the user taps Discover — never cleared automatically.
    val discovered by session.discovered.collectAsStateWithLifecycle()
    val contacts by session.contacts.collectAsStateWithLifecycle()
    val allContacts by session.allContacts.collectAsStateWithLifecycle()
    val heard by session.heard.collectAsStateWithLifecycle()
    val contactTelemetry by session.contactTelemetry.collectAsStateWithLifecycle()
    val pathDiscovery by session.pathDiscovery.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<DiscoveredNode?>(null) }
    var autoRefresh by remember { mutableStateOf(false) }

    // Discovery replies carry no name — only the key/type/signal. Resolve a friendly name from
    // the aggregate address book (contacts seen on any device), then from a heard advert; fall
    // back to the hex key prefix. Heard entries whose "name" is just the hex prefix don't count.
    fun displayName(n: DiscoveredNode): String {
        allContacts.firstOrNull { it.keyPrefixHex == n.keyPrefixHex }?.name?.ifBlank { null }?.let { return it }
        heard.firstOrNull { it.pubKeyHex.startsWith(n.keyPrefixHex) }
            ?.takeIf { it.name.isNotBlank() && it.name != it.pubKeyHex.take(12) }?.let { return it.name }
        return n.keyPrefixHex
    }

    // Periodic discovery runs only while this screen is open (the effect is cancelled on leave).
    // 60s stays within the firmware's responder rate-limit (4 per 2 min) and is airtime-frugal.
    LaunchedEffect(autoRefresh) {
        if (!autoRefresh) return@LaunchedEffect
        while (true) {
            session.discoverNodes(ALL_NODE_TYPES)
            delay(60_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolHeader("Discover nodes", onBack)
        Text("Sends a zero-hop discovery request; direct neighbours answer. Replies can take a few seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { session.discoverNodes(ALL_NODE_TYPES) }, modifier = Modifier.weight(1f)) {
                Text("Discover")
            }
            OutlinedButton(
                onClick = { session.clearDiscovered() },
                modifier = Modifier.weight(1f),
                enabled = discovered.isNotEmpty(),
            ) { Text("Clear") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = autoRefresh, onCheckedChange = { autoRefresh = it })
            Text("Auto-refresh every 60s", style = MaterialTheme.typography.bodyMedium)
        }
        if (discovered.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No nodes discovered yet. Tap Discover.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            discovered.sortedByDescending { it.snrQ }.forEach { n ->
                val c = contacts.firstOrNull { it.keyPrefixHex == n.keyPrefixHex }
                Card(Modifier.fillMaxWidth().clickable { selected = n }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(displayName(n), fontWeight = FontWeight.SemiBold)
                            Text(nodeTypeName(n.type), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Text("SNR %.1f dB".format(n.snrDb),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }

    selected?.let { n ->
        val c = contacts.firstOrNull { it.keyPrefixHex == n.keyPrefixHex }
        NodeDetailSheet(
            name = displayName(n),
            type = n.type,
            isSelf = false,
            contact = c,
            heard = heard.firstOrNull { it.pubKeyHex.startsWith(n.keyPrefixHex) },
            self = self,
            onDismiss = { selected = null },
            onRequestTelemetry = c?.let { { session.requestTelemetry(it) } },
            telemetry = c?.let { contactTelemetry[it.keyPrefixHex] },
            onShowOnMap = onShowOnMap,
            onDiscoverPath = { session.discoverPath(n.pubKey) },
            pathResult = pathDiscovery[n.keyPrefixHex],
        )
    }
}

private fun nodeTypeName(type: Int) = when (type) {
    ContactType.CHAT -> "Companion"
    ContactType.REPEATER -> "Repeater"
    ContactType.ROOM -> "Room server"
    ContactType.SENSOR -> "Sensor"
    else -> "Node"
}

@Composable
private fun kvRowMono(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
    }
}
