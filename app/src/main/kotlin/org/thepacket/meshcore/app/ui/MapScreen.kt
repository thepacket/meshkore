package org.thepacket.meshcore.app.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.thepacket.meshcore.app.HeardEntry
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.SelfInfo
import kotlin.math.hypot

/**
 * A node to plot. Positions come only from what nodes already advertise (read-only).
 * Carries the source records so a tapped marker can show everything we know.
 */
data class MapNode(
    val name: String,
    val lat: Double,
    val lon: Double,
    val type: Int,
    val isSelf: Boolean,
    val contact: Contact? = null,
    val heard: HeardEntry? = null,
    val self: SelfInfo? = null,
)

@Composable
fun MapContent(
    self: SelfInfo?,
    contacts: List<Contact>,
    heard: List<HeardEntry>,
    modifier: Modifier = Modifier,
    // Trace mode: the same map, but tapping a node with a contact appends it to the
    // ordered path ([tracePath], duplicates allowed); long-press shows its details.
    traceMode: Boolean = false,
    tracePath: List<Contact> = emptyList(),
    onAddTrace: (Contact) -> Unit = {},
) {
    val nodes = remember(self, contacts, heard) { collectNodes(self, contacts, heard) }
    // Caches so re-clustering on pan/zoom reuses marker bitmaps.
    val nodeIcons = remember { mutableMapOf<String, NodeIcon>() }
    val clusterIcons = remember { mutableMapOf<Int, Drawable>() }
    val badgeIcons = remember { mutableMapOf<String, Drawable>() }
    val holder = remember { MapHolder() }
    holder.nodes = nodes
    holder.traceMode = traceMode
    holder.traceCounts = tracePath.groupingBy { it.keyPrefixHex }.eachCount()
    holder.onAddTrace = onAddTrace
    var selected by remember { mutableStateOf<MapNode?>(null) }
    holder.onSelect = { selected = it }
    holder.onInfo = { selected = it }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName // required by OSM tile policy
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(3.0)
                    // Long-press anywhere → show the nearest node's details (trace mode).
                    holder.events = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (!holder.traceMode || p == null) return false
                            val n = nearestNode(this@apply, holder.nodes, p) ?: return false
                            holder.onInfo(n); return true
                        }
                    })
                    // Re-cluster as the viewport changes (clusters depend on screen positions).
                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            rebuildOverlays(this@apply, holder, nodeIcons, clusterIcons, badgeIcons); return false
                        }
                        override fun onZoom(event: ZoomEvent?): Boolean {
                            rebuildOverlays(this@apply, holder, nodeIcons, clusterIcons, badgeIcons); return false
                        }
                    })
                }
            },
            update = { map ->
                rebuildOverlays(map, holder, nodeIcons, clusterIcons, badgeIcons)
                if (!holder.centered && nodes.isNotEmpty()) {
                    map.controller.setCenter(GeoPoint(nodes[0].lat, nodes[0].lon))
                    if (map.zoomLevelDouble < 5.0) map.controller.setZoom(11.0)
                    holder.centered = true
                }
                map.invalidate()
            },
            onRelease = { it.onDetach() },
        )

        if (nodes.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            ) {
                Text(
                    "No node positions yet — markers appear when a node advertises GPS.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }

        selected?.let { node ->
            NodeDetailSheet(
                name = node.name, type = node.type, isSelf = node.isSelf,
                contact = node.contact, heard = node.heard, self = self,
                onDismiss = { selected = null },
            )
        }
    }
}

private class MapHolder {
    var nodes: List<MapNode> = emptyList()
    var centered = false
    var onSelect: (MapNode) -> Unit = {}
    var onInfo: (MapNode) -> Unit = {}
    var traceMode = false
    var traceCounts: Map<String, Int> = emptyMap() // keyPrefixHex -> times in path
    var onAddTrace: (Contact) -> Unit = {}
    var events: MapEventsOverlay? = null
}

/** A small numbered/✓ badge drawn over a selected trace node (no label). */
private fun makeBadgeIcon(res: Resources, text: String, color: Int): Drawable {
    val d = res.displayMetrics.density
    val diameter = 22f * d
    val size = (diameter + 4f * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f; val cy = size / 2f; val r = diameter / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r, p)            // white ring
    p.color = color; c.drawCircle(cx, cy, r - 1.5f * d, p)             // coloured fill
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = diameter * 0.5f
    }
    c.drawText(text, cx, cy - (t.descent() + t.ascent()) / 2f, t)
    return BitmapDrawable(res, bmp)
}

