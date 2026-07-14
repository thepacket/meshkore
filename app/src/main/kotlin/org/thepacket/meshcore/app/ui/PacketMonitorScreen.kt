package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.thepacket.meshcore.app.ChannelEntry
import org.thepacket.meshcore.app.ChatMessage
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.app.haversineKm
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.GroupCipher
import org.thepacket.meshcore.protocol.LoRaAirtime
import org.thepacket.meshcore.protocol.PacketInspector
import org.thepacket.meshcore.protocol.ParsedPacket
import org.thepacket.meshcore.protocol.PathDiscoveryResult
import org.thepacket.meshcore.protocol.PayloadType
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.toHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PacketMonitorContent(
    packets: List<RxLog>,
    contacts: List<Contact>,
    self: SelfInfo?,
    session: MeshSession,
    modifier: Modifier = Modifier,
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    // Hoisted so filters + grouping persist across tab switches (owned by the ViewModel).
    filter: PacketFilter = PacketFilter(),
    onFilterChange: (PacketFilter) -> Unit = {},
    groupByHash: Boolean = false,
    onGroupByHashChange: (Boolean) -> Unit = {},
) {
    var detail by remember { mutableStateOf<RxLog?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    // Which hash-groups are expanded (default collapsed). Keyed by payload fingerprint.
    val expandedGroups = remember { mutableStateMapOf<Long, Boolean>() }
    // Ticks every second so each visible row's relative "… ago" age stays current. Only rows the
    // LazyColumn actually composes read it, so off-screen rows aren't recomputed.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val pathDiscovery by session.pathDiscovery.collectAsStateWithLifecycle()
    val channels by session.channels.collectAsStateWithLifecycle()
    val messages by session.messages.collectAsStateWithLifecycle()

    // Apply the field filters (card) to the live feed.
    val filtered = remember(packets, filter) {
        packets.filter { log -> filter.matches(log, PacketInspector.parse(log.raw)) }
    }

    // Region-aware name resolution for hop/source hashes (adverts' full keys, scoped by packet region).
    val resolver = remember(packets, contacts) { NodeResolver(packets, contacts) }

    // When grouping is on, fold packets sharing a payload fingerprint (flood rebroadcasts of the
    // same content) into one group. Groups keep first-seen order (newest activity first); the
    // representative shown when collapsed is the earliest received copy.
    val groups = remember(filtered, groupByHash) {
        if (!groupByHash) emptyList()
        else {
            val byHash = LinkedHashMap<Long, MutableList<RxLog>>()
            for (log in filtered) byHash.getOrPut(payloadFingerprint(log)) { mutableListOf() }.add(log)
            byHash.map { (h, members) ->
                PacketGroup(h, members.minByOrNull { it.receivedAtMs } ?: members.first(), members)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        if (packets.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = groupByHash, onCheckedChange = onGroupByHashChange)
                Text("Group by hash", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { showFilters = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (filter.isActive) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current)
                    Spacer(Modifier.size(6.dp))
                    Text(if (filter.isActive) "Filters •" else "Filters")
                }
            }
        }
        if (packets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("Listening for packets…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No packets match the filters.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (groupByHash) {
                    items(groups, key = { it.hash }) { g ->
                        if (g.members.size == 1) {
                            PacketRow(g.rep, self, resolver, now) { detail = g.rep }
                        } else {
                            val expanded = expandedGroups[g.hash] == true
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PacketRow(
                                    g.rep, self, resolver, now,
                                    badge = "×${g.members.size}",
                                    expanded = expanded,
                                    onToggle = { expandedGroups[g.hash] = !expanded },
                                ) { detail = g.rep }
                                if (expanded) {
                                    g.members.filter { it !== g.rep }.forEach { m ->
                                        Box(Modifier.padding(start = 16.dp)) {
                                            PacketRow(m, self, resolver, now) { detail = m }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(filtered, key = { System.identityHashCode(it) }) { p ->
                        PacketRow(p, self, resolver, now) { detail = p }
                    }
                }
            }
        }
    }

    detail?.let { log ->
        val parsed = remember(log) { PacketInspector.parse(log.raw) }
        val srcKey = sourcePubKey(parsed, log.region, resolver)
        PacketDetailDialog(
            log, parsed, contacts, self, resolver, onShowOnMap,
            channels = channels,
            outgoing = messages.values.flatten().filter { !it.incoming },
            allPackets = packets,
            pathResult = srcKey?.let { pathDiscovery[it.copyOf(6).toHex()] },
            onDiscoverPath = srcKey?.let { key -> { session.discoverPath(key) } },
        ) { detail = null }
    }

    if (showFilters) {
        PacketFilterDialog(
            initial = filter,
            onApply = { onFilterChange(it); showFilters = false },
            onDismiss = { showFilters = false },
        )
    }
}

/** Full 32-byte public key of a packet's source, if recoverable (advert body, else region-scoped src). */
private fun sourcePubKey(p: ParsedPacket, region: String?, resolver: NodeResolver): ByteArray? {
    p.advertPubKey?.let { if (it.size >= 32) return it.copyOf(32) }
    p.srcHash?.let { hash ->
        resolver.resolve(region, hash).contact?.takeIf { it.publicKey.size >= 32 }?.let { return it.publicKey }
    }
    return null
}

@Composable
private fun PacketDetailDialog(
    log: RxLog,
    p: ParsedPacket,
    contacts: List<Contact>,
    self: SelfInfo?,
    resolver: NodeResolver,
    onShowOnMap: (lat: Double, lon: Double) -> Unit,
    channels: List<ChannelEntry>,
    outgoing: List<ChatMessage>,
    allPackets: List<RxLog>,
    pathResult: PathDiscoveryResult?,
    onDiscoverPath: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val srcPos = sourcePosition(p, log.region, resolver)
    val group = remember(log, channels) { decodeGroup(p, channels) }
    val copies = remember(log, allPackets) { rebroadcastCopies(log, allPackets) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = srcPos?.let {
            { TextButton(onClick = { onShowOnMap(it.first, it.second); onDismiss() }) { Text("Show on map") } }
        },
        title = { Text("${p.typeName} packet") },
        text = {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    kv("Route", "${p.routeName}  (v${p.version})")
                    p.srcHash?.let { kv("Source", resolveHash(it, log.region, resolver, self)) }
                    p.destHash?.let { kv("Destination", resolveHash(it, log.region, resolver, self)) }

                    // Group message: resolve the channel and, when we hold its key, the cleartext.
                    p.channelHash?.let { h ->
                        kv("Channel", "0x%02X".format(h) +
                            (group?.channelName?.let { " · $it" } ?: " · not our channel"))
                    }
                    group?.let { g ->
                        g.text?.let { kvBlock("Message", it) }
                        g.sentAtSecs?.let { kv("Sent", epochTime(it)) }
                        g.dataType?.let { kv("Data type", "0x%04X".format(it)) }
                        if (g.decryptFailed) kv("Message", "(decrypt failed)")
                    }

                    if (p.payloadType == PayloadType.TRACE) {
                        kv("Hop SNRs", if (p.traceHopSnrsQ.isEmpty()) "(no hops yet)"
                            else p.traceHopSnrsQ.joinToString(", ") { "%.1f".format(it / 4.0) } + " dB")
                        if (p.traceRoute.isNotEmpty())
                            kv("Trace route", p.traceRoute.joinToString(" → ") { hopLabel(it, log.region, resolver) })
                    } else {
                        kv("Path", if (p.pathHashes.isEmpty()) "direct (0 hops)"
                            else "${p.pathHashes.size} hop(s): " + p.pathHashes.joinToString(" → ") { hopLabel(it, log.region, resolver) })
                    }
                    p.transportCodes?.let { kv("Transport", "0x%04X, 0x%04X".format(it[0], it[1])) }
                    p.mac?.let { kv("MAC", it.toHex()) }

                    // ADVERT — fully public, so we can show the advertiser
                    p.advertPubKey?.let { key ->
                        kv("Advertiser", resolveKey(key, contacts, self))
                        p.advertType?.let { kv("Node type", typeLabel(it)) }
                        p.advertTimestamp?.let {
                            kv("Advert time", epoch(it))
                            kv("Sender clock", clockSkew(it, log.receivedAtMs))
                        }
                        val lat = p.advertLat; val lon = p.advertLon
                        if (lat != null && lon != null) {
                            val dist = selfDistanceKm(self, lat / 1e6, lon / 1e6)
                            kv("Position", "%.5f, %.5f".format(lat / 1e6, lon / 1e6) +
                                (dist?.let { "  (${fmtDistance(it)})" } ?: ""))
                        }
                        kv("Public key", key.toHex())
                    }
                    p.ephemeralPubKey?.let { kv("Ephemeral key", it.toHex()) }
                    p.ackCode?.let { code ->
                        kv("ACK code", code.toHex())
                        if (matchesOurMessage(code, outgoing)) kv("Matches", "ACK for our message")
                    }
                    p.traceTag?.let { kv("Trace tag", "0x%08X".format(it)) }

                    kv("Received", clockTime(log.receivedAtMs) + "  (${ageLabel(log.receivedAtMs)})")
                    kv("SNR / RSSI", "${log.snrDb} dB / ${log.rssi} dBm")
                    linkMargin(log.snrDb, self)?.let { kv("Link margin", it) }
                    kv("Length", "${log.length} B  (payload ${p.payloadLen} B)")
                    self?.let { s ->
                        LoRaAirtime.airtimeMs(log.length, s.bwKhz, s.radioSf, s.radioCr)?.let {
                            kv("Airtime", "~${it.toInt()} ms")
                        }
                    }
                    kv("Header", "0x%02X".format(p.header))
                    if (copies > 1) kv("Seen", "$copies copies in log (flood rebroadcasts)")

                    if (onDiscoverPath != null) {
                        PathSection(pathResult, onDiscoverPath) { hopLabel(it, log.region, resolver) }
                    }

                    Text("Raw", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    Text(log.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

/** What we could recover from a GRP_TXT/GRP_DATA payload with our channel keys. */
private data class DecodedGroup(
    val channelName: String?,
    val text: String? = null,
    val sentAtSecs: Long? = null,
    val dataType: Int? = null,
    val decryptFailed: Boolean = false,
)

/** Match the channel hash against our channels and try each matching key (MAC-checked). */
private fun decodeGroup(p: ParsedPacket, channels: List<ChannelEntry>): DecodedGroup? {
    val hash = p.channelHash ?: return null
    if (p.payload.size < 2) return null
    val body = p.payload.copyOfRange(1, p.payload.size) // after the channel-hash byte
    val matching = channels.filter { it.secret.isNotEmpty() && GroupCipher.channelHash(it.secret) == hash }
    if (matching.isEmpty()) return null
    for (ch in matching) {
        val plain = GroupCipher.decrypt(ch.secret, body) ?: continue
        return when (p.payloadType) {
            PayloadType.GRP_TXT -> GroupCipher.parseGroupText(plain)?.let {
                DecodedGroup(ch.displayName, text = it.text, sentAtSecs = it.timestamp)
            } ?: DecodedGroup(ch.displayName, decryptFailed = true)
            PayloadType.GRP_DATA ->
                DecodedGroup(ch.displayName, dataType = GroupCipher.parseGroupDataType(plain))
            else -> DecodedGroup(ch.displayName)
        }
    }
    return DecodedGroup(matching.first().displayName, decryptFailed = true)
}

/** Does a 4-byte over-the-air ACK code match the expected ack of one of our sent messages? */
private fun matchesOurMessage(code: ByteArray, outgoing: List<ChatMessage>): Boolean {
    if (code.size < 4) return false
    val ack = (code[0].toLong() and 0xFF) or ((code[1].toLong() and 0xFF) shl 8) or
        ((code[2].toLong() and 0xFF) shl 16) or ((code[3].toLong() and 0xFF) shl 24)
    return ack != 0L && outgoing.any { it.expectedAck == ack }
}

/** How far the received SNR sits above the SF's demodulation floor (Semtech figures). */
private fun linkMargin(snrDb: Double, self: SelfInfo?): String? {
    val sf = self?.radioSf ?: return null
    val floor = when (sf) {
        7 -> -7.5; 8 -> -10.0; 9 -> -12.5; 10 -> -15.0; 11 -> -17.5; 12 -> -20.0
        else -> return null
    }
    return "%+.1f dB above SF%d floor".format(snrDb - floor, sf)
}

/** The sender's clock offset, judged from an advert's creation timestamp vs our receive time. */
private fun clockSkew(advertSecs: Long, receivedAtMs: Long): String {
    val d = advertSecs - receivedAtMs / 1000
    return if (d in -3..3) "in sync with ours" else "%+d s vs our clock".format(d)
}

private fun selfDistanceKm(self: SelfInfo?, lat: Double, lon: Double): Double? {
    if (self == null || (self.advLat == 0 && self.advLon == 0)) return null
    if (lat == 0.0 && lon == 0.0) return null
    return haversineKm(self.advLat / 1e6, self.advLon / 1e6, lat, lon)
}

/**
 * How many packets in the log carry this same type+payload. The path is excluded from
 * the hash, so flood rebroadcasts (whose paths grow at each hop) count as copies.
 */
private fun rebroadcastCopies(log: RxLog, all: List<RxLog>): Int {
    val target = payloadFingerprint(log)
    return all.count { payloadFingerprint(it) == target }
}

/** FNV-1a over the payload type + payload bytes (path excluded), like the on-device UI. */
private fun payloadFingerprint(log: RxLog): Long {
    val p = PacketInspector.parse(log.raw)
    var h = 2166136261L
    h = ((h xor p.payloadType.toLong()) * 16777619L) and 0xFFFFFFFFL
    for (b in p.payload) h = ((h xor (b.toLong() and 0xFF)) * 16777619L) and 0xFFFFFFFFL
    return h
}

private fun epochTime(sec: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sec * 1000))

/** A label/value where the value can be long free text (a decrypted message). */
@Composable
private fun kvBlock(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun kv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Resolve a 1-byte routing hash to a name within the packet's [region] (exact via adverts' keys). */
private fun resolveHash(hash: Int, region: String?, resolver: NodeResolver, self: SelfInfo?): String {
    val tag = "0x%02X".format(hash)
    val parts = mutableListOf<String>()
    val label = resolver.resolve(region, hash).label
    if (!label.startsWith("0x")) parts.add(label) // add the resolved name unless it's just the hex
    if (self != null && self.publicKey.isNotEmpty() && (self.publicKey[0].toInt() and 0xFF) == hash) parts.add("(me)")
    return if (parts.isEmpty()) "$tag · unknown" else "$tag · ${parts.joinToString(" · ")}"
}

private fun hopLabel(hash: Int, region: String?, resolver: NodeResolver): String =
    resolver.resolve(region, hash).label

private fun resolveKey(key: ByteArray, contacts: List<Contact>, self: SelfInfo?): String {
    if (self != null && self.publicKey.size >= 32 && self.publicKey.copyOf(32).contentEquals(key.copyOf(32))) return "(this node)"
    val c = contacts.firstOrNull { it.publicKey.size >= 32 && it.publicKey.copyOf(32).contentEquals(key.copyOf(32)) }
    return c?.name?.ifBlank { c.keyPrefixHex } ?: ("unknown · " + key.copyOf(6).toHex() + "…")
}

private fun typeLabel(type: Int) = when (type) {
    org.thepacket.meshcore.protocol.ContactType.CHAT -> "Contact"
    org.thepacket.meshcore.protocol.ContactType.REPEATER -> "Repeater"
    org.thepacket.meshcore.protocol.ContactType.ROOM -> "Room"
    org.thepacket.meshcore.protocol.ContactType.SENSOR -> "Sensor"
    else -> "Node"
}

private fun epoch(sec: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))

/** A set of packets sharing a payload fingerprint; [rep] is the earliest received copy. */
private data class PacketGroup(val hash: Long, val rep: RxLog, val members: List<RxLog>)

@Composable
private fun PacketRow(
    p: RxLog,
    self: SelfInfo?,
    resolver: NodeResolver,
    now: Long,                       // ticks each second so the relative age re-renders
    badge: String? = null,           // e.g. "×3" — a hash-group summary count
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,  // non-null => show an expand/collapse chevron
    onClick: () -> Unit,
) {
    val parsed = remember(p) { PacketInspector.parse(p.raw) }
    val source = rowSource(parsed, p.region, resolver, self)
    val selfHasGps = self != null && (self.advLat != 0 || self.advLon != 0)
    val srcPos = sourcePosition(parsed, p.region, resolver)
    val distanceKm = if (selfHasGps && srcPos != null)
        haversineKm(self!!.advLat / 1e6, self.advLon / 1e6, srcPos.first, srcPos.second) else null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = if (onToggle != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TypePill(p.payloadType, p.typeName)
        Column(Modifier.weight(1f)) {
            Text(
                source ?: "${p.length} B · ${p.routeName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (source != null) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                if (source != null) "${p.length} B · ${p.routeName} · SNR ${p.snrDb} · RSSI ${p.rssi}"
                else "SNR ${p.snrDb} · RSSI ${p.rssi}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        if (badge != null) {
            Box(
                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(badge, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(clockTime(p.receivedAtMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace)
            Text(ageLabel(p.receivedAtMs, now), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (distanceKm != null) {
                Text(fmtDistance(distanceKm), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (onToggle != null) {
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
        }
    }
}

/** Source GPS (lat, lon in degrees): advert body, else the region-scoped resolved contact. */
private fun sourcePosition(p: ParsedPacket, region: String?, resolver: NodeResolver): Pair<Double, Double>? {
    val lat = p.advertLat; val lon = p.advertLon
    if (lat != null && lon != null && (lat != 0 || lon != 0)) return (lat / 1e6) to (lon / 1e6)
    val c = p.advertPubKey?.let { key ->
        resolver.contacts.firstOrNull { it.publicKey.size >= 32 && it.publicKey.copyOf(32).contentEquals(key.copyOf(32)) }
    } ?: p.srcHash?.let { hash -> resolver.resolve(region, hash).contact }
    val cLat = c?.latDegrees ?: return null
    val cLon = c.lonDegrees ?: return null
    return cLat to cLon
}

/** A short source label for the list row: advertiser, or src-hash → node (region-scoped), else null. */
private fun rowSource(p: ParsedPacket, region: String?, resolver: NodeResolver, self: SelfInfo?): String? {
    p.advertName?.takeIf { it.isNotBlank() }?.let { return it }
    p.advertPubKey?.let { return resolveKeyShort(it, resolver.contacts, self) }
    p.srcHash?.let { return nameForHash(it, region, resolver, self) }
    return null
}

private fun nameForHash(hash: Int, region: String?, resolver: NodeResolver, self: SelfInfo?): String {
    if (self != null && self.publicKey.isNotEmpty() && (self.publicKey[0].toInt() and 0xFF) == hash) return "(me)"
    return resolver.resolve(region, hash).label
}

private fun resolveKeyShort(key: ByteArray, contacts: List<Contact>, self: SelfInfo?): String {
    if (self != null && self.publicKey.size >= 32 && self.publicKey.copyOf(32).contentEquals(key.copyOf(32))) return "(this node)"
    val c = contacts.firstOrNull { it.publicKey.size >= 32 && it.publicKey.copyOf(32).contentEquals(key.copyOf(32)) }
    return c?.name?.ifBlank { c.keyPrefixHex } ?: (key.copyOf(3).toHex() + "…")
}

@Composable
private fun TypePill(type: Int, label: String) {
    val color = payloadColor(type)
    Box(
        Modifier.background(color.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Field filters for the packet feed. Each group is a "match any of these" set (empty = don't
 * constrain); numeric fields are inclusive min/max bounds (null = open). Groups combine with AND.
 */
data class PacketFilter(
    val payloadTypes: Set<Int> = emptySet(),   // RxLog.payloadType
    val nodeTypes: Set<Int> = emptySet(),      // advert type (ADVERT packets only)
    val routes: Set<String> = emptySet(),      // RxLog.routeName ("flood"/"direct")
    val snrMin: Double? = null, val snrMax: Double? = null,
    val rssiMin: Int? = null, val rssiMax: Int? = null,
    val lenMin: Int? = null, val lenMax: Int? = null,
    val hopsMin: Int? = null, val hopsMax: Int? = null,
    val withPositionOnly: Boolean = false,
) {
    val isActive: Boolean get() = payloadTypes.isNotEmpty() || nodeTypes.isNotEmpty() || routes.isNotEmpty() ||
        snrMin != null || snrMax != null || rssiMin != null || rssiMax != null ||
        lenMin != null || lenMax != null || hopsMin != null || hopsMax != null || withPositionOnly

    fun matches(log: RxLog, p: ParsedPacket): Boolean {
        if (payloadTypes.isNotEmpty() && log.payloadType !in payloadTypes) return false
        if (routes.isNotEmpty() && log.routeName !in routes) return false
        // Node type is only carried by ADVERT packets — selecting any narrows the feed to matching adverts.
        if (nodeTypes.isNotEmpty() && (p.advertType == null || p.advertType !in nodeTypes)) return false
        snrMin?.let { if (log.snrDb < it) return false }
        snrMax?.let { if (log.snrDb > it) return false }
        rssiMin?.let { if (log.rssi < it) return false }
        rssiMax?.let { if (log.rssi > it) return false }
        lenMin?.let { if (log.length < it) return false }
        lenMax?.let { if (log.length > it) return false }
        val hops = p.pathHashes.size
        hopsMin?.let { if (hops < it) return false }
        hopsMax?.let { if (hops > it) return false }
        if (withPositionOnly) {
            val lat = p.advertLat; val lon = p.advertLon
            if (lat == null || lon == null || (lat == 0 && lon == 0)) return false
        }
        return true
    }
}

private val FILTER_PAYLOAD_TYPES = listOf(
    PayloadType.REQ, PayloadType.RESPONSE, PayloadType.TXT_MSG, PayloadType.ACK, PayloadType.ADVERT,
    PayloadType.GRP_TXT, PayloadType.GRP_DATA, PayloadType.ANON_REQ, PayloadType.PATH, PayloadType.TRACE,
    PayloadType.MULTIPART, PayloadType.CONTROL, PayloadType.RAW_CUSTOM,
)

private val FILTER_NODE_TYPES = listOf(
    ContactType.CHAT to "Contact", ContactType.REPEATER to "Repeater",
    ContactType.ROOM to "Room", ContactType.SENSOR to "Sensor",
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PacketFilterDialog(
    initial: PacketFilter,
    onApply: (PacketFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var payloadTypes by remember { mutableStateOf(initial.payloadTypes) }
    var nodeTypes by remember { mutableStateOf(initial.nodeTypes) }
    var routes by remember { mutableStateOf(initial.routes) }
    var withPositionOnly by remember { mutableStateOf(initial.withPositionOnly) }
    // Numeric bounds are edited as text and parsed on Apply (blank/invalid = no bound).
    var snrMin by remember { mutableStateOf(initial.snrMin?.let { fmtNum(it) } ?: "") }
    var snrMax by remember { mutableStateOf(initial.snrMax?.let { fmtNum(it) } ?: "") }
    var rssiMin by remember { mutableStateOf(initial.rssiMin?.toString() ?: "") }
    var rssiMax by remember { mutableStateOf(initial.rssiMax?.toString() ?: "") }
    var lenMin by remember { mutableStateOf(initial.lenMin?.toString() ?: "") }
    var lenMax by remember { mutableStateOf(initial.lenMax?.toString() ?: "") }
    var hopsMin by remember { mutableStateOf(initial.hopsMin?.toString() ?: "") }
    var hopsMax by remember { mutableStateOf(initial.hopsMax?.toString() ?: "") }

    fun build() = PacketFilter(
        payloadTypes = payloadTypes, nodeTypes = nodeTypes, routes = routes,
        snrMin = snrMin.toDoubleOrNull(), snrMax = snrMax.toDoubleOrNull(),
        rssiMin = rssiMin.toIntOrNull(), rssiMax = rssiMax.toIntOrNull(),
        lenMin = lenMin.toIntOrNull(), lenMax = lenMax.toIntOrNull(),
        hopsMin = hopsMin.toIntOrNull(), hopsMax = hopsMax.toIntOrNull(),
        withPositionOnly = withPositionOnly,
    )

    // Selected chips: cyan label, background unchanged (same transparent container as unselected).
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Color.Transparent,
        selectedLabelColor = Color(0xFF22D3EE),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141414), // dark gray filter card
        confirmButton = { TextButton(onClick = { onApply(build()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = { onApply(PacketFilter()) }) { Text("Clear all") } },
        title = { Text("Filters") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterSection("Packet type") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FILTER_PAYLOAD_TYPES.forEach { t ->
                            FilterChip(
                                selected = t in payloadTypes,
                                onClick = { payloadTypes = payloadTypes.toggle(t) },
                                label = { Text(PayloadType.name(t)) },
                                colors = chipColors,
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = t in payloadTypes,
                                    selectedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            )
                        }
                    }
                }
                FilterSection("Node type", "Matches ADVERT packets only.") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FILTER_NODE_TYPES.forEach { (t, label) ->
                            FilterChip(
                                selected = t in nodeTypes,
                                onClick = { nodeTypes = nodeTypes.toggle(t) },
                                label = { Text(label) },
                                colors = chipColors,
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = t in nodeTypes,
                                    selectedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            )
                        }
                    }
                }
                FilterSection("Route") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("flood" to "Flood", "direct" to "Direct").forEach { (v, label) ->
                            FilterChip(
                                selected = v in routes,
                                onClick = { routes = routes.toggle(v) },
                                label = { Text(label) },
                                colors = chipColors,
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = v in routes,
                                    selectedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            )
                        }
                    }
                }
                FilterSection("SNR (dB)") { RangeFields(snrMin, { snrMin = it }, snrMax, { snrMax = it }) }
                FilterSection("RSSI (dBm)") { RangeFields(rssiMin, { rssiMin = it }, rssiMax, { rssiMax = it }) }
                FilterSection("Length (bytes)") { RangeFields(lenMin, { lenMin = it }, lenMax, { lenMax = it }) }
                FilterSection("Hops") { RangeFields(hopsMin, { hopsMin = it }, hopsMax, { hopsMax = it }) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = withPositionOnly, onCheckedChange = { withPositionOnly = it })
                    Text("Only packets with a GPS position", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    )
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

private fun fmtNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

@Composable
private fun FilterSection(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        content()
    }
}

@Composable
private fun RangeFields(min: String, onMin: (String) -> Unit, max: String, onMax: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = min, onValueChange = onMin, label = { Text("min") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = max, onValueChange = onMax, label = { Text("max") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun payloadColor(type: Int): Color = when (type) {
    PayloadType.TXT_MSG, PayloadType.GRP_TXT -> Color(0xFF4ADE80)
    PayloadType.ACK -> Color(0xFF22D3EE)
    PayloadType.ADVERT -> Color(0xFFF59E0B)
    PayloadType.PATH, PayloadType.TRACE -> Color(0xFFA78BFA)
    PayloadType.REQ, PayloadType.RESPONSE, PayloadType.ANON_REQ -> Color(0xFF60A5FA)
    else -> Color(0xFF94A3B8)
}
