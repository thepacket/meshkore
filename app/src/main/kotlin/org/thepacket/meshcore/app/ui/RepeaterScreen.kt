package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.app.RepeaterLogin
import org.thepacket.meshcore.app.RepeaterSession
import org.thepacket.meshcore.protocol.AclEntry
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.Neighbour
import org.thepacket.meshcore.protocol.RepeaterStats
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.toHex

/** Remote management for a repeater/room: log in, view status, and run admin/CLI commands. */
@Composable
fun RepeaterScreen(session: MeshSession, contact: Contact, onBack: () -> Unit) {
    val repeaters by session.repeaters.collectAsStateWithLifecycle()
    val contacts by session.contacts.collectAsStateWithLifecycle()
    val self by session.self.collectAsStateWithLifecycle()
    val s = repeaters[contact.keyPrefixHex] ?: RepeaterSession()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(contact.name.ifBlank { contact.keyPrefixHex },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }

        if (s.login != RepeaterLogin.LoggedIn) LoginForm(session, contact, s)
        else ManagementBody(session, contact, s, contacts, self)
    }
}

@Composable
private fun NeighboursCard(neighbours: List<Neighbour>, contacts: List<Contact>, self: SelfInfo?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Neighbours (${neighbours.size})", style = MaterialTheme.typography.titleMedium)
            if (neighbours.isEmpty()) {
                Text("None reported.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                neighbours.forEach { n ->
                    val name = resolveNodeName(n.keyPrefixHex, contacts, self)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Medium)
                            Text("heard ${fmtAge(n.secsAgo)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Text("SNR %.1f dB".format(n.snrDb),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AclCard(acl: List<AclEntry>, contacts: List<Contact>, self: SelfInfo?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Access list (${acl.size})", style = MaterialTheme.typography.titleMedium)
            if (acl.isEmpty()) {
                Text("No clients registered.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                acl.forEach { e ->
                    val name = resolveNodeName(e.keyPrefixHex, contacts, self)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, modifier = Modifier.weight(1f))
                        Text(e.roleName, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

/** Resolve a 6-byte key-prefix hex to a friendly name: our own node, a contact, else the prefix. */
private fun resolveNodeName(prefixHex: String, contacts: List<Contact>, self: SelfInfo?): String {
    if (self != null && self.publicKey.copyOf(6).toHex() == prefixHex) {
        return self.name.ifBlank { prefixHex } + " (this node)"
    }
    return contacts.firstOrNull { it.keyPrefixHex == prefixHex }?.name?.ifBlank { null } ?: prefixHex
}

private fun fmtAge(secs: Int): String = when {
    secs < 60 -> "${secs}s ago"
    secs < 3600 -> "${secs / 60}m ago"
    secs < 86400 -> "${secs / 3600}h ago"
    else -> "${secs / 86400}d ago"
}

@Composable
private fun LoginForm(session: MeshSession, contact: Contact, s: RepeaterSession) {
    var password by remember { mutableStateOf("") }
    Text("Log in to manage this node. Use its admin (or guest) password.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text("Password") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { session.loginRepeater(contact, password) },
        enabled = s.login != RepeaterLogin.LoggingIn,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (s.login == RepeaterLogin.LoggingIn) "Logging in…" else "Log in") }
    when (s.login) {
        RepeaterLogin.LoggingIn -> Text(
            "Waiting for the node to respond — can take a while over the mesh.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall)
        RepeaterLogin.Failed -> Text(
            "Login failed — wrong password, or no response from the node.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else -> {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagementBody(
    session: MeshSession, contact: Contact, s: RepeaterSession, contacts: List<Contact>, self: SelfInfo?,
) {
    var command by remember { mutableStateOf("") }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("Logged in" + if (s.isAdmin) " · admin" else "", color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { session.requestRepeaterStatus(contact) }) { Text("Refresh") }
            OutlinedButton(onClick = { session.logoutRepeater(contact) }) { Text("Log out") }
        }
    }

    s.stats?.let { StatusCard(it) }

    OutlinedButton(onClick = { session.requestRepeaterNeighbours(contact) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (s.neighbours == null) "Get neighbours" else "Refresh neighbours")
    }
    s.neighbours?.let { NeighboursCard(it, contacts, self) }

    if (s.isAdmin) {
        OutlinedButton(onClick = { session.requestRepeaterAcl(contact) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.acl == null) "Get access list" else "Refresh access list")
        }
        s.acl?.let { AclCard(it, contacts, self) }
    }

    // Quick read-only commands; anything else (set/reboot/…) goes in the field below.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ver", "clock", "advert").forEach { c ->
            AssistChip(onClick = { session.sendRepeaterCommand(contact, c) }, label = { Text(c) })
        }
    }

    ConsoleCard(s.console)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = command, onValueChange = { command = it },
            label = { Text("Command") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        Button(onClick = {
            session.sendRepeaterCommand(contact, command); command = ""
        }) { Text("Send") }
    }
}

@Composable
private fun StatusCard(st: RepeaterStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            kv("Battery", "${st.batteryMilliVolts} mV")
            kv("Uptime", fmtUptime(st.uptimeSecs))
            kv("TX queue", "${st.txQueueLen}")
            kv("Airtime tx / rx", "${st.airtimeSecs}s / ${st.airtimeRxSecs}s")
            kv("Packets recv / sent", "${st.nPacketsRecv} / ${st.nPacketsSent}")
            kv("Recv flood / direct", "${st.recvFlood} / ${st.recvDirect}")
            kv("Sent flood / direct", "${st.sentFlood} / ${st.sentDirect}")
            kv("Dups direct / flood", "${st.directDups} / ${st.floodDups}")
            kv("Noise / last RSSI", "${st.noiseFloor} / ${st.lastRssi} dBm")
            kv("Last SNR", "%.1f dB".format(st.lastSnrQ / 4.0))
            kv("Errors / recv errors", "${st.errEvents} / ${st.recvErrors}")
        }
    }
}

@Composable
private fun ConsoleCard(lines: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).heightIn(min = 80.dp, max = 260.dp).verticalScroll(rememberScrollState())) {
            if (lines.isEmpty()) {
                Text("No command output yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                SelectionContainer {
                    Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun kv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun fmtUptime(secs: Long): String {
    val d = secs / 86400; val h = (secs % 86400) / 3600; val m = (secs % 3600) / 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (d > 0 || h > 0) append("${h}h ")
        append("${m}m")
    }
}