/** Greedy screen-space clustering: nodes within [CLUSTER_RADIUS_DP] merge into a count pill. */
private fun rebuildOverlays(
    map: MapView,
    holder: MapHolder,
    nodeIcons: MutableMap<String, NodeIcon>,
    clusterIcons: MutableMap<Int, Drawable>,
    badgeIcons: MutableMap<String, Drawable>,
) {
    val nodes = holder.nodes
    map.overlays.clear()
    // Keep the long-press receiver at the bottom so markers get taps first.
    if (holder.traceMode) holder.events?.let { map.overlays.add(it) }
    if (nodes.isEmpty()) { map.invalidate(); return }

    val res = map.resources
    val radiusPx = CLUSTER_RADIUS_DP * res.displayMetrics.density
    val proj = map.projection
    val pts = nodes.map { proj.toPixels(GeoPoint(it.lat, it.lon), null) }
    val used = BooleanArray(nodes.size)

    for (i in nodes.indices) {
        if (used[i]) continue
        used[i] = true
        val group = mutableListOf(i)
        for (j in i + 1 until nodes.size) {
            if (!used[j] && dist(pts[i], pts[j]) < radiusPx) { used[j] = true; group.add(j) }
        }
        if (group.size == 1) {
            val n = nodes[i]
            val ni = nodeIcons.getOrPut(nodeKey(n)) { makeNodeIcon(res, n) }
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(n.lat, n.lon)
                setAnchor(Marker.ANCHOR_CENTER, ni.anchorV) // geo point sits at the circle centre
                setIcon(ni.drawable)
                setOnMarkerClickListener { _, _ ->
                    val c = n.contact
                    if (holder.traceMode && c != null) holder.onAddTrace(c) else holder.onSelect(n)
                    true
                }
            })
        } else {
            val lat = group.sumOf { nodes[it].lat } / group.size
            val lon = group.sumOf { nodes[it].lon } / group.size
            val icon = clusterIcons.getOrPut(group.size) { makeClusterIcon(res, group.size) }
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(lat, lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setIcon(icon)
                setOnMarkerClickListener { _, mv ->
                    mv.controller.animateTo(GeoPoint(lat, lon))
                    mv.controller.setZoom(mv.zoomLevelDouble + 2.0)
                    true
                }
            })
        }
    }

    // Trace overlay: mark each selected node with how many times it's in the path.
    if (holder.traceMode && holder.traceCounts.isNotEmpty()) {
        nodes.forEach { n ->
            val count = n.contact?.let { holder.traceCounts[it.keyPrefixHex] } ?: 0
            if (count > 0) {
                val icon = badgeIcons.getOrPut("$count") { makeBadgeIcon(res, "$count", 0xFF3FC7E8.toInt()) }
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(n.lat, n.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setIcon(icon)
                    setOnMarkerClickListener { _, _ -> n.contact?.let { holder.onAddTrace(it) }; true }
                })
            }
        }
    }
    map.invalidate()
}

private const val CLUSTER_RADIUS_DP = 26f

/** A node marker plus the vertical anchor that puts its circle (not its label) on the geo point. */
private class NodeIcon(val drawable: Drawable, val anchorV: Float)

private fun dist(a: Point, b: Point): Double =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

/** Nearest plotted node to a tapped geo point, within a finger-sized radius (or null). */
private fun nearestNode(map: MapView, nodes: List<MapNode>, p: GeoPoint): MapNode? {
    val proj = map.projection
    val tap = proj.toPixels(p, null)
    val thresh = 44f * map.resources.displayMetrics.density
    var best: MapNode? = null
    var bestD = Double.MAX_VALUE
    for (n in nodes) {
        val d = dist(tap, proj.toPixels(GeoPoint(n.lat, n.lon), null))
        if (d < thresh && d < bestD) { bestD = d; best = n }
    }
    return best
}

private fun nodeKey(n: MapNode): String = "${n.type}|${n.isSelf}|${n.name}"

private fun initialOf(name: String): Char =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'

// ---- node type colours (match the app's per-type palette) -----------------
private fun nodeColor(type: Int, isSelf: Boolean): Int = when {
    isSelf -> 0xFF3FC7E8.toInt()                 // app cyan = our node
    type == ContactType.REPEATER -> 0xFF3F5A78.toInt() // slate, like the official tower marker
    type == ContactType.CHAT -> 0xFF4ADE80.toInt()
    type == ContactType.ROOM -> 0xFFA78BFA.toInt()
    type == ContactType.SENSOR -> 0xFFF59E0B.toInt()
    else -> 0xFF64748B.toInt()
}

// ---- icon rendering -------------------------------------------------------

