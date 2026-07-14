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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.ChannelEntry
import org.thepacket.meshcore.app.Conversation
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.app.PublicChannel
import org.thepacket.meshcore.app.RegionChannels
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
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    tab: Int = 0,
    onTab: (Int) -> Unit = {},
) {
    // An exported contact card arrives asynchronously — show it for copy/share.
    var exportedCard by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session) {
        session.exportedContact.collect { exportedCard = it }
    }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("All contacts") })
            Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("Device contacts") })
            Tab(selected = tab == 2, onClick = { onTab(2) }, text = { Text("Channels") })
        }
        when (tab) {
            0 -> AllContactsList(session, self, onOpenConversation, onShowOnMap)
            1 -> ContactsList(session, self, contacts, onOpenConversation, onShowOnMap)
            else -> ChannelsList(session, channels, onOpenConversation)
        }
    }

    exportedCard?.let { card ->
        ExportCardDialog(card) { exportedCard = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllContactsList(
    session: MeshSession,
    self: SelfInfo?,
    onOpen: (String, String) -> Unit,
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val allContacts by session.allContacts.collectAsStateWithLifecycle()
    val deviceContacts by session.contacts.collectAsStateWithLifecycle()
    val contactTelemetry by session.contactTelemetry.collectAsStateWithLifecycle()
    val contactMma by session.contactMma.collectAsStateWithLifecycle()
    val pathDiscovery by session.pathDiscovery.collectAsStateWithLifecycle()
    val advertPaths by session.advertPaths.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Contact?>(null) }
    var query by remember { mutableStateOf("") }
    var confirmSend by remember { mutableStateOf(false) }
    // Sub-tab filter by contact type: 0 = Clients, 1 = Repeaters, 2 = Room Servers, 3 = Sensors.
    var typeFilter by remember { mutableStateOf(0) }
    // Region filter (null = all). Regions are learned from observed adverts; untagged = home (Ottawa).
    var regionFilter by remember { mutableStateOf<String?>(null) }
    val regions = remember(allContacts) {
        allContacts.map { NodeResolver.regionOf(it.region) }.distinct().sorted()
    }
    val regionScoped = remember(allContacts, regionFilter) {
        if (regionFilter == null) allContacts
        else allContacts.filter { NodeResolver.regionOf(it.region) == regionFilter }
    }

    // Toast the outcome of a push once it completes.
    LaunchedEffect(session) {
        session.contactPushResult.collect { n ->
            val msg = if (n == 0) "All contacts already on device"
            else "Sent $n contact${if (n == 1) "" else "s"} to device"
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Keys already present on the connected device — so we can show what a push would add.
    val onDeviceKeys = remember(deviceContacts) { deviceContacts.map { it.publicKey.toHex() }.toSet() }
    val missingCount = remember(allContacts, onDeviceKeys) {
        allContacts.count { it.publicKey.toHex() !in onDeviceKeys }
    }

    // Split the region-scoped address book by type once, so each filter tab shows a live count.
    val clients = remember(regionScoped) {
        regionScoped.filter {
            it.type != ContactType.REPEATER && it.type != ContactType.ROOM && it.type != ContactType.SENSOR
        }
    }
    val repeaters = remember(regionScoped) { regionScoped.filter { it.type == ContactType.REPEATER } }
    val rooms = remember(regionScoped) { regionScoped.filter { it.type == ContactType.ROOM } }
    val sensors = remember(regionScoped) { regionScoped.filter { it.type == ContactType.SENSOR } }
    val byType = when (typeFilter) {
        1 -> repeaters
        2 -> rooms
        3 -> sensors
        else -> clients
    }

    val sorted = remember(byType) {
        byType.sortedBy { (it.name.ifBlank { it.keyPrefixHex }).lowercase() }
    }
    val filtered = remember(sorted, query) {
        val q = query.trim()
        if (q.isEmpty()) sorted
        else sorted.filter {
            it.name.contains(q, ignoreCase = true) || it.keyPrefixHex.contains(q, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedButton(
                onClick = { confirmSend = true },
                enabled = self != null && missingCount > 0,
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (missingCount > 0) "  Send $missingCount to device" else "  All on device")
            }
        }
        if (regions.size > 1) {
            RegionFilterRow(regions = regions, selected = regionFilter, onSelect = { regionFilter = it })
        }
        TypeFilterRow(
            selected = typeFilter,
            onSelect = { typeFilter = it },
            clientCount = clients.size,
            repeaterCount = repeaters.size,
            roomCount = rooms.size,
            sensorCount = sensors.size,
        )
        if (allContacts.isNotEmpty()) {
            CompactSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        val typeLabel = when (typeFilter) {
            1 -> "repeaters"; 2 -> "room servers"; 3 -> "sensors"; else -> "clients"
        }
        if (allContacts.isEmpty()) {
            EmptyHint("No contacts collected yet.\nConnect to a device to build your address book.")
        } else if (byType.isEmpty()) {
            EmptyHint("No $typeLabel in your address book.")
        } else if (filtered.isEmpty()) {
            EmptyHint("No $typeLabel match \"$query\".")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.keyPrefixHex }) { c ->
                    val onDevice = c.publicKey.toHex() in onDeviceKeys
                    ConversationRow(
                        icon = if (c.isRepeater) Icons.Default.Router else Icons.Default.Person,
                        tint = nameColor(c.name.ifBlank { c.keyPrefixHex }),
                        title = c.name.ifBlank { c.keyPrefixHex },
                        subtitle = contactTypeLabel(c.type) + if (onDevice) " · on device" else "",
                        onClick = { detail = c },
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
            onRemove = { session.removeContact(c); session.forgetAggregateContact(c); detail = null },
            onRequestTelemetry = { session.requestTelemetry(c) },
            telemetry = contactTelemetry[c.keyPrefixHex],
            onRequestMma = { session.requestContactMma(c) },
            mma = contactMma[c.keyPrefixHex],
            onManage = null,
            onShowOnMap = onShowOnMap,
            onDiscoverPath = { session.discoverPath(c.publicKey) },
            pathResult = pathDiscovery[c.keyPrefixHex],
            onRequestAdvertPath = { session.requestAdvertPath(c) },
            advertPath = advertPaths[c.keyPrefixHex],
            advertPathLoaded = advertPaths.containsKey(c.keyPrefixHex),
        )
    }

    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Send contacts to device?") },
            text = {
                Text(
                    "Add $missingCount contact${if (missingCount == 1) "" else "s"} from your address " +
                        "book to the connected device. Contacts it already has are left untouched.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSend = false; session.pushAllContactsToDevice() }) {
                    Text("Send")
                }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TypeFilterRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    clientCount: Int,
    repeaterCount: Int,
    roomCount: Int,
    sensorCount: Int,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == 0, onClick = { onSelect(0) },
            label = { Text("Clients ($clientCount)") })
        FilterChip(selected = selected == 1, onClick = { onSelect(1) },
            label = { Text("Repeaters ($repeaterCount)") })
        FilterChip(selected = selected == 2, onClick = { onSelect(2) },
            label = { Text("Room Servers ($roomCount)") })
        FilterChip(selected = selected == 3, onClick = { onSelect(3) },
            label = { Text("Sensors ($sensorCount)") })
    }
}

/** Region filter chips ("All" + each learned region). Shown only when the book spans 2+ regions. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RegionFilterRow(regions: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All regions") })
        regions.forEach { r ->
            FilterChip(selected = selected == r, onClick = { onSelect(r) }, label = { Text(r) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactsList(
    session: MeshSession,
    self: SelfInfo?,
    contacts: List<Contact>,
    onOpen: (String, String) -> Unit,
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
) {
    var detail by remember { mutableStateOf<Contact?>(null) }
    var manage by remember { mutableStateOf<Contact?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Sub-tab filter by contact type: 0 = Clients, 1 = Repeaters, 2 = Room Servers, 3 = Sensors.
    var typeFilter by remember { mutableStateOf(0) }
    val contactTelemetry by session.contactTelemetry.collectAsStateWithLifecycle()
    val contactMma by session.contactMma.collectAsStateWithLifecycle()
    val pathDiscovery by session.pathDiscovery.collectAsStateWithLifecycle()
    val advertPaths by session.advertPaths.collectAsStateWithLifecycle()
    val unread by session.unread.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Repeater/room management takes over the whole pane when open.
    manage?.let { c ->
        RepeaterScreen(session, c, onBack = { manage = null })
        return
    }

    // Split by type once, so each filter tab shows a live count.
    val clients = remember(contacts) {
        contacts.filter {
            it.type != ContactType.REPEATER && it.type != ContactType.ROOM && it.type != ContactType.SENSOR
        }
    }
    val repeaters = remember(contacts) { contacts.filter { it.type == ContactType.REPEATER } }
    val rooms = remember(contacts) { contacts.filter { it.type == ContactType.ROOM } }
    val sensors = remember(contacts) { contacts.filter { it.type == ContactType.SENSOR } }
    val byType = when (typeFilter) {
        1 -> repeaters
        2 -> rooms
        3 -> sensors
        else -> clients
    }

    // Sorted alphabetically by display name (case-insensitive).
    val sorted = remember(byType) {
        byType.sortedBy { (it.name.ifBlank { it.keyPrefixHex }).lowercase() }
    }

    // Filter by the search query against display name and key prefix.
    val filtered = remember(sorted, query) {
        val q = query.trim()
        if (q.isEmpty()) sorted
        else sorted.filter {
            it.name.contains(q, ignoreCase = true) || it.keyPrefixHex.contains(q, ignoreCase = true)
        }
    }

    // After an import, the list is rebuilt and LazyColumn keeps its scroll anchor, so a
    // newly-added row can land off-screen. Scroll to the imported contact once it appears
    // in the (possibly not-yet-recomposed) sorted list.
    val filteredState = rememberUpdatedState(filtered)
    val contactsState = rememberUpdatedState(contacts)
    LaunchedEffect(session) {
        session.importedContact.collect { key ->
            // Make sure the imported contact's filter tab is active, else its row is hidden
            // and the scroll below would wait forever.
            contactsState.value.firstOrNull { it.keyPrefixHex == key }?.let { c ->
                typeFilter = when (c.type) {
                    ContactType.REPEATER -> 1
                    ContactType.ROOM -> 2
                    ContactType.SENSOR -> 3
                    else -> 0
                }
            }
            // Wait until the imported row is present in the data, then let the rebuilt list
            // fully settle before scrolling — scrolling mid-rebuild uses stale measurements
            // and the LazyColumn re-anchors to the previously-first key, hiding the new row.
            snapshotFlow { filteredState.value.indexOfFirst { it.keyPrefixHex == key } }.first { it >= 0 }
            delay(350)
            val idx = filteredState.value.indexOfFirst { it.keyPrefixHex == key }
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
        TypeFilterRow(
            selected = typeFilter,
            onSelect = { typeFilter = it },
            clientCount = clients.size,
            repeaterCount = repeaters.size,
            roomCount = rooms.size,
            sensorCount = sensors.size,
        )
        if (contacts.isNotEmpty()) {
            CompactSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        val typeLabel = when (typeFilter) {
            1 -> "repeaters"; 2 -> "room servers"; 3 -> "sensors"; else -> "clients"
        }
        if (contacts.isEmpty()) {
            EmptyHint("No contacts synced yet.")
        } else if (byType.isEmpty()) {
            EmptyHint("No $typeLabel on this device.")
        } else if (filtered.isEmpty()) {
            EmptyHint("No $typeLabel match \"$query\".")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.keyPrefixHex }) { c ->
                    ConversationRow(
                        icon = if (c.isRepeater) Icons.Default.Router else Icons.Default.Person,
                        tint = nameColor(c.name.ifBlank { c.keyPrefixHex }),
                        title = c.name.ifBlank { c.keyPrefixHex },
                        subtitle = contactTypeLabel(c.type),
                        // A repeater isn't a chat target — tap opens its management screen instead.
                        onClick = {
                            if (c.type == ContactType.REPEATER) manage = c
                            else onOpen(Conversation.dmId(c), c.name.ifBlank { c.keyPrefixHex })
                        },
                        onLongClick = { detail = c },
                        unread = unread[Conversation.dmId(c)] ?: 0,
                    )
                }
            }
        }
    }

    detail?.let { sel ->
        // Re-read this contact's current device record on open (learned path, position, last
        // advert), and reflect the live version so those fields update in place.
        LaunchedEffect(sel.keyPrefixHex) { session.refreshContact(sel) }
        val c = contacts.firstOrNull { it.publicKey.contentEquals(sel.publicKey) } ?: sel
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
            onRequestMma = { session.requestContactMma(c) },
            mma = contactMma[c.keyPrefixHex],
            onManage = if (c.type == ContactType.REPEATER || c.type == ContactType.ROOM) {
                { manage = c; detail = null }
            } else null,
            onShowOnMap = onShowOnMap,
            onDiscoverPath = { session.discoverPath(c.publicKey) },
            pathResult = pathDiscovery[c.keyPrefixHex],
            onRequestAdvertPath = { session.requestAdvertPath(c) },
            advertPath = advertPaths[c.keyPrefixHex],
            advertPathLoaded = advertPaths.containsKey(c.keyPrefixHex),
        )
    }

    if (showImport) {
        ImportContactDialog(
            onDismiss = { showImport = false },
            onImport = { hex -> session.importContact(hex); showImport = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChannelsList(
    session: MeshSession,
    channels: List<ChannelEntry>,
    onOpen: (String, String) -> Unit,
) {
    val deviceInfo by session.deviceInfo.collectAsStateWithLifecycle()
    val unread by session.unread.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<ChannelEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var addingRegion by remember { mutableStateOf(false) }

    val maxChannels = deviceInfo?.maxChannels?.takeIf { it > 0 } ?: 8
    val existing = channels.map { it.index }.toSet()
    // Slot 0 is reserved for the protected Public channel; new channels go in slots 1+.
    val freeSlot = (1 until maxChannels).firstOrNull { it !in existing }
    val freeSlots = (1 until maxChannels).count { it !in existing }
    val loaded = channels.isNotEmpty() // device always has ≥1 channel; empty = not synced yet
    // The Public channel needs restoring if slot 0 is missing or no longer the canonical one.
    val publicEntry = channels.firstOrNull { it.index == PublicChannel.INDEX }
    val publicNeedsRestore = loaded && (publicEntry == null || !PublicChannel.isCanonical(publicEntry))

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { creating = true }, enabled = loaded && freeSlot != null) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (loaded && freeSlot == null) "  Channels full" else "  New channel")
            }
            OutlinedButton(onClick = { addingRegion = true }, enabled = loaded && freeSlots > 0) {
                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Add region")
            }
            if (publicNeedsRestore) {
                OutlinedButton(onClick = { session.restorePublicChannel() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Restore Public")
                }
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
                    // The Public channel (slot 0) is protected: no rename/re-key/delete.
                    val protectedPublic = ch.index == PublicChannel.INDEX
                    ConversationRow(
                        icon = Icons.Default.Campaign,
                        tint = MaterialTheme.colorScheme.tertiary,
                        title = ch.displayName,
                        subtitle = if (protectedPublic) "Channel ${ch.index} · protected"
                        else "Channel ${ch.index} · long-press to edit",
                        onClick = { onOpen(Conversation.channelId(ch.index), ch.displayName) },
                        onLongClick = if (protectedPublic) null else ({ editing = ch }),
                        unread = unread[Conversation.channelId(ch.index)] ?: 0,
                    )
                }
            }
        }
    }

    if (addingRegion) {
        RegionChannelsDialog(
            freeSlots = freeSlots,
            onAdd = { picked ->
                val n = session.addChannels(picked.map { it.name to it.secret })
                Toast.makeText(ctx, "Added $n channel(s)", Toast.LENGTH_SHORT).show()
                addingRegion = false
            },
            onDismiss = { addingRegion = false },
        )
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
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text = it.trim() } // fill the field so the user can review, then Import
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import contact") },
        text = {
            Column {
                Text("Scan a contact QR, or paste a contact card (hex) exported from another node.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                OutlinedButton(
                    onClick = {
                        scanLauncher.launch(
                            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setOrientationLocked(false).setBeepEnabled(false)
                                .setPrompt("Scan a contact QR")
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Scan QR") }
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
internal fun ExportCardDialog(card: String, onDismiss: () -> Unit) {
    // Encode the official MeshCore contact URL so other clients (incl. the official app) can scan it.
    val qr = rememberQrBitmap("meshcore://$card")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contact card") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qr != null) {
                    Text("Scan this from any MeshCore app to add this contact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Image(
                        bitmap = qr, contentDescription = "Contact QR code",
                        modifier = Modifier.size(240.dp),
                    )
                }
                SelectionContainer {
                    Text(card, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    var showKeyQr by remember { mutableStateOf(false) }
    val cleanSecret = secretHex.trim().replace(" ", "").lowercase()
    val secretValid = cleanSecret.length == 32 && cleanSecret.all { it in "0123456789abcdef" }
    // Scan a shared channel-key QR (the on-device UI shows the key as base64) into the field.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { secretHex = parseChannelKeyHex(it) ?: it.trim() }
    }

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
                val isHashtag = name.trim().startsWith("#")
                Text(
                    "Tip: for a community #channel (e.g. #salishmesh), type its name above, then tap " +
                        "“Key from #name” to derive the shared key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { secretHex = randomSecretHex() }) { Text("Randomize") }
                    OutlinedButton(
                        onClick = { secretHex = RegionChannels.hashChannelSecret(name.trim()).toHex() },
                        enabled = isHashtag,
                    ) { Text("Key from #name") }
                    OutlinedButton(onClick = {
                        scanLauncher.launch(
                            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setOrientationLocked(false).setBeepEnabled(false)
                                .setPrompt("Scan a channel-key QR")
                        )
                    }) { Text("Scan key") }
                    OutlinedButton(onClick = { showKeyQr = true }, enabled = secretValid) { Text("Key QR") }
                }
                Text("Slot $slot", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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

    if (showKeyQr && secretValid) {
        ChannelKeyQrDialog(
            name = name.trim().ifBlank { "Channel $slot" },
            secretHex = cleanSecret,
            onDismiss = { showKeyQr = false },
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

/**
 * The channel's pre-shared key as a scannable QR (base64, the format the on-device UI
 * shows and accepts), so others can join by scanning — plus both text forms to copy.
 */
@Composable
private fun ChannelKeyQrDialog(name: String, secretHex: String, onDismiss: () -> Unit) {
    val base64 = remember(secretHex) {
        java.util.Base64.getEncoder().encodeToString(secretHex.hexToBytes())
    }
    val qr = rememberQrBitmap(base64)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Key for “$name”") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Others scan this (or enter the key) to join the channel. " +
                    "They'll also need the channel name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                if (qr != null) {
                    Image(bitmap = qr, contentDescription = "Channel key QR code",
                        modifier = Modifier.size(220.dp))
                }
                SelectionContainer {
                    Column {
                        Text(base64, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(secretHex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Normalize a scanned/pasted channel key to 32 hex chars: accepts hex (with spaces)
 * or a base64 128-bit key (what the on-device UI displays). Null if it's neither.
 */
private fun parseChannelKeyHex(content: String): String? {
    val c = content.trim().replace(" ", "")
    val asHex = c.lowercase()
    if (asHex.length == 32 && asHex.all { it in "0123456789abcdef" }) return asHex
    return runCatching { java.util.Base64.getDecoder().decode(c) }.getOrNull()
        ?.takeIf { it.size == 16 }?.toHex()
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

/**
 * A compact outlined search field. Built from BasicTextField + the outlined decoration box so
 * we can shrink the vertical padding (a forced height on OutlinedTextField clips the text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = query,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                placeholder = { Text("Search contacts", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(20.dp))
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            )
        },
    )
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
    unread: Int = 0,
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
            if (unread > 0) UnreadBadge(unread)
        }
    }
}

/** A small pill showing the number of unread incoming messages in a conversation. */
@Composable
private fun UnreadBadge(count: Int) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
        Box(Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center) {
            Text(
                if (count > 99) "99+" else "$count",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
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
