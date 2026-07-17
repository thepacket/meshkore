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
import android.view.MotionEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
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
    // When set, centre the map on these coordinates once, then call [onFocusConsumed].
    focus: Pair<Double, Double>? = null,
    onFocusConsumed: () -> Unit = {},
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
    holder.focus = focus
    var selected by remember { mutableStateOf<MapNode?>(null) }
    holder.onSelect = { selected = it }
    holder.onInfo = { selected = it }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName // required by OSM tile policy
                val map = MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(3.0)
                }
                // Clusters depend on screen positions, so they must be rebuilt as the viewport changes —
                // but osmdroid fires scroll/zoom events continuously during a gesture. Rebuilding on each
                // one re-clusters the whole book every frame, which is what makes zoom/pan janky. Coalesce
                // them: reschedule a single rebuild once motion settles. Markers still pan and zoom with
                // the map in the meantime (they're geo-anchored); only the cluster grouping is deferred.
                val rebuild = Runnable { rebuildOverlays(map, holder, nodeIcons, clusterIcons, badgeIcons) }
                holder.rebuild = rebuild
                // A single coalescing entry point for both gesture- and data-driven rebuilds: under the
                // all-regions feed the node list churns constantly, so debouncing here stops a rebuild (and
                // full redraw) firing on every recomposition, which was pinning the CPU.
                holder.requestRebuild = { map.removeCallbacks(rebuild); map.postDelayed(rebuild, REBUILD_DEBOUNCE_MS) }
                // Once the map is measured, do the first cluster pass (earlier rebuilds bail on width==0).
                map.addOnFirstLayoutListener { _, _, _, _, _ -> holder.requestRebuild() }
                map.addMapListener(object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        map.removeCallbacks(rebuild); map.postDelayed(rebuild, REBUILD_DEBOUNCE_MS); return false
                    }
                    override fun onZoom(event: ZoomEvent?): Boolean {
                        map.removeCallbacks(rebuild); map.postDelayed(rebuild, REBUILD_DEBOUNCE_MS); return false
                    }
                })
                map
            },
            update = { map ->
                // Only rebuild when the node set actually changed (remember() hands back the same list
                // instance otherwise), and do it through the debounce — recompositions from the packet
                // feed no longer force a synchronous re-cluster + redraw. renderedNodes is advanced inside
                // rebuildOverlays on success, so a rebuild that bails (map not yet laid out) is retried.
                if (nodes !== holder.renderedNodes) holder.requestRebuild()
                val f = holder.focus
                if (f != null && f != holder.appliedFocus) {
                    map.controller.setCenter(GeoPoint(f.first, f.second))
                    if (map.zoomLevelDouble < 13.0) map.controller.setZoom(15.0)
                    holder.appliedFocus = f
                    holder.centered = true
                    onFocusConsumed()
                } else if (!holder.centered && nodes.isNotEmpty()) {
                    map.controller.setCenter(GeoPoint(nodes[0].lat, nodes[0].lon))
                    if (map.zoomLevelDouble < 5.0) map.controller.setZoom(11.0)
                    holder.centered = true
                }
            },
            onRelease = { map ->
                holder.rebuild?.let { map.removeCallbacks(it) } // drop any pending rebuild before teardown
                map.onDetach()
            },
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
    // The node list last drawn, so recompositions that didn't change it skip the rebuild entirely.
    var renderedNodes: List<MapNode>? = null
    // Debounced rebuild trigger, wired up in the AndroidView factory (coalesces data + gesture updates).
    var requestRebuild: () -> Unit = {}
    // The pending rebuild Runnable, so onRelease can cancel it before the MapView is detached.
    var rebuild: Runnable? = null
    var centered = false
    var focus: Pair<Double, Double>? = null
    var appliedFocus: Pair<Double, Double>? = null
    var onSelect: (MapNode) -> Unit = {}
    var onInfo: (MapNode) -> Unit = {}
    var traceMode = false
    var traceCounts: Map<String, Int> = emptyMap() // keyPrefixHex -> times in path
    var onAddTrace: (Contact) -> Unit = {}
}

