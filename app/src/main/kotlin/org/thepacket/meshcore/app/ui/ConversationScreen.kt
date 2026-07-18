package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import org.thepacket.meshcore.app.ChatMessage
import org.thepacket.meshcore.app.HeardEntry
import org.thepacket.meshcore.app.MsgStatus
import org.thepacket.meshcore.app.RepeaterLogin
import org.thepacket.meshcore.app.regionLabel
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.toHex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    title: String,
    messages: List<ChatMessage>,
    contacts: List<Contact>,
    heard: List<HeardEntry>,
    self: SelfInfo?,
    /** Non-null when this conversation is a room server: its current login state. */
    roomLogin: RepeaterLogin?,
    /** True for a group channel (its incoming texts usually embed the sender's name). */
    isChannel: Boolean,
    /**
     * True when this conversation can only be watched — a channel in a region we observe over MQTT.
     * We have no radio there and nothing to transmit with, so there is no composer at all rather than
     * one that fails on send.
     */
    readOnly: Boolean = false,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onLogin: (password: String) -> Unit,
    onResend: (ChatMessage) -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    /** Quoted text being replied to (tap a bubble to set it); sent as a leading "> …" line. */
    var replyTo by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Resolve a room post's author (4-byte key prefix, hex) to a friendly name. Room members
    // are rarely in Contacts, so fall back to the Heard list (advert names) before raw hex.
    val authorName: (String) -> String = remember(contacts, heard, self) {
        { prefixHex ->
            if (self != null && self.publicKey.copyOf(4).toHex() == prefixHex) "You"
            else contacts.firstOrNull { it.publicKey.copyOf(4).toHex() == prefixHex }
                ?.let { it.name.ifBlank { it.keyPrefixHex } }
                ?: heard.firstOrNull { it.pubKeyHex.startsWith(prefixHex) }
                    ?.name?.takeIf { it.isNotBlank() }
                ?: prefixHex
        }
    }

    // Keep the newest message visible — on a new message AND when the keyboard opens
    // (edge-to-edge means the IME overlays content, so we must re-scroll past it).
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(messages.size, imeVisible) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    var showLoginDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Room login is optional: the room remembers you after the first login, so this
                    // is mainly for first-time registration, re-syncing history, or admin access.
                    when (roomLogin) {
                        null -> {}
                        RepeaterLogin.LoggingIn ->
                            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                        RepeaterLogin.LoggedIn ->
                            Text(
                                "Authorized",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        else ->
                            IconButton(onClick = { showLoginDialog = true }) {
                                Icon(Icons.Filled.Login, contentDescription = "Log in to room")
                            }
                    }
                },
            )
        },
    ) { pad ->
        // imePadding lifts the compose bar (and list) above the keyboard.
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (roomLogin == RepeaterLogin.Failed) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Room login failed — wrong password or no reply.",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.localId }) { m ->
                    MessageBubble(
                        m, m.authorPrefix?.let(authorName), onResend,
                        // Quoting exists to seed a reply, so it does nothing without a composer.
                        onReply = if (readOnly) null else ({
                            replyTo = quoteOf(m, m.authorPrefix?.let(authorName), title, self, isChannel)
                        }),
                    )
                }
            }
            if (readOnly) {
                ObservingNote()
            } else {
                replyTo?.let { r ->
                    ReplyStrip(r, onCancel = { replyTo = null })
                }
                ComposeBar(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {
                        val t = draft.trim()
                        if (t.isNotEmpty()) {
                            // Interop with the on-device UI: a reply is a leading quoted "> …" line.
                            onSend(replyTo?.let { "> $it\n$t" } ?: t)
                            draft = ""; replyTo = null
                        }
                    },
                )
            }
        }
    }

    if (showLoginDialog) {
        RoomLoginDialog(
            loggedIn = roomLogin == RepeaterLogin.LoggedIn,
            onDismiss = { showLoginDialog = false },
            onLogin = onLogin,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    m: ChatMessage,
    author: String?,
    onResend: (ChatMessage) -> Unit,
    /** Null when there's nothing to reply with — the bubble then isn't tappable at all. */
    onReply: (() -> Unit)? = null,
) {
    val align = if (m.incoming) Alignment.CenterStart else Alignment.CenterEnd
    val bubble = if (m.incoming) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .widthIn(max = 280.dp)
                .background(bubble, RoundedCornerShape(14.dp))
                .let { if (onReply != null) it.clickable(onClick = onReply) else it }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Room posts: who wrote it (the conversation is the room, the author is the signer).
            if (author != null) {
                Text(author, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            MessageText(m.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val meta = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                Text(messageTime(m.timestampSecs), style = MaterialTheme.typography.labelSmall, color = meta)
                if (m.incoming) {
                    // Observed-channel metadata from the relaying node's packet (SNR/RSSI/region).
                    if (m.snrDb != null) Text("SNR ${m.snrDb} dB", style = MaterialTheme.typography.labelSmall, color = meta)
                    if (m.rssi != null) Text("RSSI ${m.rssi}", style = MaterialTheme.typography.labelSmall, color = meta)
                    if (m.region != null) Text(regionLabel(m.region), style = MaterialTheme.typography.labelSmall, color = meta)
                }
                if (!m.incoming) {
                    val (label, color) = statusLabel(m.status)
                    if (m.status == MsgStatus.Failed) {
                        Text(
                            "Failed — tap to retry",
                            style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onResend(m) },
                        )
                    } else {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * Message body with the two plain-text chat conventions rendered richly:
 * lines starting with ">" (a quoted reply) are dimmed + italic, and http(s) URLs
 * become tappable links that open in the browser.
 */
@Composable
private fun MessageText(text: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val annotated = remember(text, linkColor, quoteColor) {
        buildAnnotatedString {
            text.lines().forEachIndexed { i, line ->
                if (i > 0) append('\n')
                val lineStart = length
                var pos = 0
                for (match in URL_REGEX.findAll(line)) {
                    // Trailing punctuation is almost never part of a pasted URL.
                    val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
                    if (url.isEmpty()) continue
                    val end = match.range.first + url.length
                    append(line.substring(pos, match.range.first))
                    withLink(
                        LinkAnnotation.Url(
                            url,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        )
                    ) { append(url) }
                    pos = end
                }
                append(line.substring(pos))
                if (line.startsWith(">")) {
                    addStyle(SpanStyle(color = quoteColor, fontStyle = FontStyle.Italic), lineStart, length)
                }
            }
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyLarge)
}

private val URL_REGEX = Regex("""https?://\S+""")

/**
 * Build the quoted line for a tapped message: "Name: text" when we know the author
 * (room posts by signer; DMs by the conversation partner; our own by our node name),
 * else just the text. Nested quotes are stripped and the result is capped, matching
 * the on-device UI's convention so both render each other's replies.
 */
private fun quoteOf(
    m: ChatMessage,
    author: String?,
    title: String,
    self: SelfInfo?,
    isChannel: Boolean,
): String {
    val body = m.text.lines().dropWhile { it.startsWith(">") }.joinToString(" ").trim()
        .ifEmpty { m.text.replace('\n', ' ').trim() }
    val name = when {
        author != null -> author
        !m.incoming -> self?.name?.takeIf { it.isNotBlank() }
        !isChannel -> title // a DM: the sender is the conversation partner
        else -> null // channel texts usually embed "Name: …" already
    }
    // 80-char cap matches the on-device UI and leaves room for the reply itself
    // within the firmware's max text length.
    return (if (name != null) "$name: $body" else body).take(80)
}

/** The pending reply shown above the compose bar, with a cancel (×) button. */
@Composable
private fun ReplyStrip(quote: String, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Replying to: $quote",
                Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RoomLoginDialog(loggedIn: Boolean, onDismiss: () -> Unit, onLogin: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (loggedIn) "Re-sync room" else "Log in to room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The room remembers you after the first login. Logging in again re-syncs " +
                        "history or signs you in as admin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password (blank for public)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onLogin(password) }) { Text("Log in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "HH:mm" for messages from today, "MMM d, HH:mm" for older ones. */
private fun messageTime(secs: Long): String {
    if (secs <= 0) return ""
    val ms = secs * 1000
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "MMM d, HH:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

@Composable
private fun statusLabel(s: MsgStatus): Pair<String, Color> = when (s) {
    MsgStatus.Sending -> "Sending…" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    MsgStatus.Sent -> "Sent" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    MsgStatus.Delivered -> "Delivered ✓" to MaterialTheme.colorScheme.secondary
    MsgStatus.Failed -> "Failed" to MaterialTheme.colorScheme.error
    MsgStatus.Received -> "" to Color.Unspecified
}

/** Stands in for the composer on an observed channel, so the absence reads as deliberate. */
@Composable
private fun ObservingNote() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Observing over MQTT. Your radio isn't in this region.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
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
