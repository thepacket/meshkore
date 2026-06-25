package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.ChannelEntry
import org.thepacket.meshcore.app.Conversation
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.toHex
import org.thepacket.meshcore.protocol.hexToBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.random.Random

@Composable
fun HomeContent(
    session: MeshSession,
    self: SelfInfo?,
    channels: List<ChannelEntry>,
    contacts: List<Contact>,
    onOpenConversation: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    // An exported contact card arrives asynchronously — show it for copy/share.
    var exportedCard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        session.exportedContact.collect { exportedCard = it }
    }

    Column(modifier.fillMaxSize()) {
        self?.let {
            Box(Modifier.padding(12.dp)) { DeviceHeader(it) }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Contacts") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Channels") })
        }
        when (tab) {
            0 -> ContactsList(session, self, contacts, onOpenConversation)
            else -> ChannelsList(session, channels, onOpenConversation)
        }
    }

    exportedCard?.let { card ->
        ExportCardDialog(card) { exportedCard = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactsList(
    session: MeshSession,
    self: SelfInfo?,
    contacts: List<Contact>,
    onOpen: (String, String) -> Unit,
) {
    var detail by remember { mutableStateOf<Contact?>(null) }
    var manage by remember { mutableStateOf<Contact?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val contactTelemetry by session.contactTelemetry.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Repeater/room management takes over the whole pane when open.
    manage?.let { c ->
        RepeaterScreen(session, c, onBack = { manage = null })
        return
    }

    // Sorted alphabetically by display name (case-insensitive).
    val sorted = remember(contacts) {
        contacts.sortedBy { (it.name.ifBlank { it.keyPrefixHex }).lowercase() }
    }

    // After an import, the list is rebuilt and LazyColumn keeps its scroll anchor, so a
    // newly-added row can land off-screen. Scroll to the imported contact once it appears
    // in the (possibly not-yet-recomposed) sorted list.
    val sortedState = rememberUpdatedState(sorted)
    LaunchedEffect(session) {
        session.importedContact.collect { key ->
            // Wait until the imported row is present in the data, then let the rebuilt list
            // fully settle before scrolling — scrolling mid-rebuild uses stale measurements
            // and the LazyColumn re-anchors to the previously-first key, hiding the new row.
            snapshotFlow { sortedState.value.indexOfFirst { it.keyPrefixHex == key } }.first { it >= 0 }
            delay(350)
            val idx = sortedState.value.indexOfFirst { it.keyPrefixHex == key }
            if (idx >= 0) listState.animateScrollToItem(idx)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = { showImport = true }) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Import contact")
            }
        }
        if (sorted.isEmpty()) {
            EmptyHint("No contacts synced yet.")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sorted, key = { it.keyPrefixHex }) { c ->
                    ConversationRow(
                        icon = if (c.isRepeater) Icons.Default.Router else Icons.Default.Person,
                        tint = nameColor(c.name.ifBlank { c.keyPrefixHex }),
                        title = c.name.ifBlank { c.keyPrefixHex },
                        subtitle = contactTypeLabel(c.type),
                        onClick = { onOpen(Conversation.dmId(c), c.name.ifBlank { c.keyPrefixHex }) },
                        onLongClick = { detail = c },
                    )
                }
            }
        }
    }

    detail?.let { c ->
        NodeDetailSheet(
            name = c.name.ifBlank { c.keyPrefixHex },
            type = c.type,
            isSelf = false,
            contact = c,
            heard = null,
            self = self,
            onDismiss = { detail = null },
            onShare = { session.shareContact(c); detail = null },
            onResetPath = { session.resetPath(c) },
            onExport = { session.exportContact(c); detail = null },
            onRemove = { session.removeContact(c); detail = null },
            onRequestTelemetry = { session.requestTelemetry(c) },
            telemetry = contactTelemetry[c.keyPrefixHex],
            onManage = if (c.type == ContactType.REPEATER || c.type == ContactType.ROOM) {
                { manage = c; detail = null }
            } else null,
        )
    }

    if (showImport) {
        ImportContactDialog(
            onDismiss = { showImport = false },
            onImport = { hex -> session.importContact(hex); showImport = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelsList(
    session: MeshSession,
    channels: List<ChannelEntry>,
    onOpen: (String, String) -> Unit,
) {
    val deviceInfo by session.deviceInfo.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ChannelEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    val maxChannels = deviceInfo?.maxChannels?.takeIf { it > 0 } ?: 8
    val existing = channels.map { it.index }.toSet()
    // Always pick a FREE slot above existing ones — never silently land on an occupied
    // slot (which would clobber a channel, e.g. Public at slot 0).
    val freeSlot = (0 until maxChannels).firstOrNull { it !in existing }
    val loaded = channels.isNotEmpty() // device always has ≥1 channel; empty = not synced yet

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = { creating = true }, enabled = loaded && freeSlot != null) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (loaded && freeSlot == null) "  Channels full" else "  New channel")
            }
        }
        if (channels.isEmpty()) {
            EmptyHint("Loading channels…")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(channels, key = { "ch:${it.index}" }) { ch ->
                    ConversationRow(
                        icon = Icons.Default.Campaign,
                        tint = MaterialTheme.colorScheme.tertiary,
                        title = ch.displayName,
                        subtitle = "Channel ${ch.index} · long-press to edit",
                        onClick = { onOpen(Conversation.channelId(ch.index), ch.displayName) },
                        onLongClick = { editing = ch },
                    )
                }
            }
        }
    }

    if (creating && freeSlot != null) {
        ChannelDialog(
            title = "New channel",
            slot = freeSlot,
            initialName = "",
            initialSecretHex = randomSecretHex(),
            currentAtSlot = channels.firstOrNull { it.index == freeSlot }, // expected null (free)
            onDismiss = { creating = false },
            onSave = { name, secret -> session.setChannel(freeSlot, name, secret); creating = false },
        )
    }
    editing?.let { ch ->
        ChannelDialog(
            title = "Edit channel ${ch.index}",
            slot = ch.index,
            initialName = ch.name,
            initialSecretHex = ch.secret.toHex(),
            currentAtSlot = ch,
            onDismiss = { editing = null },
            onSave = { name, secret -> session.setChannel(ch.index, name, secret); editing = null },
            onDelete = { session.deleteChannel(ch.index); editing = null },
        )
    }
}