/** A marker that reports a long-press on itself (osmdroid's Marker otherwise swallows it). */
private class TapMarker(map: MapView) : Marker(map) {
    var onLong: (() -> Unit)? = null
    override fun onLongPress(event: MotionEvent, mapView: MapView): Boolean {
        val cb = onLong
        if (cb != null && hitTest(event, mapView)) { cb(); return true }
        return super.onLongPress(event, mapView)
    }
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
    // A debounced rebuild can fire after the map is torn down (e.g. switching tools) or before it's laid
    // out — operating on the projection/overlays then crashes. Bail while detached or unmeasured; a later
    // gesture/data change re-triggers once the view is live again.
    if (!map.isAttachedToWindow || map.width == 0 || map.height == 0) return
    val allNodes = holder.nodes
    holder.renderedNodes = allNodes // committed to rendering this set; a bailed rebuild (above) leaves it stale to retry
    map.overlays.clear()
    if (allNodes.isEmpty()) { map.invalidate(); return }

    val res = map.resources
    val radiusPx = CLUSTER_RADIUS_DP * res.displayMetrics.density
    val proj = map.projection
    // Only cluster and draw what's on screen (plus a margin). With a large address book most nodes are
    // off-screen once zoomed in, so this keeps the O(n²) clustering proportional to the visible set.
    val nodes = cullToViewport(allNodes, map.boundingBox)
    if (nodes.isEmpty()) { map.invalidate(); return }
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
            map.overlays.add(TapMarker(map).apply {
                position = GeoPoint(n.lat, n.lon)
                setAnchor(Marker.ANCHOR_CENTER, ni.anchorV) // geo point sits at the circle centre
                setIcon(ni.drawable)
                setOnMarkerClickListener { _, _ ->
                    val c = n.contact
                    if (holder.traceMode && c != null) holder.onAddTrace(c) else holder.onSelect(n)
                    true
                }
                // Trace mode: tap adds to the path, long-press shows the node's details.
                if (holder.traceMode) onLong = { holder.onInfo(n) }
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
                map.overlays.add(TapMarker(map).apply {
                    position = GeoPoint(n.lat, n.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setIcon(icon)
                    setOnMarkerClickListener { _, _ -> n.contact?.let { holder.onAddTrace(it) }; true }
                    onLong = { holder.onInfo(n) }
                })
            }
        }
    }
    map.invalidate()
}

private const val CLUSTER_RADIUS_DP = 26f
private const val REBUILD_DEBOUNCE_MS = 100L // coalesce a scroll/zoom gesture's events into one rebuild
private const val VIEWPORT_MARGIN = 0.25 // expand the visible bounds this much before culling off-screen nodes

/** Nodes within the visible bounds, expanded by [VIEWPORT_MARGIN] so a small pan doesn't pop edge markers. */
private fun cullToViewport(nodes: List<MapNode>, bb: BoundingBox): List<MapNode> {
    val latMargin = (bb.latNorth - bb.latSouth) * VIEWPORT_MARGIN
    val lonMargin = (bb.lonEast - bb.lonWest) * VIEWPORT_MARGIN
    val north = bb.latNorth + latMargin
    val south = bb.latSouth - latMargin
    val west = bb.lonWest - lonMargin
    val east = bb.lonEast + lonMargin
    // Skip culling when the view wraps the antemeridian (east <= west) or spans ~the whole world: clustering
    // everything is fine there and this avoids wrap-around edge cases.
    if (east <= west || (north - south) >= 170.0) return nodes
    return nodes.filter { it.lat in south..north && it.lon in west..east }
}

/** A node marker plus the vertical anchor that puts its circle (not its label) on the geo point. */
private class NodeIcon(val drawable: Drawable, val anchorV: Float)

private fun dist(a: Point, b: Point): Double =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

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
