package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.protocol.CoreStats
import org.thepacket.meshcore.protocol.LoRaAirtime
import org.thepacket.meshcore.protocol.Lpp
import org.thepacket.meshcore.protocol.PacketStats
import org.thepacket.meshcore.protocol.PayloadType
import org.thepacket.meshcore.protocol.RadioStats
import org.thepacket.meshcore.protocol.RouteType

@Composable
fun StatsContent(
    session: MeshSession,
    radio: RadioStats?,
    core: CoreStats?,
    packets: PacketStats?,
    noiseHistory: List<Int>,
    telemetry: List<Lpp.Reading>,
    onRefreshTelemetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AddressBookCard(session)
        TrafficCard(session)
        TelemetryCard(telemetry, onRefreshTelemetry)
        NoiseCard(noiseHistory, radio?.noiseFloor)
        radio?.let { RadioCard(it) }
        core?.let { CoreCard(it) }
        packets?.let { PacketsCard(it) }
        if (radio == null && core == null) {
            Text("Reading stats…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun AddressBookCard(session: MeshSession) {
    val allContacts by session.allContacts.collectAsStateWithLifecycle()
    val deviceContacts by session.contacts.collectAsStateWithLifecycle()
    val channels by session.channels.collectAsStateWithLifecycle()
    val deviceInfo by session.deviceInfo.collectAsStateWithLifecycle()
    val maxContacts = deviceInfo?.maxContacts?.takeIf { it > 0 }
    val maxChannels = deviceInfo?.maxChannels?.takeIf { it > 0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Contacts & channels", style = MaterialTheme.typography.titleMedium)
            StatRow("Address book", "${allContacts.size}")
            StatRow(
                "Device contacts",
                if (maxContacts != null) "${deviceContacts.size} / $maxContacts" else "${deviceContacts.size}",
            )
            StatRow(
                "Device channels",
                if (maxChannels != null) "${channels.size} / $maxChannels" else "${channels.size}",
            )
        }
    }
}

/**
 * Traffic analysis over the persisted RX history (spans sessions): capture rate, estimated
 * channel-busy (airtime ÷ window), payload-type mix, flood/direct split, and duplicate
 * (flood-rebroadcast) share, for a selectable recent window (5m / 1h / all retained).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TrafficCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val self by session.self.collectAsStateWithLifecycle()
    val windows = remember { listOf("5m" to 5 * 60_000L, "1h" to 60 * 60_000L, "All" to Long.MAX_VALUE) }
    var wIdx by remember { mutableStateOf(0) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Traffic", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    windows.forEachIndexed { i, (label, _) ->
                        androidx.compose.material3.FilterChip(
                            selected = wIdx == i, onClick = { wIdx = i }, label = { Text(label) })
                    }
                }
            }

            val now = System.currentTimeMillis()
            val windowMs = windows[wIdx].second
            val pkts = remember(history, wIdx, now) {
                if (windowMs == Long.MAX_VALUE) history else history.filter { it.receivedAtMs >= now - windowMs }
            }
            if (pkts.isEmpty()) {
                Text("No packets in this window yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            // History is newest-first; span covers now back to the oldest packet in the window.
            val spanMs = (now - pkts.last().receivedAtMs).coerceAtLeast(1L)
            val count = pkts.size
            val perMin = count / (spanMs / 60_000.0)
            StatRow("Captured", "$count pkts · ${fmtDuration(spanMs / 1000)}")
            StatRow("Rate", "%.1f pkts/min".format(perMin))
            self?.let { s ->
                val airtimeMs = pkts.sumOf { LoRaAirtime.airtimeMs(it.length, s.bwKhz, s.radioSf, s.radioCr) ?: 0.0 }
                val busy = (airtimeMs / spanMs * 100).coerceIn(0.0, 100.0)
                StatRow("Channel busy", "%.1f%% · %.1fs airtime".format(busy, airtimeMs / 1000))
            }

            val flood = pkts.count { it.routeType == RouteType.FLOOD || it.routeType == RouteType.TRANSPORT_FLOOD }
            val direct = pkts.count { it.routeType == RouteType.DIRECT || it.routeType == RouteType.TRANSPORT_DIRECT }
            StatRow("Flood / direct", "$flood / $direct")
            val dups = count - pkts.distinctBy { it.hex }.size
            StatRow("Duplicate copies", "$dups (${dups * 100 / count}%)")

            Text("By type", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            pkts.groupingBy { it.payloadType }.eachCount().entries
                .sortedByDescending { it.value }
                .forEach { (type, c) -> BarRow(PayloadType.name(type), c, count) }
        }
    }
}

/** A labelled proportional bar (value / total) for the traffic breakdown. */
@Composable
private fun BarRow(label: String, value: Int, total: Int) {
    val frac = if (total > 0) value.toFloat() / total else 0f
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("$value (${(frac * 100).toInt()}%)", style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace)
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun TelemetryCard(telemetry: List<Lpp.Reading>, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("My telemetry", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
            }
            if (telemetry.isEmpty()) {
                Text("No telemetry.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                telemetry.groupBy { it.channel }.toSortedMap().forEach { (ch, readings) ->
                    Text("Channel $ch", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    readings.forEach { StatRow(telemetryLabel(it), telemetryValue(it)) }
                }
            }
        }
    }
}

internal fun telemetryLabel(r: Lpp.Reading): String = when (r.type) {
    Lpp.VOLTAGE -> if (r.channel == 1) "Battery" else "Voltage"
    Lpp.TEMPERATURE -> "Temperature"
    Lpp.RELATIVE_HUMIDITY -> "Relative humidity"
    Lpp.BAROMETRIC_PRESSURE -> "Barometric pressure"
    Lpp.ALTITUDE -> "Altitude"
    Lpp.CURRENT -> "Current"
    Lpp.POWER -> "Power"
    Lpp.GPS -> "Location"
    Lpp.LUMINOSITY -> "Luminosity"
    Lpp.DIRECTION -> "Direction"
    else -> "Type ${r.type}"
}

internal fun telemetryValue(r: Lpp.Reading): String {
    fun n(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(d)
    val v = r.values.firstOrNull() ?: return "—"
    return when (r.type) {
        Lpp.VOLTAGE -> if (r.channel == 1) {
            val pct = (((v - 3.3) / (4.2 - 3.3)) * 100).coerceIn(0.0, 100.0).toInt()
            "$pct% / ${"%.2f".format(v)}v"
        } else "${"%.2f".format(v)} V"
        Lpp.TEMPERATURE -> "${n(v)}°C / ${n(v * 9 / 5 + 32)}°F"
        Lpp.RELATIVE_HUMIDITY -> "${n(v)}%"
        Lpp.BAROMETRIC_PRESSURE -> "${n(v)} hPa"
        Lpp.ALTITUDE -> "${n(v)}m / ${n(v * 3.28084)}ft"
        Lpp.CURRENT -> "${"%.3f".format(v)} A"
        Lpp.POWER -> "${n(v)} W"
        Lpp.GPS -> if (r.values.size >= 2) "%.5f, %.5f".format(r.values[0], r.values[1]) else n(v)
        else -> r.values.joinToString(", ") { n(it) }
    }
}

@Composable
private fun NoiseCard(history: List<Int>, current: Int?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Noise floor", style = MaterialTheme.typography.titleMedium)
                Text(current?.let { "$it dBm" } ?: "—", fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            NoiseGraph(history, Modifier.fillMaxWidth().height(120.dp))
            if (history.size >= 2) {
                Text("min ${history.min()} · max ${history.max()} dBm  (last ${history.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun NoiseGraph(history: List<Int>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Canvas(modifier) {
        if (history.size < 2) return@Canvas
        // Fixed dBm window typical for LoRa noise floor; clamps keep the line on-canvas.
        val top = -40f
        val bottom = -130f
        val range = (top - bottom)
        fun y(v: Int) = size.height * (1f - ((v.coerceIn(bottom.toInt(), top.toInt()) - bottom) / range))
        val dx = size.width / (history.size - 1)

        // baseline grid lines at -60/-90/-120
        listOf(-60, -90, -120).forEach { g ->
            val gy = y(g)
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        val path = Path().apply {
            moveTo(0f, y(history[0]))
            history.forEachIndexed { i, v -> lineTo(i * dx, y(v)) }
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}

@Composable
private fun RadioCard(s: RadioStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Radio", style = MaterialTheme.typography.titleMedium)
            StatRow("Last RSSI", "${s.lastRssi} dBm")
            StatRow("Last SNR", "${s.lastSnrDb} dB")
            StatRow("TX airtime", fmtDuration(s.txAirtimeSecs))
            StatRow("RX airtime", fmtDuration(s.rxAirtimeSecs))
        }
    }
}

@Composable
private fun CoreCard(s: CoreStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device", style = MaterialTheme.typography.titleMedium)
            StatRow("Battery", "${s.batteryMilliVolts} mV")
            StatRow("Uptime", fmtDuration(s.uptimeSecs))
            StatRow("TX queue", "${s.txQueueLen}")
            if (s.errFlags != 0) StatRow("Error flags", "0x%04X".format(s.errFlags))
        }
    }
}

@Composable
private fun PacketsCard(s: PacketStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Packets", style = MaterialTheme.typography.titleMedium)
            StatRow("Received", "${s.recv}")
            StatRow("Sent", "${s.sent}")
            StatRow("Recv flood / direct", "${s.recvFlood} / ${s.recvDirect}")
            StatRow("Sent flood / direct", "${s.sentFlood} / ${s.sentDirect}")
            StatRow("Recv errors", "${s.recvErrors}")
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private fun fmtDuration(secs: Long): String {
    val d = secs / 86400; val h = (secs % 86400) / 3600; val m = (secs % 3600) / 60; val s = secs % 60
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
