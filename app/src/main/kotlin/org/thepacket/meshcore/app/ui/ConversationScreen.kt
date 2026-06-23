package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.thepacket.meshcore.app.ChatMessage
import org.thepacket.meshcore.app.MsgStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    title: String,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the newest message visible — on a new message AND when the keyboard opens
    // (edge-to-edge means the IME overlays content, so we must re-scroll past it).
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(messages.size, imeVisible) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        // imePadding lifts the compose bar (and list) above the keyboard.
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.localId }) { MessageBubble(it) }
            }
            ComposeBar(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    val t = draft.trim()
                    if (t.isNotEmpty()) { onSend(t); draft = "" }
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    val align = if (m.incoming) Alignment.CenterStart else Alignment.CenterEnd
    val bubble = if (m.incoming) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 280.dp)
                .background(bubble, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.incoming && m.snrDb != null) {
                    Text("SNR ${m.snrDb} dB", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
                if (!m.incoming) {
                    val (label, color) = statusLabel(m.status)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun statusLabel(s: MsgStatus): Pair<String, Color> = when (s) {
    MsgStatus.Sending -> "Sending…" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    MsgStatus.Sent -> "Sent" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    MsgStatus.Delivered -> "Delivered ✓" to MaterialTheme.colorScheme.secondary
    MsgStatus.Failed -> "Failed" to MaterialTheme.colorScheme.error
    MsgStatus.Received -> "" to Color.Unspecified
}

@Composable
private fun ComposeBar(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            maxLines = 4,
        )
        IconButton(onClick = onSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send",
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}
