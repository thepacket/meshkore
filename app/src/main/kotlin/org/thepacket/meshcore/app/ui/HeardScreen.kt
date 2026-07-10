package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.thepacket.meshcore.app.HeardEntry
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.app.haversineKm
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.hexToBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HeardContent(
    heard: List<HeardEntry>,
    contacts: List<Contact>,
    self: SelfInfo?,
    session: MeshSession,
    modifier: Modifier = Modifier,
    onShowOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
) {
    val pathDiscovery by session.pathDiscovery.collectAsStateWithLifecycle()
    // Resolve names from the persisted aggregate book too (device contacts are empty until a sync
    // completes, and many heard stations aren't on the current device at all).
    val allContacts by session.allContacts.collectAsStateWithLifecycle()
    val book = remember(contacts, allContacts) { (contacts + allContacts).distinctBy { it.keyPrefixHex } }
    if (heard.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No stations heard yet…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        return
    }
    var selected by remember { mutableStateOf<HeardEntry?>(null) }
    val selfHasGps = self != null && (self.advLat != 0 || self.advLon != 0)
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(heard, key = { it.pubKeyHex }) { h ->
            val distanceKm = if (selfHasGps && h.hasGps)
                haversineKm(self!!.advLat / 1e6, self.advLon / 1e6, h.latDeg, h.lonDeg) else null
            HeardRow(h, resolveHeardName(h, book), distanceKm) { selected = h }
        }
    }

    selected?.let { h ->
        val contact = book.firstOrNull { h.pubKeyHex.startsWith(it.keyPrefixHex) }
        NodeDetailSheet(
            name = resolveHeardName(h, book),
            type = contact?.type ?: h.type,
            isSelf = false,
            contact = contact,
            heard = h,
            self = self,
            onDismiss = { selected = null },
            onShowOnMap = onShowOnMap,
            onDiscoverPath = { session.discoverPath(contact?.publicKey ?: h.pubKeyHex.hexToBytes()) },
            pathResult = pathDiscovery[contact?.keyPrefixHex ?: h.pubKeyHex.take(12)],
        )
    }
}

/**
 * Resolve a heard entry to a friendly name. The [AdvertHeard] push that feeds many entries
 * carries only a public key, so the stored [HeardEntry.name] is often just hex. Match against
 * the current contact list (full key, else key-prefix) and prefer a real contact name; fall
 * back to whatever name the entry already carries.
 */
private fun resolveHeardName(h: HeardEntry, contacts: List<Contact>): String {
    val contact = contacts.firstOrNull { h.pubKeyHex.startsWith(it.keyPrefixHex) }
    return contact?.name?.ifBlank { null } ?: h.name
}

@Composable
private fun HeardRow(h: HeardEntry, name: String, distanceKm: Double?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SignalDot(h.snrDb)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val signal = buildString {
                    if (h.snrDb != null) append("SNR ${h.snrDb} dB")
                    if (h.rssi != null) { if (isNotEmpty()) append(" · "); append("RSSI ${h.rssi}") }
                    if (isEmpty()) append("signal n/a")
                }
                Text(signal, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(clockTime(h.lastHeardMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace)
                Text(ageLabel(h.lastHeardMs), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                if (distanceKm != null) {
                    Text(fmtDistance(distanceKm), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Colour-graded dot by link quality (SNR). */
@Composable
private fun SignalDot(snrDb: Double?) {
    val color = when {
        snrDb == null -> Color(0xFF64748B)
        snrDb >= 5 -> Color(0xFF4ADE80)
        snrDb >= 0 -> Color(0xFFF59E0B)
        else -> Color(0xFFFB7185)
    }
    Surface(color = color.copy(alpha = 0.20f), shape = CircleShape) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        }
    }
}

internal fun ageLabel(ms: Long): String {
    val secs = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ago"
        else -> "${secs / 3600}h ago"
    }
}

/** Wall-clock time-of-day, e.g. "14:03:27". */
internal fun clockTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

internal fun fmtDistance(km: Double): String =
    if (km < 1.0) "${(km * 1000).toInt()} m" else "%.1f km".format(km)
