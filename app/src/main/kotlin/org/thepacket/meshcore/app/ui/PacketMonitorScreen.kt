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
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.PacketInspector
import org.thepacket.meshcore.protocol.ParsedPacket
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
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<RxLog?>(null) }

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
            items(packets, key = { System.identityHashCode(it) }) { p -> PacketRow(p) { detail = p } }
        }
    }

    detail?.let { log ->
        val parsed = remember(log) { PacketInspector.parse(log.raw) }
        PacketDetailDialog(log, parsed, contacts, self) { detail = null }
    }
}

@Composable
private fun PacketDetailDialog(
    log: RxLog,
    p: ParsedPacket,
    contacts: List<Contact>,
    self: SelfInfo?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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

                    kv("SNR / RSSI", "${log.snrDb} dB / ${log.rssi} dBm")
                    kv("Length", "${log.length} B  (payload ${p.payloadLen} B)")
                    kv("Header", "0x%02X".format(p.header))
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
private fun PacketRow(p: RxLog, onClick: () -> Unit) {
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
            Text("${p.length} B · ${p.routeName}", style = MaterialTheme.typography.bodyMedium)
            Text("SNR ${p.snrDb} · RSSI ${p.rssi}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
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
