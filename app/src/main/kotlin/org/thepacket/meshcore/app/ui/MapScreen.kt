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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.thepacket.meshcore.app.HeardEntry
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.SelfInfo
import kotlin.math.hypot

/** A node to plot. Positions come only from what nodes already advertise (read-only). */
data class MapNode(val name: String, val lat: Double, val lon: Double, val type: Int, val isSelf: Boolean)

@Composable
fun MapContent(
    self: SelfInfo?,
    contacts: List<Contact>,
    heard: List<HeardEntry>,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(self, contacts, heard) { collectNodes(self, contacts, heard) }
    // Caches so re-clustering on pan/zoom reuses marker bitmaps.
    val nodeIcons = remember { mutableMapOf<String, Drawable>() }
    val clusterIcons = remember { mutableMapOf<Int, Drawable>() }
    val holder = remember { MapHolder() }
    holder.nodes = nodes

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName // required by OSM tile policy
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(3.0)
                    // Re-cluster as the viewport changes (clusters depend on screen positions).
                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            rebuildOverlays(this@apply, holder.nodes, nodeIcons, clusterIcons); return false
                        }
                        override fun onZoom(event: ZoomEvent?): Boolean {
                            rebuildOverlays(this@apply, holder.nodes, nodeIcons, clusterIcons); return false
                        }
                    })
                }
            },
            update = { map ->
                rebuildOverlays(map, nodes, nodeIcons, clusterIcons)
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
    }
}

private class MapHolder {
    var nodes: List<MapNode> = emptyList()
    var centered = false
}

/** Greedy screen-space clustering: nodes within [CLUSTER_RADIUS_DP] merge into a count pill. */
private fun rebuildOverlays(
    map: MapView,
    nodes: List<MapNode>,
    nodeIcons: MutableMap<String, Drawable>,
    clusterIcons: MutableMap<Int, Drawable>,
) {
    map.overlays.clear()
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
            val icon = nodeIcons.getOrPut(nodeKey(n)) { makeNodeIcon(res, n) }
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(n.lat, n.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setIcon(icon)
                title = if (n.isSelf) "${n.name} (this node)" else n.name
                subDescription = "%.5f, %.5f".format(n.lat, n.lon)
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
    map.invalidate()
}

private const val CLUSTER_RADIUS_DP = 44f

private fun dist(a: Point, b: Point): Double =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

private fun nodeKey(n: MapNode): String = "${n.type}|${n.isSelf}|${initialOf(n.name)}"

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

private fun makeNodeIcon(res: Resources, n: MapNode): Drawable {
    val d = res.displayMetrics.density
    val diameter = ((if (n.isSelf) 30f else 26f) * d)
    val pad = 2f * d
    val size = (diameter + pad * 2).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f; val cy = size / 2f; val r = diameter / 2f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)

    // white backing ring then coloured fill
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r, p)
    p.color = nodeColor(n.type, n.isSelf); c.drawCircle(cx, cy, r - 1.5f * d, p)
    if (n.isSelf) { // extra ring to stand out
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, cy, r - 3.5f * d, p); p.style = Paint.Style.FILL
    }

    if (n.type == ContactType.REPEATER) drawTower(c, cx, cy, diameter, d)
    else drawInitial(c, cx, cy, diameter, initialOf(n.name))
    return BitmapDrawable(res, bmp)
}

/** Broadcast tower glyph (mast + signal arcs), white, centred in the circle. */
private fun drawTower(c: Canvas, cx: Float, cy: Float, diameter: Float, d: Float) {
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.8f * d
        strokeCap = Paint.Cap.ROUND
    }
    val tipY = cy - diameter * 0.20f
    // mast
    c.drawLine(cx, tipY, cx, cy + diameter * 0.26f, white)
    // tip
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    c.drawCircle(cx, tipY, 1.7f * d, fill)
    // signal arcs each side of the tip
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
            byKey[c.keyPrefixHex] =
                MapNode(c.name.ifBlank { c.keyPrefixHex }, c.gpsLat / 1e6, c.gpsLon / 1e6, c.type, false)
        }
    }
    heard.forEach { h ->
        if (h.hasGps && !byKey.containsKey(h.pubKeyHex.take(12))) {
            byKey[h.pubKeyHex] = MapNode(h.name, h.latDeg, h.lonDeg, h.type, false)
        }
    }
    val list = byKey.values.toMutableList()
    // Show our own node only if it already advertises a position (read-only; we never set it).
    if (self != null && (self.advLat != 0 || self.advLon != 0)) {
        list.add(0, MapNode(self.name.ifBlank { "This node" }, self.advLat / 1e6, self.advLon / 1e6,
            ContactType.CHAT, true))
    }
    return list
}
