package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.ChannelEntry
import org.thepacket.meshcore.app.MeshConnection
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.app.MqttPrefs
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.GroupCipher
import org.thepacket.meshcore.protocol.CoreStats
import org.thepacket.meshcore.protocol.LoRaAirtime
import org.thepacket.meshcore.protocol.Lpp
import org.thepacket.meshcore.protocol.PacketInspector
import org.thepacket.meshcore.protocol.PacketStats
import org.thepacket.meshcore.protocol.ParsedPacket
import org.thepacket.meshcore.protocol.PayloadType
import org.thepacket.meshcore.protocol.RadioStats
import org.thepacket.meshcore.protocol.RouteType
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.toHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        MqttCard()
        core?.let { CoreCard(it) }
        radio?.let { RadioCard(it) }
        NoiseCard(noiseHistory, radio?.noiseFloor)
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

/** Live MQTT feed counters: per-broker + total packets, disconnects, and broker failovers. */
@Composable
internal fun MqttCard() {
    val fromBroker by MeshConnection.mqtt.fromBroker.collectAsStateWithLifecycle()
    val total by MeshConnection.mqtt.received.collectAsStateWithLifecycle()
    val disconnections by MeshConnection.mqtt.disconnections.collectAsStateWithLifecycle()
    val brokerChanges by MeshConnection.mqtt.brokerChanges.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("MQTT", style = MaterialTheme.typography.titleMedium)
            MqttPrefs.BROKERS.forEachIndexed { i, (_, url) ->
                val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
                StatRow("Packets from ${host.substringBefore('.')}", "${fromBroker.getOrElse(i) { 0L }}")
            }
            StatRow("Total packets", "$total")
            StatRow("Disconnections", "$disconnections")
            StatRow("Broker change", "$brokerChanges")
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
internal fun TrafficCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val self by session.self.collectAsStateWithLifecycle()
    val windows = remember { listOf("5m" to 5 * 60_000L, "1h" to 60 * 60_000L, "All" to Long.MAX_VALUE) }
    var wIdx by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Traffic", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    windows.forEachIndexed { i, (label, _) ->
                        androidx.compose.material3.FilterChip(
                            selected = wIdx == i, onClick = { wIdx = i }, label = { Text(label) })
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear traffic history")
                    }
                }
            }
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text("Clear traffic history?") },
                    text = { Text("Discards the recorded packet history that the traffic stats, chart and " +
                        "top talkers are built from. The device isn't affected; new packets re-accumulate.") },
                    confirmButton = {
                        TextButton(onClick = { confirmClear = false; session.clearPacketHistory() }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                )
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
        }
    }
}

/**
 * Top nodes by packets sent (busiest sources), over the persisted RX history. Source = advert
 * pubkey (adverts) or the 1-byte src hash; nodes sharing a hash are grouped and name-resolved.
 */