private fun makeNodeIcon(res: Resources, n: MapNode): NodeIcon {
    val d = res.displayMetrics.density
    val diameter = ((if (n.isSelf) 30f else 26f) * d)
    val topPad = 2f * d
    val gap = 3f * d

    // Label below the circle (always shown for un-clustered nodes).
    val label = n.name.let { if (it.length > 16) it.take(15) + "…" else it }
    val tLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 11f * d
    }
    val fm = tLabel.fontMetrics
    val labelTextH = fm.descent - fm.ascent
    val labelPadH = 5f * d; val labelPadV = 2.5f * d
    val labelW = tLabel.measureText(label) + labelPadH * 2
    val labelH = labelTextH + labelPadV * 2

    val width = maxOf(diameter + 4f * d, labelW).toInt()
    val height = (topPad + diameter + gap + labelH).toInt()
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = width / 2f
    val circleCy = topPad + diameter / 2f
    val r = diameter / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // white backing ring then coloured fill
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, circleCy, r, p)
    p.color = nodeColor(n.type, n.isSelf); c.drawCircle(cx, circleCy, r - 1.5f * d, p)
    if (n.isSelf) { // extra ring to stand out
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, circleCy, r - 3.5f * d, p); p.style = Paint.Style.FILL
    }
    if (n.type == ContactType.REPEATER) drawTower(c, cx, circleCy, diameter, d)
    else drawInitial(c, cx, circleCy, diameter, initialOf(n.name))

    // label pill
    val labelTop = circleCy + r + gap
    val rect = RectF(cx - labelW / 2f, labelTop, cx + labelW / 2f, labelTop + labelH)
    p.color = 0xE61E2933.toInt(); c.drawRoundRect(rect, 4f * d, 4f * d, p)
    c.drawText(label, cx, labelTop + labelPadV - fm.ascent, tLabel)

    return NodeIcon(BitmapDrawable(res, bmp), anchorV = circleCy / height)
}

/** Broadcast tower glyph (mast + signal arcs), white, vertically centred at [cy]. */
private fun drawTower(c: Canvas, cx: Float, cy: Float, diameter: Float, d: Float) {
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.8f * d
        strokeCap = Paint.Cap.ROUND
    }
    // glyph spans tip-arcs (top) to mast bottom; chosen so its midpoint == cy
    val tipY = cy - diameter * 0.06f
    val mastBottom = tipY + diameter * 0.40f
    c.drawLine(cx, tipY, cx, mastBottom, white)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    c.drawCircle(cx, tipY, 1.7f * d, fill)
    for (k in 1..2) {
        val rad = diameter * (0.10f + 0.09f * k)
        val box = RectF(cx - rad, tipY - rad, cx + rad, tipY + rad)
        c.drawArc(box, 120f, 110f, false, white)  // left
        c.drawArc(box, -50f, 110f, false, white)  // right
    }
}

private fun drawInitial(c: Canvas, cx: Float, cy: Float, diameter: Float, ch: Char) {
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = diameter * 0.52f
    }
    val baseline = cy - (t.descent() + t.ascent()) / 2f
    c.drawText(ch.toString(), cx, baseline, t)
}

private fun makeClusterIcon(res: Resources, count: Int): Drawable {
    val d = res.displayMetrics.density
    val diameter = (34f + (count.coerceAtMost(50) * 0.5f)) * d
    val size = (diameter + 4f * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f; val cy = size / 2f; val r = diameter / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x33FFFFFF; c.drawCircle(cx, cy, r, p)          // soft halo
    p.color = 0xFF43A047.toInt(); c.drawCircle(cx, cy, r - 3f * d, p) // green pill
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = diameter * 0.42f
    }
    val baseline = cy - (t.descent() + t.ascent()) / 2f
    c.drawText(count.toString(), cx, baseline, t)
    return BitmapDrawable(res, bmp)
}

private fun collectNodes(self: SelfInfo?, contacts: List<Contact>, heard: List<HeardEntry>): List<MapNode> {
    val byKey = LinkedHashMap<String, MapNode>()
    contacts.forEach { c ->
        if (c.gpsLat != 0 || c.gpsLon != 0) {
            val h = heard.firstOrNull { it.pubKeyHex.startsWith(c.keyPrefixHex) } // attach signal if heard
            byKey[c.keyPrefixHex] = MapNode(
                c.name.ifBlank { c.keyPrefixHex }, c.gpsLat / 1e6, c.gpsLon / 1e6, c.type, false,
                contact = c, heard = h,
            )
        }
    }
    heard.forEach { h ->
        val coveredByContact = contacts.any { h.pubKeyHex.startsWith(it.keyPrefixHex) && (it.gpsLat != 0 || it.gpsLon != 0) }
        if (h.hasGps && !coveredByContact && !byKey.containsKey(h.pubKeyHex)) {
            byKey[h.pubKeyHex] = MapNode(h.name, h.latDeg, h.lonDeg, h.type, false, heard = h)
        }
    }
    val list = byKey.values.toMutableList()
    // Show our own node only if it already advertises a position (read-only; we never set it).
    if (self != null && (self.advLat != 0 || self.advLon != 0)) {
        list.add(0, MapNode(self.name.ifBlank { "This node" }, self.advLat / 1e6, self.advLon / 1e6,
            ContactType.CHAT, true, self = self))
    }
    return list
}
