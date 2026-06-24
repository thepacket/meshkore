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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.SelfInfo

@Composable
fun ToolsContent(session: MeshSession, self: SelfInfo?, modifier: Modifier = Modifier) {
    val contacts by session.contacts.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize()) {
        when (open) {
            "trace" -> TraceTool(session, contacts, self) { open = null }
            "companions" -> DiscoverTool(session, self, ContactType.CHAT,
                "Discover companions", "No nearby companions yet.") { open = null }
            "repeaters" -> DiscoverTool(session, self, ContactType.REPEATER,
                "Discover repeaters", "No nearby repeaters yet.") { open = null }
            "rooms" -> DiscoverTool(session, self, ContactType.ROOM,
                "Discover room servers", "No nearby room servers yet.") { open = null }
            "sensors" -> DiscoverTool(session, self, ContactType.SENSOR,
                "Discover sensors", "No nearby sensors yet.") { open = null }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolRow(Icons.Default.Route, "Trace path",
                    "Trace a path through chosen repeaters; see each hop's SNR.") { open = "trace" }
                ToolRow(Icons.Default.Person, "Discover companions",
                    "Announce and list nearby (one-hop) companion nodes.") { open = "companions" }
                ToolRow(Icons.Default.Router, "Discover repeaters",
                    "Announce and list nearby (one-hop) repeaters.") { open = "repeaters" }
                ToolRow(Icons.Default.MeetingRoom, "Discover room servers",
                    "Announce and list nearby (one-hop) room servers.") { open = "rooms" }
                ToolRow(Icons.Default.Sensors, "Discover sensors",
                    "Announce and list nearby (one-hop) sensors.") { open = "sensors" }
            }
        }
    }
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

@Composable
private fun DiscoverTool(
    session: MeshSession,
    self: SelfInfo?,
    typeFilter: Int,
    title: String,
    emptyMsg: String,
    onBack: () -> Unit,
) {
    // One-hop neighbours, tracked in the session (cleared on announce, fills from direct adverts).
    val allNeighbours by session.neighbours.collectAsStateWithLifecycle()
    val neighbours = allNeighbours.filter { it.type == typeFilter }
    val heard by session.heard.collectAsStateWithLifecycle()
    val contactTelemetry by session.contactTelemetry.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Contact?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolHeader(title, onBack)
        val hint = if (typeFilter == ContactType.CHAT) {
            // Companions don't beacon periodically (unlike repeaters/sensors/room servers),
            // so they only appear if they advertise while you're listening.
            "Reachable directly, with no repeater in between (one hop). Companions don't " +
                "advertise automatically — each one must Announce on its own device to appear here."
        } else {
            "Reachable directly, with no repeater in between (one hop)."
        }
        Text(hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        OutlinedButton(onClick = { session.announceZeroHop() }, modifier = Modifier.fillMaxWidth()) {
            Text("Announce (zero-hop advert)")
        }
        if (neighbours.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(emptyMsg, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            neighbours.forEach { c ->
                Card(Modifier.fillMaxWidth().clickable { selected = c }) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.name.ifBlank { c.keyPrefixHex }, fontWeight = FontWeight.SemiBold)
                        Text(if (c.isRepeater) "Repeater" else "Direct",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }

    selected?.let { c ->
        NodeDetailSheet(
            name = c.name.ifBlank { c.keyPrefixHex },
            type = c.type,
            isSelf = false,
            contact = c,
            heard = heard.firstOrNull { it.pubKeyHex.startsWith(c.keyPrefixHex) },
            self = self,
            onDismiss = { selected = null },
            onRequestTelemetry = { session.requestTelemetry(c) },
            telemetry = contactTelemetry[c.keyPrefixHex],
        )
    }
}

@Composable
private fun kvRowMono(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
    }
}
