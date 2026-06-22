package org.thepacket.meshcore.app.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.SelfInfo
import kotlin.math.abs

@Composable
fun HomeContent(
    self: SelfInfo?,
    channels: List<org.thepacket.meshcore.app.ChannelEntry>,
    contacts: List<Contact>,
    onOpenConversation: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            self?.let {
                item { DeviceHeader(it) }
            }
            item {
                Text("Channels", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            items(channels, key = { "ch:${it.index}" }) { ch ->
                ConversationRow(
                    icon = Icons.Default.Campaign,
                    tint = MaterialTheme.colorScheme.tertiary,
                    title = ch.displayName,
                    subtitle = "Channel ${ch.index}",
                    onClick = { onOpenConversation(org.thepacket.meshcore.app.Conversation.channelId(ch.index), ch.displayName) },
                )
            }
            item {
                Text("Contacts", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            if (contacts.isEmpty()) {
                item { Text("No contacts synced yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(8.dp)) }
            }
            items(contacts, key = { it.keyPrefixHex }) { c ->
                ConversationRow(
                    icon = if (c.isRepeater) Icons.Default.Router else Icons.Default.Person,
                    tint = nameColor(c.name.ifBlank { c.keyPrefixHex }),
                    title = c.name.ifBlank { c.keyPrefixHex },
                    subtitle = contactTypeLabel(c.type),
                    onClick = { onOpenConversation(org.thepacket.meshcore.app.Conversation.dmId(c), c.name.ifBlank { c.keyPrefixHex }) },
                )
            }
        }
    }

@Composable
private fun DeviceHeader(self: SelfInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(self.name.ifBlank { "(unnamed node)" }, style = MaterialTheme.typography.titleLarge)
            Text("${self.freqMhz} MHz · ${self.bwKhz} kHz · SF${self.radioSf} · 4/${self.radioCr} · ${self.txPower} dBm",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun ConversationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = tint.copy(alpha = 0.18f), shape = CircleShape) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tint)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

private fun contactTypeLabel(type: Int) = when (type) {
    ContactType.CHAT -> "Contact"
    ContactType.REPEATER -> "Repeater"
    ContactType.ROOM -> "Room"
    ContactType.SENSOR -> "Sensor"
    else -> "Node"
}

/** Stable per-name colour (matches the on-device UI's hashed-username idea). */
fun nameColor(name: String): Color {
    val palette = listOf(
        Color(0xFF4ADE80), Color(0xFF60A5FA), Color(0xFFF59E0B), Color(0xFFF472B6),
        Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFFFB7185), Color(0xFF34D399),
    )
    return palette[abs(name.hashCode()) % palette.size]
}
