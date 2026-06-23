package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import org.thepacket.meshcore.protocol.SelfInfo

@Composable
fun ToolsContent(session: MeshSession, self: SelfInfo?, modifier: Modifier = Modifier) {
    val contacts by session.contacts.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize()) {
        when (open) {
            "trace" -> TraceTool(session, contacts, self) { open = null }
            "discover" -> DiscoverTool(session, contacts) { open = null }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolRow(Icons.Default.Route, "Trace path",
                    "Trace a path through chosen repeaters; see each hop's SNR.") { open = "trace" }
                ToolRow(Icons.Default.Podcasts, "Discover nearby nodes",
                    "Announce to direct (one-hop) neighbours and list them.") { open = "discover" }
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

@Composable
private fun TraceTool(session: MeshSession, contacts: List<Contact>, self: SelfInfo?, onBack: () -> Unit) {
    val result by session.traceResult.collectAsStateWithLifecycle()
    val repeaters = remember(contacts) { contacts.filter { it.isRepeater } }
    val selected = remember { mutableStateListOf<Contact>() } // ordered path

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolHeader("Trace path", onBack)
        Text("Select repeaters in path order, then trace.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

        repeaters.forEach { c ->
            val idx = selected.indexOf(c)
            Card(Modifier.fillMaxWidth().clickable {
                if (idx >= 0) selected.remove(c) else selected.add(c)
            }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Checkbox(checked = idx >= 0, onCheckedChange = { if (idx >= 0) selected.remove(c) else selected.add(c) })
                    Text(c.name.ifBlank { c.keyPrefixHex }, Modifier.weight(1f))
                    if (idx >= 0) Text("#${idx + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (repeaters.isEmpty()) Text("No repeater contacts to trace through.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

        Button(
            onClick = { session.sendTrace(selected.map { it.publicKey[0] }.toByteArray()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Trace ${selected.size} hop(s)") }

        result?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Result", style = MaterialTheme.typography.titleMedium)
                    r.hops.forEachIndexed { i, h ->
                        val name = contacts.firstOrNull { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == h.hashByte }
                            ?.name?.ifBlank { null } ?: "0x%02X".format(h.hashByte)
                        kvRowMono("${i + 1}. $name", "SNR ${h.snrDb} dB")
                    }
                    kvRowMono("→ this node", "SNR ${r.finalSnrDb} dB")
                }
            }
        }
    }
}

@Composable
private fun DiscoverTool(session: MeshSession, contacts: List<Contact>, onBack: () -> Unit) {
    // A node is a one-hop neighbour when its known return path is direct (out_path_len == 0).
    val neighbours = remember(contacts) { contacts.filter { it.outPathLen == 0 } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolHeader("Discover nearby nodes", onBack)
        Text("“Nearby” = nodes reachable directly, with no repeater in between (one hop).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        OutlinedButton(onClick = { session.announceZeroHop() }, modifier = Modifier.fillMaxWidth()) {
            Text("Announce to neighbours (zero-hop advert)")
        }
        if (neighbours.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No direct neighbours yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            neighbours.forEach { c ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.name.ifBlank { c.keyPrefixHex }, fontWeight = FontWeight.SemiBold)
                        Text(if (c.isRepeater) "Repeater" else "Direct",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun kvRowMono(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
    }
}
