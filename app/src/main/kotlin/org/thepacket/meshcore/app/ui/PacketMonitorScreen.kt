package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.app.haversineKm
import org.thepacket.meshcore.protocol.Contact
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
) {
    var detail by remember { mutableStateOf<RxLog?>(null) }
    val pathDiscovery by session.pathDiscovery.collectAsStateWithLifecycle()

    if (packets.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Listening for packets…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    } else {
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(packets, key = { System.identityHashCode(it) }) { p ->
                PacketRow(p, contacts, self) { detail = p }
            }
        }
    }

    detail?.let { log ->
        val parsed = remember(log) { PacketInspector.parse(log.raw) }
        val srcKey = sourcePubKey(parsed, contacts)
        PacketDetailDialog(
            log, parsed, contacts, self, onShowOnMap,
            pathResult = srcKey?.let { pathDiscovery[it.copyOf(6).toHex()] },
            onDiscoverPath = srcKey?.let { key -> { session.discoverPath(key) } },
        ) { detail = null }
    }
}

/** Full 32-byte public key of a packet's source, if recoverable (advert body, else src-hash contact). */
private fun sourcePubKey(p: ParsedPacket, contacts: List<Contact>): ByteArray? {
    p.advertPubKey?.let { if (it.size >= 32) return it.copyOf(32) }
    p.srcHash?.let { hash ->
        contacts.firstOrNull { it.publicKey.size >= 32 && (it.publicKey[0].toInt() and 0xFF) == hash }
            ?.let { return it.publicKey }
    }
    return null
}