@Composable
private fun ImportContactDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import contact") },
        text = {
            Column {
                Text("Paste a contact card (hex) exported from another node.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Contact card") }, singleLine = false, maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportCardDialog(card: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contact card") },
        text = {
            SelectionContainer {
                Text(card, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ChannelDialog(
    title: String,
    slot: Int,
    initialName: String,
    initialSecretHex: String,
    currentAtSlot: ChannelEntry?,
    onDismiss: () -> Unit,
    onSave: (name: String, secret: ByteArray) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var secretHex by remember { mutableStateOf(initialSecretHex) }
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val cleanSecret = secretHex.trim().replace(" ", "").lowercase()
    val secretValid = cleanSecret.length == 32 && cleanSecret.all { it in "0123456789abcdef" }

    fun commit() = onSave(name.trim(), cleanSecret.hexToBytes())

    // Would saving change an existing channel in this slot (rename or re-key)?
    fun replacesExisting(): Boolean {
        val cur = currentAtSlot ?: return false
        return cur.name != name.trim() || !cur.secret.contentEquals(cleanSecret.hexToBytes())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secretHex, onValueChange = { secretHex = it },
                    label = { Text("Secret (32 hex chars = 128-bit)") }, singleLine = true,
                    isError = !secretValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { secretHex = randomSecretHex() }) { Text("Randomize key") }
                    Text("Slot $slot", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete channel", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (replacesExisting()) confirmReplace = true else commit() },
                enabled = name.isNotBlank() && secretValid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmReplace && currentAtSlot != null) {
        val isPublic = slot == 0 || currentAtSlot.displayName.equals("Public", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace “${currentAtSlot.displayName}”?") },
            text = {
                Text(
                    "Slot $slot already holds “${currentAtSlot.displayName}”. Saving will overwrite its " +
                        "name/key and clear its messages." +
                        if (isPublic) "\n\nThis is the Public channel — replacing it removes Public from this device." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; commit() }) {
                    Text("Replace", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete && onDelete != null) {
        val isPublic = slot == 0 || currentAtSlot?.displayName.equals("Public", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete channel?") },
            text = {
                Text(
                    "“${currentAtSlot?.displayName ?: name}” will be removed from this device and its messages cleared." +
                        if (isPublic) "\n\nThis is the Public channel — you can re-add it later by creating a channel named \"Public\" with the public key." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** A fresh random 128-bit channel key as 32 hex chars. */
private fun randomSecretHex(): String =
    ByteArray(16).also { Random.nextBytes(it) }.toHex()

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun DeviceHeader(self: SelfInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(self.name.ifBlank { "(unnamed node)" }, style = MaterialTheme.typography.titleLarge)
            Text("${self.freqMhz} MHz · ${self.bwKhz} kHz · SF${self.radioSf} · 4/${self.radioCr} · ${self.txPower} dBm",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = tint.copy(alpha = 0.18f), shape = CircleShape) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tint)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

private fun contactTypeLabel(type: Int) = when (type) {
    ContactType.CHAT -> "Contact"
    ContactType.REPEATER -> "Repeater"
    ContactType.ROOM -> "Room"
    ContactType.SENSOR -> "Sensor"
    else -> "Node"
}

/** Stable per-name colour (matches the on-device UI's hashed-username idea). */
fun nameColor(name: String): Color {
    val palette = listOf(
        Color(0xFF4ADE80), Color(0xFF60A5FA), Color(0xFFF59E0B), Color(0xFFF472B6),
        Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFFFB7185), Color(0xFF34D399),
    )
    return palette[abs(name.hashCode()) % palette.size]
}
