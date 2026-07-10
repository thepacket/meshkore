package org.thepacket.meshcore.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.PacketInspector
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.SelfInfo
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

enum class TopoKind { Self, Repeater, Room, Contact, Unknown }

class TopoNode(val id: String, val label: String, val kind: TopoKind) {
    var depth: Int = 0
    var x: Float = 0f
    var y: Float = 0f
}

data class TopoEdge(val a: String, val b: String)

class Topology(
    val nodes: List<TopoNode>,
    val edges: List<TopoEdge>,
    /** Direct (0-hop) chat/sensor contacts, not drawn — shown as a count. */
    val directCount: Int,
    val maxDepth: Int,
)

/**
 * Build a us-centred routing graph. Nodes are 1-byte routing identities (a node's public-key
 * first byte / path hash), the granularity MeshCore itself routes on. Edges come from two sources:
 *  - each contact's learned outbound path (`outPath`): this node → hops → contact;
 *  - observed packet paths ([packets]): source → relays → this node.
 * Byte labels resolve from a matching contact (preferring a named repeater) or a name seen in an
 * advert, else "0xNN". Directly-reachable chat/sensor contacts are collapsed into a count.
 */
fun buildTopology(
    self: SelfInfo?,
    contacts: List<Contact>,
    nameBook: List<Contact>,
    packets: List<RxLog>,
    edgeLimit: Int = 500,
): Topology {
    val selfId = "self"
    val selfByte = self?.publicKey?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF }

    // Harvest advert names from the WHOLE (persisted) history so labels resolve immediately —
    // including nodes named in past sessions; build edges only from the recent slice.
    val advNames = HashMap<Int, String>()
    val chains = ArrayList<List<Int>>()
    packets.forEachIndexed { idx, pkt ->
        val p = PacketInspector.parse(pkt.raw)
        p.advertName?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
            val b = p.advertPubKey?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF }
            if (b != null) advNames.putIfAbsent(b, name)
        }
        if (idx < edgeLimit && p.pathHashes.isNotEmpty()) {
            val src = p.advertPubKey?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF } ?: p.srcHash
            chains.add(buildList { src?.let { add(it) }; addAll(p.pathHashes) })
        }
    }

    fun byteId(b: Int) = "b:%02x".format(b)
    // Label + kind for a byte: the aggregate address book (name/type), then an advert name, else hex.
    fun labelKind(b: Int): Pair<String, TopoKind> {
        val cs = nameBook.filter { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == b }
        val kind = when {
            cs.any { it.type == ContactType.REPEATER } -> TopoKind.Repeater
            cs.any { it.type == ContactType.ROOM } -> TopoKind.Room
            cs.isNotEmpty() -> TopoKind.Contact
            else -> TopoKind.Unknown
        }
        val named = cs.mapNotNull { it.name.ifBlank { null } }
        val label = when {
            named.isNotEmpty() -> if (named.size == 1) named[0] else "${named[0]} +${named.size - 1}"
            advNames[b] != null -> advNames.getValue(b)
            else -> "0x%02X".format(b)
        }
        return label to kind
    }

    val nodes = LinkedHashMap<String, TopoNode>()
    nodes[selfId] = TopoNode(selfId, self?.name?.ifBlank { "This node" } ?: "This node", TopoKind.Self)
    val edges = LinkedHashSet<TopoEdge>()
    fun node(b: Int): String {
        if (selfByte != null && b == selfByte) return selfId
        val id = byteId(b)
        nodes.getOrPut(id) { val (l, k) = labelKind(b); TopoNode(id, l, k) }
        return id
    }
    fun edge(x: String, y: String) { if (x != y) edges.add(TopoEdge(x, y)) }

    var directCount = 0
    for (c in contacts) {
        val cByte = c.publicKey.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF } ?: continue
        when (val len = c.outPathLen) {
            0 -> if (c.type == ContactType.REPEATER || c.type == ContactType.ROOM) edge(selfId, node(cByte))
            else directCount++
            in 1..63 -> {
                var prev = selfId
                for (i in 0 until len) { val h = node(c.outPath[i].toInt() and 0xFF); edge(prev, h); prev = h }
                edge(prev, node(cByte))
            }
        }
    }
    // Fold in observed packet relay chains (source → relays → us).
    chains.forEach { chain ->
        var prev: String? = null
        chain.forEach { b -> val n = node(b); prev?.let { edge(it, n) }; prev = n }
        prev?.let { edge(it, selfId) }
    }

    // Undirected BFS hop-depth from self.
    val adj = HashMap<String, MutableSet<String>>()
    edges.forEach { adj.getOrPut(it.a) { mutableSetOf() }.add(it.b); adj.getOrPut(it.b) { mutableSetOf() }.add(it.a) }
    val seen = hashSetOf(selfId)
    val queue = ArrayDeque<String>().apply { add(selfId) }
    var maxDepth = 0
    while (queue.isNotEmpty()) {
        val n = queue.removeFirst()
        val d = nodes[n]!!.depth
        adj[n]?.forEach { m -> if (seen.add(m)) { nodes[m]?.let { it.depth = d + 1; maxDepth = maxOf(maxDepth, d + 1) }; queue.add(m) } }
    }
    nodes.values.forEach { if (it.id !in seen && it.id != selfId) it.depth = 1 }

    return Topology(nodes.values.toList(), edges.toList(), directCount, maxDepth)
}