@Composable
internal fun TopTalkersCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val self by session.self.collectAsStateWithLifecycle()
    val contacts by session.allContacts.collectAsStateWithLifecycle()
    val talkers = remember(history, contacts, self) {
        val resolver = NodeResolver(history, contacts)
        val agg = HashMap<String, DoubleArray>() // node id -> [count, airtimeMs]
        val info = HashMap<String, ResolvedNode>()
        history.forEach { pkt ->
            val p = PacketInspector.parse(pkt.raw)
            val byte = sourceByte(p) ?: return@forEach
            val rn = resolver.resolve(pkt.region, byte) // resolve the source within the packet's region
            val a = agg.getOrPut(rn.id) { DoubleArray(2) }
            a[0] += 1
            self?.let { s -> a[1] += LoRaAirtime.airtimeMs(pkt.length, s.bwKhz, s.radioSf, s.radioCr) ?: 0.0 }
            info.putIfAbsent(rn.id, rn)
        }
        agg.entries.sortedByDescending { it.value[0] }.take(10)
            .map { e -> TalkerAgg(info.getValue(e.key).label, e.value[0].toInt(), e.value[1]) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Top Talkers", style = MaterialTheme.typography.titleMedium)
            Text("Busiest packet sources — count + airtime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (talkers.isEmpty()) {
                Text("No packets yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            val maxCount = talkers.first().count
            talkers.forEach { t -> TalkerRow(t.label, t.count, maxCount, t.airtimeMs) }
        }
    }
}

/** Distribution of packets by payload type over the whole persisted RX history. */
@Composable
internal fun MessageTypeCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Message Types", style = MaterialTheme.typography.titleMedium)
            Text("Packets by payload type",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (history.isEmpty()) {
                Text("No packets yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            val count = history.size
            val byType = history.groupingBy { it.payloadType }.eachCount().entries
                .sortedByDescending { it.value }
            val max = byType.firstOrNull()?.value ?: 1
            byType.forEach { (type, c) -> BarRow(PayloadType.name(type), c, count, max) }
        }
    }
}

private data class TalkerAgg(val label: String, val count: Int, val airtimeMs: Double)

/** The 1-byte source identity of a packet: advert pubkey's first byte, else the src hash; null if none. */
private fun sourceByte(p: ParsedPacket): Int? =
    p.advertPubKey?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF } ?: p.srcHash

/** A top-talker bar: proportional to packet count, with count + airtime on the right. */
@Composable
private fun TalkerRow(label: String, count: Int, maxCount: Int, airtimeMs: Double) {
    val frac = if (maxCount > 0) count.toFloat() / maxCount else 0f
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Text("$count · %.1fs".format(airtimeMs / 1000), style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
        }
    }
}

/**
 * A labelled bar for the traffic breakdown. The label shows the share of [total]; the bar length is
 * scaled to [max] (the largest value in the set) so the top row fills the width.
 */
@Composable
private fun BarRow(label: String, value: Int, total: Int, max: Int = total) {
    val frac = if (max > 0) value.toFloat() / max else 0f
    val pct = if (total > 0) value * 100 / total else 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("$value ($pct%)", style = MaterialTheme.typography.labelSmall,
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

/** SNR histogram over the RX history (higher = cleaner signal), green bars. */
@Composable
internal fun SnrDistributionCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val stats = remember(history) { distStats(history.map { it.snrDb }) }
    DistributionCard(
        title = "SNR Distribution",
        subtitle = "Signal-to-Noise Ratio (higher = cleaner signal)",
        stats = stats,
        barColor = MaterialTheme.colorScheme.secondary,
        sdUnit = "dB",
        fmt = { "%.1f dB".format(it) },
    )
}

/** RSSI histogram over the RX history (closer to 0 = stronger), blue bars. */
@Composable
internal fun RssiDistributionCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val stats = remember(history) { distStats(history.map { it.rssi.toDouble() }) }
    DistributionCard(
        title = "RSSI Distribution",
        subtitle = "Received Signal Strength (closer to 0 = stronger)",
        stats = stats,
        barColor = Color(0xFF60A5FA),
        sdUnit = "dBm",
        fmt = { "%.0f dBm".format(it) },
    )
}

