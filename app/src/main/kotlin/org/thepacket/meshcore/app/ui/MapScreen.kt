package org.thepacket.meshcore.app.ui

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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.thepacket.meshcore.app.HeardEntry
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.SelfInfo

/** A node to plot. Positions come only from what nodes already advertise (read-only). */
data class MapNode(val name: String, val lat: Double, val lon: Double, val isSelf: Boolean)

@Composable
fun MapContent(
    self: SelfInfo?,
    contacts: List<Contact>,
    heard: List<HeardEntry>,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(self, contacts, heard) { collectNodes(self, contacts, heard) }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName // required by OSM tile policy
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(3.0)
                }
            },
            update = { map ->
                map.overlays.clear()
                nodes.forEach { n ->
                    map.overlays.add(
                        Marker(map).apply {
                            position = GeoPoint(n.lat, n.lon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = if (n.isSelf) "${n.name} (this node)" else n.name
                            subDescription = "%.5f, %.5f".format(n.lat, n.lon)
                        }
                    )
                }
                // Center on the first available node the first time we have one.
                nodes.firstOrNull()?.let { map.controller.setCenter(GeoPoint(it.lat, it.lon)) }
                if (nodes.isNotEmpty() && map.zoomLevelDouble < 5.0) map.controller.setZoom(10.0)
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

private fun collectNodes(self: SelfInfo?, contacts: List<Contact>, heard: List<HeardEntry>): List<MapNode> {
    val byKey = LinkedHashMap<String, MapNode>()
    contacts.forEach { c ->
        if (c.gpsLat != 0 || c.gpsLon != 0) {
            byKey[c.keyPrefixHex] = MapNode(c.name.ifBlank { c.keyPrefixHex }, c.gpsLat / 1e6, c.gpsLon / 1e6, false)
        }
    }
    heard.forEach { h ->
        if (h.hasGps && !byKey.containsKey(h.pubKeyHex.take(12))) {
            byKey[h.pubKeyHex] = MapNode(h.name, h.latDeg, h.lonDeg, false)
        }
    }
    val list = byKey.values.toMutableList()
    // Show our own node only if it already advertises a position (read-only; we never set it).
    if (self != null && (self.advLat != 0 || self.advLon != 0)) {
        list.add(0, MapNode(self.name.ifBlank { "This node" }, self.advLat / 1e6, self.advLon / 1e6, true))
    }
    return list
}
