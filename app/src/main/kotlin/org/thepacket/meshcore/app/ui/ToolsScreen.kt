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
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    val selected = remember { mutableStateListOf<Contact>() } // ordered path (duplicates allowed)
    val r = result

    Box(Modifier.fillMaxSize()) {
        MapContent(
            self = self, contacts = contacts, heard = heard,
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
    val heard by session.heard.collectAsStateWithLifecycle()
    val contactTelemetry by session.contactTelemetry.collectAsStateWithLifecycle()
    val pathDiscovery by session.pathDiscovery.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<DiscoveredNode?>(null) }
    var autoRefresh by remember { mutableStateOf(false) }

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
                            Text(c?.name?.ifBlank { null } ?: n.keyPrefixHex, fontWeight = FontWeight.SemiBold)
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
            name = c?.name?.ifBlank { null } ?: n.keyPrefixHex,
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
