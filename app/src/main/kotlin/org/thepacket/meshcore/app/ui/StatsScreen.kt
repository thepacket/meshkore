package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.protocol.Contact
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
        SnrDistributionCard(session)
        RssiDistributionCard(session)
        HopCountDistributionCard(session)
        TopRepeatersCard(session)
        radio?.let { RadioCard(it) }
        core?.let { CoreCard(it) }
        TelemetryCard(telemetry, onRefreshTelemetry)
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
    // Resolve source names from the persisted aggregate book — it's populated even before a
    // fresh contact sync completes, unlike the live device-contacts list.
    val contacts by session.allContacts.collectAsStateWithLifecycle()
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

            Text("Over time", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            TrafficSparkline(pkts, now, spanMs)

            Text("By type", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            pkts.groupingBy { it.payloadType }.eachCount().entries
                .sortedByDescending { it.value }
                .forEach { (type, c) -> BarRow(PayloadType.name(type), c, count) }

            // Top talkers: rank source identities by packet count (+ airtime) over the window.
            // Source = advert pubkey (adverts) or the 1-byte src hash (other packets); nodes that
            // share a hash are grouped and labelled by their contact name(s).
            val talkers = remember(pkts, contacts, self) {
                val agg = HashMap<Int, DoubleArray>() // hash -> [count, airtimeMs]
                val advNames = HashMap<Int, String>() // best name seen in an advert for this hash
                pkts.forEach { pkt ->
                    val p = PacketInspector.parse(pkt.raw)
                    val h = sourceByte(p) ?: return@forEach
                    val a = agg.getOrPut(h) { DoubleArray(2) }
                    a[0] += 1
                    self?.let { s -> a[1] += LoRaAirtime.airtimeMs(pkt.length, s.bwKhz, s.radioSf, s.radioCr) ?: 0.0 }
                    // Adverts carry the node's own name — the most reliable label for that source.
                    p.advertName?.trim()?.takeIf { it.isNotBlank() }?.let { advNames.putIfAbsent(h, it) }
                }
                agg.entries.sortedByDescending { it.value[0] }.take(8)
                    .map { e -> TalkerAgg(advNames[e.key] ?: talkerLabel(e.key, contacts), e.value[0].toInt(), e.value[1]) }
            }
            if (talkers.isNotEmpty()) {
                Text("Top talkers", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                val maxCount = talkers.first().count
                talkers.forEach { t -> TalkerRow(t.label, t.count, maxCount, t.airtimeMs) }
            }
        }
    }
}

/**
 * Packets-over-time bar chart: bins the window into fixed slots and draws per-slot packet counts,
 * so bursts and quiet periods are visible. [spanMs] is the time from the oldest packet to [nowMs].
 */
@Composable
private fun TrafficSparkline(pkts: List<RxLog>, nowMs: Long, spanMs: Long) {
    val bars = 40
    val counts = remember(pkts, nowMs) {
        val c = IntArray(bars)
        val oldest = nowMs - spanMs
        pkts.forEach {
            val f = ((it.receivedAtMs - oldest).toDouble() / spanMs).coerceIn(0.0, 1.0)
            c[(f * (bars - 1)).toInt()]++
        }
        c
    }
    val maxC = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val slot = size.width / bars
        counts.forEachIndexed { i, c ->
            val bh = size.height * (c.toFloat() / maxC)
            drawRect(barColor, topLeft = Offset(i * slot, size.height - bh), size = Size(slot * 0.75f, bh))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${fmtDuration(spanMs / 1000)} ago", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text("peak $maxC/slot · now", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

private data class TalkerAgg(val label: String, val count: Int, val airtimeMs: Double)

/** The 1-byte source identity of a packet: advert pubkey's first byte, else the src hash; null if none. */
private fun sourceByte(p: ParsedPacket): Int? =
    p.advertPubKey?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF } ?: p.srcHash

/** Resolve a 1-byte source hash to contact name(s), or "0xNN" when unknown. */
private fun talkerLabel(hash: Int, contacts: List<Contact>): String {
    val names = contacts
        .filter { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == hash }
        .map { it.name.ifBlank { it.keyPrefixHex } }
    return when {
        names.isEmpty() -> "0x%02X".format(hash)
        names.size == 1 -> names[0]
        else -> "${names[0]} +${names.size - 1}"
    }
}

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

/** SNR histogram over the RX history (higher = cleaner signal), green bars. */
@Composable
private fun SnrDistributionCard(session: MeshSession) {
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
private fun RssiDistributionCard(session: MeshSession) {
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

/** Histogram of relay-hop counts per packet, with avg/median/max and a direct-packet count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HopCountDistributionCard(session: MeshSession) {
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

/**
 * Top nodes by how often they appear as a relay in packet paths (i.e. the busiest repeaters).
 * Path hops are 1-byte routing identities, resolved to a name via an advert or the aggregate book.
 */
@Composable
private fun TopRepeatersCard(session: MeshSession) {
    val history by session.packetHistory.collectAsStateWithLifecycle()
    val contacts by session.allContacts.collectAsStateWithLifecycle()
    val top = remember(history, contacts) {
        val counts = HashMap<Int, Int>()
        val advNames = HashMap<Int, String>() // best advert name seen for each routing byte
        for (pkt in history) {
            val p = PacketInspector.parse(pkt.raw)
            p.advertName?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
                p.advertPubKey?.takeIf { it.isNotEmpty() }?.let { advNames.putIfAbsent(it[0].toInt() and 0xFF, name) }
            }
            if (p.payloadType == PayloadType.TRACE) continue // path field holds SNRs, not hop hashes
            p.pathHashes.forEach { h -> counts[h] = (counts[h] ?: 0) + 1 }
        }
        counts.entries.sortedByDescending { it.value }.take(16)
            .map { RepeaterAgg(advNames[it.key] ?: talkerLabel(it.key, contacts), it.value) }
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
            top.forEach { RepeaterRow(it.label, it.count, maxCount) }
        }
    }
}

/** A single ranked repeater: name, proportional bar, and path-appearance count. */
@Composable
private fun RepeaterRow(label: String, count: Int, maxCount: Int) {
    val frac = if (maxCount > 0) count.toFloat() / maxCount else 0f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
        Box(
            Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF60A5FA)))
        }
        Text("%,d".format(count), style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp))
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