@Composable
private fun PacketDetailDialog(
    log: RxLog,
    p: ParsedPacket,
    contacts: List<Contact>,
    self: SelfInfo?,
    onShowOnMap: (lat: Double, lon: Double) -> Unit,
    pathResult: PathDiscoveryResult?,
    onDiscoverPath: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val srcPos = sourcePosition(p, contacts)
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
                    p.srcHash?.let { kv("Source", resolveHash(it, contacts, self)) }
                    p.destHash?.let { kv("Destination", resolveHash(it, contacts, self)) }
                    p.channelHash?.let { kv("Channel hash", "0x%02X".format(it)) }
                    kv("Path", if (p.pathHashes.isEmpty()) "direct (0 hops)"
                        else "${p.pathHashes.size} hop(s): " + p.pathHashes.joinToString(" → ") { hopLabel(it, contacts, self) })
                    p.transportCodes?.let { kv("Transport", "0x%04X, 0x%04X".format(it[0], it[1])) }
                    p.mac?.let { kv("MAC", it.toHex()) }

                    // ADVERT — fully public, so we can show the advertiser
                    p.advertPubKey?.let { key ->
                        kv("Advertiser", resolveKey(key, contacts, self))
                        p.advertType?.let { kv("Node type", typeLabel(it)) }
                        p.advertTimestamp?.let { kv("Advert time", epoch(it)) }
                        val lat = p.advertLat; val lon = p.advertLon
                        if (lat != null && lon != null)
                            kv("Position", "%.5f, %.5f".format(lat / 1e6, lon / 1e6))
                        kv("Public key", key.toHex())
                    }
                    p.ephemeralPubKey?.let { kv("Ephemeral key", it.toHex()) }
                    p.ackCode?.let { kv("ACK code", it.toHex()) }
                    p.traceTag?.let { kv("Trace tag", "0x%08X".format(it)) }

                    kv("Received", clockTime(log.receivedAtMs))
                    kv("SNR / RSSI", "${log.snrDb} dB / ${log.rssi} dBm")
                    kv("Length", "${log.length} B  (payload ${p.payloadLen} B)")
                    kv("Header", "0x%02X".format(p.header))

                    if (onDiscoverPath != null) {
                        PathSection(pathResult, onDiscoverPath) { hopLabel(it, contacts, self) }
                    }

                    Text("Raw", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    Text(log.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

@Composable
private fun kv(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Resolve a 1-byte routing hash (= first public-key byte) to contact name(s). */
private fun resolveHash(hash: Int, contacts: List<Contact>, self: SelfInfo?): String {
    val names = contacts.filter { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == hash }
        .map { it.name.ifBlank { it.keyPrefixHex } }
        .toMutableList()
    if (self != null && self.publicKey.isNotEmpty() && (self.publicKey[0].toInt() and 0xFF) == hash) names.add("(me)")
    val tag = "0x%02X".format(hash)
    return if (names.isEmpty()) "$tag · unknown" else "$tag · ${names.joinToString("/")}"
}

private fun hopLabel(hash: Int, contacts: List<Contact>, self: SelfInfo?): String {
    val name = contacts.firstOrNull { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == hash }
        ?.name?.ifBlank { null }
    return name ?: "0x%02X".format(hash)
}

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

@Composable
private fun PacketRow(p: RxLog, contacts: List<Contact>, self: SelfInfo?, onClick: () -> Unit) {
    val parsed = remember(p) { PacketInspector.parse(p.raw) }
    val source = rowSource(parsed, contacts, self)
    val selfHasGps = self != null && (self.advLat != 0 || self.advLon != 0)
    val srcPos = sourcePosition(parsed, contacts)
    val distanceKm = if (selfHasGps && srcPos != null)
        haversineKm(self!!.advLat / 1e6, self.advLon / 1e6, srcPos.first, srcPos.second) else null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(clockTime(p.receivedAtMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace)
            Text(ageLabel(p.receivedAtMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (distanceKm != null) {
                Text(fmtDistance(distanceKm), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Source GPS (lat, lon in degrees) for a packet: advert body, else the resolved contact. */
private fun sourcePosition(p: ParsedPacket, contacts: List<Contact>): Pair<Double, Double>? {
    val lat = p.advertLat; val lon = p.advertLon
    if (lat != null && lon != null && (lat != 0 || lon != 0)) return (lat / 1e6) to (lon / 1e6)
    val c = p.advertPubKey?.let { key ->
        contacts.firstOrNull { it.publicKey.size >= 32 && it.publicKey.copyOf(32).contentEquals(key.copyOf(32)) }
    } ?: p.srcHash?.let { hash ->
        contacts.firstOrNull { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == hash }
    }
    val cLat = c?.latDegrees ?: return null
    val cLon = c.lonDegrees ?: return null
    return cLat to cLon
}

/** A short source label for the list row: advertiser, or src-hash → contact, else null. */
private fun rowSource(p: ParsedPacket, contacts: List<Contact>, self: SelfInfo?): String? {
    p.advertName?.takeIf { it.isNotBlank() }?.let { return it }
    p.advertPubKey?.let { return resolveKeyShort(it, contacts, self) }
    p.srcHash?.let { return nameForHash(it, contacts, self) }
    return null
}

private fun nameForHash(hash: Int, contacts: List<Contact>, self: SelfInfo?): String {
    contacts.firstOrNull { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == hash }
        ?.name?.ifBlank { null }?.let { return it }
    if (self != null && self.publicKey.isNotEmpty() && (self.publicKey[0].toInt() and 0xFF) == hash) return "(me)"
    return "0x%02X".format(hash)
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

private fun payloadColor(type: Int): Color = when (type) {
    PayloadType.TXT_MSG, PayloadType.GRP_TXT -> Color(0xFF4ADE80)
    PayloadType.ACK -> Color(0xFF22D3EE)
    PayloadType.ADVERT -> Color(0xFFF59E0B)
    PayloadType.PATH, PayloadType.TRACE -> Color(0xFFA78BFA)
    PayloadType.REQ, PayloadType.RESPONSE, PayloadType.ANON_REQ -> Color(0xFF60A5FA)
    else -> Color(0xFF94A3B8)
}
