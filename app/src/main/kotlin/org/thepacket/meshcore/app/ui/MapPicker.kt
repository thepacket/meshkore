package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Full-screen map where the user taps to choose a position. The controls are drawn
 * as an overlay on top of the map (a Box) so they always sit above it. Read-only
 * tiles; nothing is sent until the caller saves.
 */
@Composable
fun MapPickerDialog(
    initialLat: Double?,
    initialLon: Double?,
    onPick: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember {
        mutableStateOf(
            if (initialLat != null && initialLon != null) GeoPoint(initialLat, initialLon) else null
        )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER) // no +/-
                        controller.setZoom(if (picked != null) 13.0 else 4.0)
                        picked?.let { controller.setCenter(it) }
                        val marker = Marker(this).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
                        picked?.let { marker.position = it; overlays.add(marker) }
                        val recv = object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                picked = p
                                marker.position = p
                                if (!overlays.contains(marker)) overlays.add(marker)
                                invalidate()
                                return true
                            }
                            override fun longPressHelper(p: GeoPoint) = false
                        }
                        overlays.add(0, MapEventsOverlay(recv))
                    }
                },
                onRelease = { it.onDetach() },
            )

            // Control bar overlaid on top of the map.
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().systemBarsPadding().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Tap the map to set position", style = MaterialTheme.typography.titleMedium)
                        Text(
                            picked?.let { "%.5f, %.5f".format(it.latitude, it.longitude) } ?: "No point selected",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        enabled = picked != null,
                        onClick = { picked?.let { onPick(it.latitude, it.longitude) } },
                    ) { Text("Save") }
                }
            }
        }
    }
}