/** Scatter of every packet at (SNR, RSSI), over Weak/Good/Excellent link-quality zones. */
@Composable
internal fun SnrRssiScatterCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val points = remember(history) { history.map { it.snrDb.toFloat() to it.rssi.toFloat() } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SNR vs RSSI Scatter", style = MaterialTheme.typography.titleMedium)
            Text("Each dot = one packet. Cluster position reveals link quality.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (points.isEmpty()) {
                Text("No packets yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // RSSI (y) axis ticks, top → bottom.
                Column(Modifier.height(220.dp).width(34.dp), verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End) {
                    listOf("-10", "-40", "-70", "-100", "-130").forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                ScatterCanvas(points, Modifier.weight(1f).height(220.dp).padding(start = 4.dp))
            }
            // SNR (x) axis ticks.
            Row(Modifier.fillMaxWidth().padding(start = 42.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("-10", "-6", "-2", "2", "6", "10", "14").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LegendDot(Color(0xFFEF4444), "Weak"); Box(Modifier.width(12.dp))
                LegendDot(Color(0xFF22C55E), "Excellent")
            }
        }
    }
}

@Composable
private fun ScatterCanvas(points: List<Pair<Float, Float>>, modifier: Modifier) {
    val dot = Color(0xFF60A5FA)
    Canvas(modifier) {
        val snrLo = -12f; val snrHi = 15f; val rssiLo = -130f; val rssiHi = -10f
        fun x(s: Float) = (s.coerceIn(snrLo, snrHi) - snrLo) / (snrHi - snrLo) * size.width
        fun y(r: Float) = (rssiHi - r.coerceIn(rssiLo, rssiHi)) / (rssiHi - rssiLo) * size.height
        fun zone(color: Color, x0: Float, x1: Float, rTop: Float, rBot: Float) =
            drawRect(color.copy(alpha = 0.12f), topLeft = Offset(x(x0), y(rTop)),
                size = Size(x(x1) - x(x0), y(rBot) - y(rTop)))
        zone(Color(0xFFEF4444), snrLo, 0f, -100f, rssiLo) // Weak
        zone(Color(0xFF22C55E), 6f, snrHi, rssiHi, -80f)  // Excellent
        points.forEach { (s, r) -> drawCircle(dot.copy(alpha = 0.55f), radius = 3f, center = Offset(x(s), y(r))) }
    }
}

/** Avg SNR (line) and packet volume (area) over the RX history's time span. */
@Composable
internal fun SignalQualityCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val series = remember(history) { signalOverTime(history, bins = 72) }
    val snrColor = MaterialTheme.colorScheme.secondary
    val volColor = Color(0xFF64748B)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Signal Quality Over Time", style = MaterialTheme.typography.titleMedium)
            Text("SNR trend and packet volume",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (series == null) {
                Text("No packets yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            SignalQualityChart(series, snrColor, volColor, Modifier.fillMaxWidth().height(180.dp))
            // Time axis: hour-of-day at five points from oldest to newest.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..4) {
                    val t = series.startMs + (series.endMs - series.startMs) * i / 4
                    Text(hourLabel(t), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                LegendDot(snrColor, "Avg SNR")
                Box(Modifier.width(16.dp))
                LegendDot(volColor, "Volume")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Green avg-SNR line in the upper band, navy volume area in the lower band. */
@Composable
private fun SignalQualityChart(s: SignalSeries, snrColor: Color, volColor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = s.counts.size
        if (n < 2) return@Canvas
        val dx = size.width / (n - 1)
        // Volume area: baseline at bottom, rising into the lower ~45% of the height.
        val maxVol = (s.counts.maxOrNull() ?: 0).coerceAtLeast(1)
        val volTop = size.height * 0.55f
        val volPath = Path().apply {
            moveTo(0f, size.height)
            s.counts.forEachIndexed { i, c ->
                lineTo(i * dx, size.height - (size.height - volTop) * (c.toFloat() / maxVol))
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(volPath, volColor.copy(alpha = 0.45f))
        // Avg-SNR line across present bins (bridging empty ones), mapped into the top band.
        val top = size.height * 0.08f; val bot = size.height * 0.55f
        val range = (s.snrMax - s.snrMin).coerceAtLeast(0.1)
        val line = Path()
        var started = false
        s.avgSnr.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val py = bot - ((v - s.snrMin) / range * (bot - top)).toFloat()
            val px = i * dx
            if (!started) { line.moveTo(px, py); started = true } else line.lineTo(px, py)
        }
        drawPath(line, snrColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}

private class SignalSeries(
    val avgSnr: List<Double?>, // per-bin mean SNR, null when the bin had no packets
    val counts: IntArray,
    val snrMin: Double, val snrMax: Double,
    val startMs: Long, val endMs: Long,
)

/** Bin the RX history's time span into [bins] buckets of mean SNR + packet count. */
private fun signalOverTime(history: List<RxLog>, bins: Int): SignalSeries? {
    if (history.isEmpty()) return null
    val end = history.first().receivedAtMs   // history is newest-first
    val start = history.last().receivedAtMs
    val span = (end - start).coerceAtLeast(1L)
    val sums = DoubleArray(bins); val counts = IntArray(bins)
    for (pkt in history) {
        val f = ((pkt.receivedAtMs - start).toDouble() / span).coerceIn(0.0, 1.0)
        val idx = (f * (bins - 1)).toInt()
        sums[idx] += pkt.snrDb; counts[idx]++
    }
    val avg = (0 until bins).map { if (counts[it] > 0) sums[it] / counts[it] else null }
    val present = avg.filterNotNull()
    return SignalSeries(avg, counts, present.minOrNull() ?: 0.0, present.maxOrNull() ?: 1.0, start, end)
}

private fun hourLabel(ms: Long): String =
    SimpleDateFormat("HH'h'", Locale.getDefault()).format(Date(ms))

/** Histogram of raw packet length (bytes) over the RX history, with min/avg/max. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PacketSizeCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val stats = remember(history) { distStats(history.map { it.length.toDouble() }) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Packet Size Distribution", style = MaterialTheme.typography.titleMedium)
            Text("Raw packet length in bytes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (stats == null) {
                Text("No packets yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            Text("peak ${stats.counts.max()} / bin · ${stats.count} samples",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            DistributionHistogram(stats.counts, Color(0xFF8B5CF6), Modifier.fillMaxWidth().height(140.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(stats.min, (stats.min + stats.max) / 2, stats.max).forEach {
                    Text("%.1f".format(it), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatInline("Min", "${stats.min.toInt()} B")
                StatInline("Avg", "${stats.mean.roundToInt()} B")
                StatInline("Max", "${stats.max.toInt()} B")
            }
        }
    }
}

/** Histogram of relay-hop counts per packet, with avg/median/max and a direct-packet count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HopCountDistributionCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val stats = remember(history) { hopStats(history) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hop Count Distribution", style = MaterialTheme.typography.titleMedium)
            Text("Number of repeater hops per packet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (stats == null) {
                Text("No packets yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            if (stats.counts.isNotEmpty()) {
                DistributionHistogram(stats.counts, Color(0xFF60A5FA), Modifier.fillMaxWidth().height(140.dp))
                // Integer hop-count axis: 1, midpoint, max.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(1, (1 + stats.maxHop) / 2, stats.maxHop).forEach {
                        Text("$it", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatInline("Avg", "%.1f hops".format(stats.mean))
                StatInline("Median", "${stats.median}")
                StatInline("Max", "${stats.maxHop}")
                StatInline("1-hop direct", "${stats.direct}")
            }
        }
    }
}

private class HopStats(
    val counts: IntArray,   // index i => hop count (i + 1)
    val maxHop: Int,
    val mean: Double,
    val median: Int,
    val direct: Int,        // packets that reached us directly (no relay hops)
)

/** Per-packet relay-hop counts over the RX history; null when there are no packets. */
private fun hopStats(history: List<RxLog>): HopStats? {
    if (history.isEmpty()) return null
    var direct = 0
    val hops = ArrayList<Int>()
    for (pkt in history) {
        val p = PacketInspector.parse(pkt.raw)
        if (p.payloadType == PayloadType.TRACE) continue // path field holds SNRs, not hop hashes
        val n = p.pathHashes.size
        if (n <= 0) direct++ else hops.add(n)
    }
    if (hops.isEmpty()) return HopStats(IntArray(0), 0, 0.0, 0, direct)
    val maxHop = hops.max()
    val counts = IntArray(maxHop)
    hops.forEach { counts[it - 1]++ }
    val sorted = hops.sorted()
    return HopStats(counts, maxHop, hops.average(), sorted[sorted.size / 2], direct)
}

private data class RepeaterAgg(val label: String, val count: Int)

/** A path node resolved within its region: a stable identity, a display label, and the best contact. */
internal class ResolvedNode(val id: String, val label: String, val contact: Contact?)

/**
 * Region-scoped resolver for 1-byte path hops. Adverts carry the full public key, so a hop byte seen
 * advertising in a given region resolves to an exact node — disambiguating the collisions that arise
 * because the aggregate address book (and MQTT feed) spans many regions. Falls back to a contact by
 * byte, then to the raw hash. Identity is the full-key prefix when known (so a node dedupes across
 * regions), else "region:byte" (so the same byte in different regions is counted separately).
 */
internal class NodeResolver(history: List<RxLog>, val contacts: List<Contact>) {
    private val adv = HashMap<Pair<String, Int>, Pair<ByteArray, String?>>() // (region, byte) -> (key, name)
    init {
        for (pkt in history) {
            val p = PacketInspector.parse(pkt.raw)
            val key = p.advertPubKey?.takeIf { it.size >= 32 } ?: continue
            adv.putIfAbsent(regionOf(pkt.region) to (key[0].toInt() and 0xFF),
                key.copyOf(32) to p.advertName?.trim()?.takeIf { it.isNotBlank() })
        }
    }

    fun resolve(region: String?, byte: Int): ResolvedNode {
        val r = regionOf(region)
        val a = adv[r to byte]
        val key = a?.first
        // Fallback contact match is scoped to the same region so we never label a hop with a node from
        // a different region that merely shares the 1-byte hash.
        val byByte = { t: Int? -> contacts.firstOrNull { c ->
            c.publicKey.isNotEmpty() && (c.publicKey[0].toInt() and 0xFF) == byte &&
                (t == null || c.type == t) && regionOf(c.region) == r
        } }
        val contact = key?.let { k -> contacts.firstOrNull { it.publicKey.size >= 32 && it.publicKey.copyOf(32).contentEquals(k) } }
            ?: byByte(ContactType.REPEATER) ?: byByte(null)
        val label = contact?.name?.ifBlank { null } ?: a?.second ?: "0x%02X".format(byte)
        val id = (key ?: contact?.publicKey?.takeIf { it.size >= 6 })?.copyOf(6)?.toHex() ?: "$r:$byte"
        return ResolvedNode(id, label, contact)
    }

    companion object {
        /** Region-less packets/contacts (BLE, or history from before region tagging) are assumed Ottawa. */
        fun regionOf(region: String?): String = region ?: "YOW"
    }
}

/**
 * Top nodes by how often they appear as a relay in packet paths (i.e. the busiest repeaters).
 * Hops are resolved per-region so the same 1-byte hash in different regions isn't merged.
 */
@Composable
internal fun TopRepeatersCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val contacts by session.allContacts.collectAsStateWithLifecycle()
    val top = remember(history, contacts) {
        val resolver = NodeResolver(history, contacts)
        val counts = HashMap<String, Int>()
        val info = HashMap<String, ResolvedNode>()
        for (pkt in history) {
            val p = PacketInspector.parse(pkt.raw)
            if (p.payloadType == PayloadType.TRACE) continue // path field holds SNRs, not hop hashes
            p.pathHashes.forEach { h ->
                val rn = resolver.resolve(pkt.region, h)
                counts[rn.id] = (counts[rn.id] ?: 0) + 1
                info.putIfAbsent(rn.id, rn)
            }
        }
        counts.entries.sortedByDescending { it.value }.take(16)
            .map { RepeaterAgg(info.getValue(it.key).label, it.value) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Top Repeaters", style = MaterialTheme.typography.titleMedium)
            Text("Nodes appearing most in packet paths",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (top.isEmpty()) {
                Text("No relayed packets yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            val maxCount = top.first().count
            top.forEach { RankBarRow(it.label, "%,d".format(it.count), it.count.toFloat() / maxCount, Color(0xFF60A5FA)) }
        }
    }
}

/**
 * Top nodes by number of channel messages sent, over the persisted RX history. The sender name is
 * parsed from the decrypted GRP_TXT payload ("Sender: text"); undecryptable posts count as Anonymous.
 */
@Composable
internal fun TopSendersCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val channels by session.channels.collectAsStateWithLifecycle()
    val top = remember(history, channels) {
        val counts = HashMap<String, Int>()
        for (pkt in history) {
            val p = PacketInspector.parse(pkt.raw)
            if (p.payloadType != PayloadType.GRP_TXT) continue
            val name = channelSender(p, channels)
            counts[name] = (counts[name] ?: 0) + 1
        }
        counts.entries.sortedByDescending { it.value }.take(10).map { RepeaterAgg(it.key, it.value) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Top Senders", style = MaterialTheme.typography.titleMedium)
            Text("Channel messages by sender",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (top.isEmpty()) {
                Text("No channel messages yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            val maxCount = top.first().count
            top.forEach { RankBarRow(it.label, "${it.count} msgs", it.count.toFloat() / maxCount, Color(0xFF8B5CF6)) }
        }
    }
}

/** Sender name of a GRP_TXT packet from its decrypted "Sender: text" body, or "Anonymous". */
private fun channelSender(p: ParsedPacket, channels: List<ChannelEntry>): String {
    val hash = p.channelHash ?: return "Anonymous"
    if (p.payload.size < 2) return "Anonymous"
    val body = p.payload.copyOfRange(1, p.payload.size) // after the channel-hash byte
    for (ch in channels.filter { it.secret.isNotEmpty() && GroupCipher.channelHash(it.secret) == hash }) {
        val plain = GroupCipher.decrypt(ch.secret, body) ?: continue
        val text = GroupCipher.parseGroupText(plain)?.text ?: continue
        val name = text.substringBefore(": ", "").trim()
        return name.ifEmpty { "Anonymous" }
    }
    return "Anonymous" // not our channel (no key) or MAC/parse failed
}

/** A single ranked bar row: name, proportional bar, and a value label. */
@Composable
private fun RankBarRow(label: String, valueText: String, frac: Float, barColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
        Box(
            Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                .background(barColor))
        }
        Text(valueText, style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp))
    }
}

private data class PairAgg(val a: ResolvedNode, val b: ResolvedNode, val count: Int)

/** Repeater pairs that co-occur most often in the same packet path (a co-appearance table). */
@Composable
internal fun RepeaterPairHeatmapCard(
    session: MeshSession,
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val contacts by session.allContacts.collectAsStateWithLifecycle()
    val self by session.self.collectAsStateWithLifecycle()
    val heard by session.heard.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ResolvedNode?>(null) } // tapped node
    val pairs = remember(history, contacts) {
        val resolver = NodeResolver(history, contacts)
        val counts = HashMap<Pair<String, String>, Int>()
        val info = HashMap<String, ResolvedNode>()
        for (pkt in history) {
            val p = PacketInspector.parse(pkt.raw)
            if (p.payloadType == PayloadType.TRACE) continue
            // Resolve each hop within this packet's region, then count every distinct co-occurring pair.
            val nodes = p.pathHashes.map { resolver.resolve(pkt.region, it) }.distinctBy { it.id }
            nodes.forEach { info.putIfAbsent(it.id, it) }
            for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                val a = nodes[i].id; val b = nodes[j].id
                val key = if (a <= b) a to b else b to a
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        counts.entries.sortedByDescending { it.value }.take(15)
            .map { PairAgg(info.getValue(it.key.first), info.getValue(it.key.second), it.value) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Repeater Pair Heatmap", style = MaterialTheme.typography.titleMedium)
            Text("Which repeaters frequently appear together in paths",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (pairs.isEmpty()) {
                Text("No relayed packets yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Node A", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text("Node B", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text("Count", Modifier.width(56.dp), textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            pairs.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.a.label, Modifier.weight(1f).clickable { selected = p.a }.padding(end = 4.dp),
                        color = Color(0xFF60A5FA), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.b.label, Modifier.weight(1f).clickable { selected = p.b }.padding(end = 4.dp),
                        color = Color(0xFF60A5FA), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.count}", Modifier.width(56.dp), textAlign = TextAlign.End,
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    selected?.let { n ->
        val c = n.contact
        NodeDetailSheet(
            name = n.label,
            type = c?.type ?: ContactType.REPEATER,
            isSelf = false,
            contact = c,
            heard = c?.let { ct -> heard.firstOrNull { it.pubKeyHex.startsWith(ct.keyPrefixHex) } },
            self = self,
            onDismiss = { selected = null },
            onShowOnMap = { lat, lon -> selected = null; onShowOnMap(lat, lon) },
        )
    }
}

private val HASH_SIZE_COLORS = mapOf(1 to Color(0xFFEF4444), 2 to Color(0xFF4ADE80), 3 to Color(0xFF60A5FA), 4 to Color(0xFFA78BFA))

/** Full hop-hash hex list + hash size for a packet's path, or null (no path / TRACE). */
private fun pathHops(raw: ByteArray): Pair<Int, List<String>>? {
    if (raw.isEmpty()) return null
    val header = raw[0].toInt() and 0xFF
    val route = header and 0x03
    if (((header ushr 2) and 0x0F) == PayloadType.TRACE) return null // path field holds SNRs, not hops
    var i = 1
    if ((route == RouteType.TRANSPORT_FLOOD || route == RouteType.TRANSPORT_DIRECT) && raw.size >= i + 4) i += 4
    if (raw.size <= i) return null
    val pl = raw[i].toInt() and 0xFF; i++
    val count = pl and 63
    val size = (pl ushr 6) + 1
    if (count == 0 || raw.size < i + count * size) return null
    return size to (0 until count).map { raw.copyOfRange(i + it * size, i + it * size + size).toHex() }
}

private class HashSizeStats(
    val packetsWithHops: Int, val bySizePackets: Map<Int, Int>,
    val totalUnique: Int, val uniqueBySize: Map<Int, Int>,
)

private fun hashSizeStats(history: List<RxLog>): HashSizeStats {
    var packetsWithHops = 0
    val bySize = HashMap<Int, Int>()
    val uniq = HashMap<Int, HashSet<String>>()
    for (pkt in history) {
        val (size, hops) = pathHops(pkt.raw) ?: continue
        packetsWithHops++
        bySize[size] = (bySize[size] ?: 0) + 1
        // Region-scope uniqueness: the same hop hash in two regions is two distinct repeaters.
        val region = NodeResolver.regionOf(pkt.region)
        uniq.getOrPut(size) { HashSet() }.addAll(hops.map { "$region:$it" })
    }
    return HashSizeStats(packetsWithHops, bySize, uniq.values.sumOf { it.size }, uniq.mapValues { it.value.size })
}

/** Distribution of path-hash sizes (1/2/3-byte routing IDs) over packets, and by unique repeater. */
@Composable
internal fun HashSizeCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val s = remember(history) { hashSizeStats(history) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hash Size Distribution", style = MaterialTheme.typography.titleMedium)
            Text("%,d packets with path hops".format(s.packetsWithHops),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (s.packetsWithHops == 0) {
                Text("No path hops yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            val sizes = (s.bySizePackets.keys + s.uniqueBySize.keys).sorted()
            val maxP = s.bySizePackets.values.maxOrNull() ?: 1
            sizes.forEach { sz ->
                HashSizeRow("$sz-byte", hashSizeDetail(sz), s.bySizePackets[sz] ?: 0, s.packetsWithHops, maxP,
                    HASH_SIZE_COLORS[sz] ?: Color(0xFF94A3B8))
            }

            Text("By Repeaters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            Text("%,d unique repeaters".format(s.totalUnique),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            val maxR = s.uniqueBySize.values.maxOrNull() ?: 1
            sizes.forEach { sz ->
                HashSizeRow("$sz-byte", null, s.uniqueBySize[sz] ?: 0, s.totalUnique, maxR,
                    (HASH_SIZE_COLORS[sz] ?: Color(0xFF94A3B8)).copy(alpha = 0.6f))
            }
        }
    }
}

private fun hashSizeDetail(size: Int): String = "(${size * 8}-bit, %,d IDs)".format(1L shl (size * 8))

@Composable
private fun HashSizeRow(title: String, detail: String?, count: Int, total: Int, max: Int, color: Color) {
    val frac = if (max > 0) count.toFloat() / max else 0f
    val pct = if (total > 0) count * 100.0 / total else 0.0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                detail?.let {
                    Text(" $it", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            Text("%,d (%.1f%%)".format(count, pct), style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace)
        }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/**
 * A signal-quality histogram card: binned distribution over the persisted RX history, with the
 * min/mean/median/max and standard deviation summarised below the bars. [fmt] renders the summary
 * values (unit-aware); [sdUnit] is the unit appended to the standard deviation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistributionCard(
    title: String,
    subtitle: String,
    stats: DistStats?,
    barColor: androidx.compose.ui.graphics.Color,
    sdUnit: String,
    fmt: (Double) -> String,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (stats == null) {
                Text("No samples yet.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                return@Column
            }
            Text("peak ${stats.counts.max()} / bin · ${stats.count} samples",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            DistributionHistogram(stats.counts, barColor, Modifier.fillMaxWidth().height(140.dp))
            // Axis reference: low, midpoint, high edge of the binned range.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(stats.min, (stats.min + stats.max) / 2, stats.max).forEach {
                    Text("%.1f".format(it), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatInline("Min", fmt(stats.min))
                StatInline("Mean", fmt(stats.mean))
                StatInline("Median", fmt(stats.median))
                StatInline("Max", fmt(stats.max))
                StatInline("σ", "%.1f %s".format(stats.sd, sdUnit))
            }
        }
    }
}

/** Bar chart of per-bin counts (faint quartile grid lines). */
@Composable
private fun DistributionHistogram(
    counts: IntArray,
    barColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val maxC = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(modifier) {
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { f ->
            val gy = size.height * (1f - f)
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        val slot = size.width / counts.size
        counts.forEachIndexed { i, c ->
            val bh = size.height * (c.toFloat() / maxC)
            drawRect(barColor, topLeft = Offset(i * slot + slot * 0.1f, size.height - bh),
                size = Size(slot * 0.8f, bh))
        }
    }
}

/** An inline "Label: value" pair for a distribution summary row. */
@Composable
private fun StatInline(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private class DistStats(
    val count: Int,
    val min: Double, val max: Double, val mean: Double, val median: Double, val sd: Double,
    val counts: IntArray,
)

/** Compute a summary + a fixed-bin histogram over [values]; null when there are no samples. */
private fun distStats(values: List<Double>, bins: Int = 21): DistStats? {
    if (values.isEmpty()) return null
    val min = values.min(); val max = values.max()
    val mean = values.average()
    val sorted = values.sorted()
    val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    val counts = IntArray(bins)
    val span = max - min
    for (x in values) {
        val idx = if (span <= 0.0) 0 else (((x - min) / span) * (bins - 1)).roundToInt().coerceIn(0, bins - 1)
        counts[idx]++
    }
    return DistStats(values.size, min, max, mean, median, sd, counts)
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