@Composable
fun MeshTopologyScreen(
    session: MeshSession,
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val contacts by session.contacts.collectAsStateWithLifecycle()
    val allContacts by session.allContacts.collectAsStateWithLifecycle()
    val self by session.self.collectAsStateWithLifecycle()
    val packets by session.packetHistory.collectAsStateWithLifecycle()

    // Contact.equals ignores outPath, so key the rebuild on an explicit signature that reflects
    // learned paths + packet growth (bucketed, so we don't rebuild on literally every packet).
    val sig = run {
        var h = self?.publicKey?.firstOrNull()?.toInt() ?: 0
        for (c in contacts) { h = h * 31 + c.keyPrefixHex.hashCode(); h = h * 31 + c.outPathLen }
        (h * 31 + allContacts.size) * 31 + packets.size / 15
    }
    // Names resolve from the aggregate book + the whole persisted history; edges from the recent slice.
    val topo = remember(sig) { buildTopology(self, contacts, allContacts, packets) }

    val cs = MaterialTheme.colorScheme
    fun colorFor(kind: TopoKind): Color = when (kind) {
        TopoKind.Self -> cs.primary
        TopoKind.Repeater -> cs.tertiary
        TopoKind.Room -> cs.secondary
        TopoKind.Contact -> cs.onSurface.copy(alpha = 0.75f)
        TopoKind.Unknown -> cs.onSurface.copy(alpha = 0.35f)
    }
    val labelArgb = cs.onSurface.toArgb()
    val edgeColor = cs.onSurface.copy(alpha = 0.25f)

    // Pan/zoom transform applied to the whole graph via a graphics layer.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Tapped node: when set, only it and its directly-connected neighbours are drawn.
    var selected by remember { mutableStateOf<String?>(null) }
    val selNode = topo.nodes.firstOrNull { it.id == selected }
    val ctx = LocalContext.current

    // Long-press opens the contact card (detail sheet) — needs the node's underlying contact.
    val contactTelemetry by session.contactTelemetry.collectAsStateWithLifecycle()
    val contactMma by session.contactMma.collectAsStateWithLifecycle()
    val pathDiscovery by session.pathDiscovery.collectAsStateWithLifecycle()
    val advertPaths by session.advertPaths.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Contact?>(null) }
    var exportedCard by remember { mutableStateOf<String?>(null) }
    var showMap by remember { mutableStateOf(false) }
    LaunchedEffect(session) { session.exportedContact.collect { exportedCard = it } }

    // Resolve a graph node (1-byte identity) to the best matching contact (named repeater first).
    fun contactForNode(node: TopoNode): Contact? {
        if (!node.id.startsWith("b:")) return null
        val byte = node.id.substring(2).toIntOrNull(16) ?: return null
        val pool = contacts + allContacts.filterNot { a -> contacts.any { it.publicKey.contentEquals(a.publicKey) } }
        return pool.filter { it.publicKey.isNotEmpty() && (it.publicKey[0].toInt() and 0xFF) == byte }
            .sortedWith(compareByDescending<Contact> { it.name.isNotBlank() }
                .thenByDescending { it.type == ContactType.REPEATER })
            .firstOrNull()
    }
    val exportSvg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        uri?.let {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(topologyToSvg(topo).toByteArray()) }
            }.isSuccess
            Toast.makeText(ctx, if (ok) "Topology exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    if (showMap) {
        // Map the currently-displayed subset: the whole graph, or — when a node is focused — just
        // that node and its neighbours (those with a known position).
        val focusSet = selected?.let { s ->
            buildSet { add(s); topo.edges.forEach { if (it.a == s) add(it.b); if (it.b == s) add(it.a) } }
        }
        val visible = if (focusSet != null) topo.nodes.filter { it.id in focusSet } else topo.nodes
        val topoContacts = visible.mapNotNull { contactForNode(it) }.distinctBy { it.keyPrefixHex }
        val placed = topoContacts.count { it.gpsLat != 0 || it.gpsLon != 0 }
        Box(Modifier.fillMaxSize()) {
            MapContent(self = self, contacts = topoContacts, heard = emptyList(), modifier = Modifier.fillMaxSize())
            Surface(
                color = cs.surface.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showMap = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to graph")
                    }
                    Text("Topology map · $placed of ${topoContacts.size} placed",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Mesh topology", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (topo.edges.isNotEmpty()) {
                IconButton(onClick = { showMap = true }) {
                    Icon(Icons.Default.Map, contentDescription = "Show on map")
                }
                IconButton(onClick = { exportSvg.launch("mesh-topology.svg") }) {
                    Icon(Icons.Default.Download, contentDescription = "Export SVG")
                }
                IconButton(onClick = { scale = 1f; offset = Offset.Zero; selected = null }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Reset view")
                }
            }
        }
        Text(
            "Routing graph centred on this node, from learned contact paths + observed packet " +
                "relays (updates as packets arrive). ${topo.nodes.size} nodes · ${topo.maxDepth} hops · " +
                "${topo.directCount} direct contacts collapsed. Pinch to zoom, drag to pan.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurface.copy(alpha = 0.6f),
        )

        if (topo.edges.isEmpty()) {
            Text(
                "No relayed routes to draw yet — waiting for multi-hop packets or learned paths. " +
                    "Direct contacts (${topo.directCount}) don't add structure.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }

        selNode?.let { n ->
            val conns = topo.edges.count { it.a == n.id || it.b == n.id }
            Text("Focused: ${n.label} · $conns connection(s). Tap it again or empty space to clear.",
                style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }

        // Neighbours of the focused node (itself + everything one edge away).
        val focusSet: Set<String>? = selected?.let { s ->
            buildSet { add(s); topo.edges.forEach { if (it.a == s) add(it.b); if (it.b == s) add(it.a) } }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.15f, 40f)
                        offset += pan
                    }
                }
                .pointerInput(topo) {
                    // Undo the graphics-layer transform (scale around centre, then translate) to
                    // hit-test a tap in the graph's own coordinate space.
                    fun nodeAt(tap: Offset): TopoNode? {
                        val pivotX = size.width / 2f; val pivotY = size.height / 2f
                        val gx = pivotX + (tap.x - offset.x - pivotX) / scale
                        val gy = pivotY + (tap.y - offset.y - pivotY) / scale
                        val hit = topo.nodes.minByOrNull { hypot(it.x - gx, it.y - gy) } ?: return null
                        return hit.takeIf { hypot(it.x - gx, it.y - gy) < 44f }
                    }
                    detectTapGestures(
                        onTap = { tap -> val n = nodeAt(tap); selected = if (n != null && n.id != selected) n.id else null },
                        onLongPress = { tap -> nodeAt(tap)?.let { contactForNode(it)?.let { c -> detail = c } } },
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val margin = 44f
                val ringGap = (min(cx, cy) - margin) / topo.maxDepth.coerceAtLeast(1)
                // Base ("world") layout positions — independent of zoom.
                topo.nodes.groupBy { it.depth }.forEach { (depth, group) ->
                    if (depth == 0) group.forEach { it.x = cx; it.y = cy }
                    else {
                        val r = ringGap * depth
                        group.forEachIndexed { i, n ->
                            val a = 2.0 * Math.PI * i / group.size + depth * 0.6
                            n.x = cx + (r * cos(a)).toFloat(); n.y = cy + (r * sin(a)).toFloat()
                        }
                    }
                }
                // Zoom scales the layout (spreads nodes apart, like a map); dots/labels/lines keep a
                // constant pixel size. screen = centre + (world - centre)*scale + pan.
                fun screen(wx: Float, wy: Float) =
                    Offset(cx + (wx - cx) * scale + offset.x, cy + (wy - cy) * scale + offset.y)
                val pos = topo.nodes.associate { it.id to screen(it.x, it.y) }
                topo.edges.forEach { e ->
                    // When a node is focused, only draw edges touching it.
                    if (focusSet != null && e.a != selected && e.b != selected) return@forEach
                    val a = pos[e.a]; val b = pos[e.b]
                    if (a != null && b != null) drawLine(edgeColor, a, b, strokeWidth = 2f)
                }
                val paint = android.graphics.Paint().apply {
                    color = labelArgb; textSize = 26f; isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                topo.nodes.forEach { n ->
                    if (focusSet != null && n.id !in focusSet) return@forEach // hide non-neighbours
                    val p = pos[n.id] ?: return@forEach
                    val focused = n.id == selected
                    val baseR = if (n.kind == TopoKind.Self) 12f else 7f
                    drawCircle(colorFor(n.kind), radius = if (focused) baseR + 4f else baseR, center = p)
                    // Full label at constant size — zoom spreads names apart rather than magnifying them.
                    drawContext.canvas.nativeCanvas.drawText(n.label, p.x, p.y - 12f, paint)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot("This node", colorFor(TopoKind.Self))
            LegendDot("Repeater", colorFor(TopoKind.Repeater))
            LegendDot("Room", colorFor(TopoKind.Room))
            LegendDot("Contact", colorFor(TopoKind.Contact))
            LegendDot("Relay?", colorFor(TopoKind.Unknown))
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
            onRequestMma = { session.requestContactMma(c) },
            mma = contactMma[c.keyPrefixHex],
            onDiscoverPath = { session.discoverPath(c.publicKey) },
            pathResult = pathDiscovery[c.keyPrefixHex],
            onRequestAdvertPath = { session.requestAdvertPath(c) },
            advertPath = advertPaths[c.keyPrefixHex],
            advertPathLoaded = advertPaths.containsKey(c.keyPrefixHex),
            onShowOnMap = { lat, lon -> detail = null; onShowOnMap(lat, lon) },
        )
    }
    exportedCard?.let { card -> ExportCardDialog(card) { exportedCard = null } }
}

/** Theme-independent colours for the exported SVG (reads on a white background anywhere). */
private fun svgColor(kind: TopoKind): String = when (kind) {
    TopoKind.Self -> "#2563eb"
    TopoKind.Repeater -> "#0d9488"
    TopoKind.Room -> "#7c3aed"
    TopoKind.Contact -> "#475569"
    TopoKind.Unknown -> "#94a3b8"
}

private fun xmlEscape(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

/** Render the topology to a standalone SVG using the same radial layout as the on-screen graph. */
fun topologyToSvg(topo: Topology): String {
    val w = 1400.0; val h = 1400.0; val cx = w / 2; val cy = h / 2; val margin = 130.0
    val ringGap = (min(cx, cy) - margin) / topo.maxDepth.coerceAtLeast(1)
    val pos = HashMap<String, Pair<Double, Double>>()
    topo.nodes.groupBy { it.depth }.forEach { (depth, group) ->
        if (depth == 0) group.forEach { pos[it.id] = cx to cy }
        else {
            val r = ringGap * depth
            group.forEachIndexed { i, n ->
                val a = 2.0 * Math.PI * i / group.size + depth * 0.6
                pos[n.id] = (cx + r * cos(a)) to (cy + r * sin(a))
            }
        }
    }
    val sb = StringBuilder()
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${w.toInt()} ${h.toInt()}\" ")
        .append("font-family=\"sans-serif\">\n")
    sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n")
    topo.edges.forEach { e ->
        val a = pos[e.a]; val b = pos[e.b]
        if (a != null && b != null) sb.append(
            "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#cbd5e1\" stroke-width=\"1.5\"/>\n"
                .format(a.first, a.second, b.first, b.second))
    }
    topo.nodes.forEach { n ->
        val (x, y) = pos[n.id] ?: return@forEach
        val r = if (n.kind == TopoKind.Self) 16 else 9
        sb.append("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"$r\" fill=\"${svgColor(n.kind)}\"/>\n".format(x, y))
        sb.append("<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" font-size=\"22\" fill=\"#0f172a\">%s</text>\n"
            .format(x, y - 18, xmlEscape(n.label)))
    }
    sb.append("</svg>\n")
    return sb.toString()
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
