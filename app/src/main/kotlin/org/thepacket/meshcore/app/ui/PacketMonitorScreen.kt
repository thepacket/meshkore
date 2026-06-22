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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.thepacket.meshcore.protocol.PayloadType
import org.thepacket.meshcore.protocol.RxLog

@Composable
fun PacketMonitorContent(packets: List<RxLog>, modifier: Modifier = Modifier) {
    var detail by remember { mutableStateOf<RxLog?>(null) }

    if (packets.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Listening for packets…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    } else {
        LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(packets, key = { System.identityHashCode(it) }) { p -> PacketRow(p) { detail = p } }
        }
    }

    detail?.let { p ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
            title = { Text("${p.typeName} packet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Route: ${p.routeName}    Length: ${p.length} B")
                    Text("SNR ${p.snrDb} dB    RSSI ${p.rssi} dBm")
                    Text("Header: 0x%02X".format(p.header))
                    Text("Raw:", fontWeight = FontWeight.SemiBold)
                    Text(p.hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
}

@Composable
private fun PacketRow(p: RxLog, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TypePill(p.payloadType, p.typeName)
        Column(Modifier.weight(1f)) {
            Text("${p.length} B · ${p.routeName}", style = MaterialTheme.typography.bodyMedium)
            Text("SNR ${p.snrDb} · RSSI ${p.rssi}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun TypePill(type: Int, label: String) {
    val color = payloadColor(type)
    Box(
        Modifier.background(color.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun payloadColor(type: Int): Color = when (type) {
    PayloadType.TXT_MSG, PayloadType.GRP_TXT -> Color(0xFF4ADE80)
    PayloadType.ACK -> Color(0xFF22D3EE)
    PayloadType.ADVERT -> Color(0xFFF59E0B)
    PayloadType.PATH, PayloadType.TRACE -> Color(0xFFA78BFA)
    PayloadType.REQ, PayloadType.RESPONSE, PayloadType.ANON_REQ -> Color(0xFF60A5FA)
    else -> Color(0xFF94A3B8)
}
