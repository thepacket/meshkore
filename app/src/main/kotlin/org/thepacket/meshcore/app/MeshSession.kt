package org.thepacket.meshcore.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.thepacket.meshcore.ble.MeshCoreLink
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.Incoming
import org.thepacket.meshcore.protocol.Requests
import org.thepacket.meshcore.protocol.SelfInfo
import java.util.ArrayDeque

/**
 * Post-connection protocol orchestration for one companion link: the APP_START
 * handshake, contacts sync, the message drain-loop (MSG_WAITING → SYNC_NEXT_MESSAGE
 * until NO_MORE_MESSAGES), and outgoing sends with ack-correlated delivery status.
 *
 * Holds the chat state as flows the UI observes. In-memory only (M7 = persistence).
 */
class MeshSession(
    private val link: MeshCoreLink,
    private val scope: CoroutineScope,
) {
    private val _self = MutableStateFlow<SelfInfo?>(null)
    val self: StateFlow<SelfInfo?> = _self.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /** conversationId -> messages (oldest first). */
    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    // --- internal sync/send bookkeeping ---
    private val contactAccumulator = mutableListOf<Contact>()
    private var draining = false
    private var localIdSeq = 0L
    /** Outgoing messages awaiting their immediate SENT/OK reply, FIFO (link is sequential). */
    private val pendingSends = ArrayDeque<Long>()

    init {
        scope.launch { link.incoming.collect(::onFrame) }
    }

    /** Call once the link reports Connected. */
    fun start() {
        scope.launch { link.send(Requests.appStart()) }
    }

    fun reset() {
        _self.value = null
        _contacts.value = emptyList()
        _messages.value = emptyMap()
        contactAccumulator.clear()
        pendingSends.clear()
        draining = false
    }

    // ---- sending ----------------------------------------------------------

    fun sendDirect(contact: Contact, text: String) {
        val convId = Conversation.dmId(contact)
        val msg = newOutgoing(convId, text)
        appendMessage(msg)
        pendingSends.addLast(msg.localId)
        scope.launch {
            link.send(Requests.sendTextMessage(contact.publicKey.copyOf(6), text, msg.timestampSecs))
        }
    }

    fun sendChannel(index: Int, text: String) {
        val convId = Conversation.channelId(index)
        val msg = newOutgoing(convId, text)
        appendMessage(msg)
        pendingSends.addLast(msg.localId)
        scope.launch {
            link.send(Requests.sendChannelTextMessage(index, text, msg.timestampSecs))
        }
    }

    private fun newOutgoing(convId: String, text: String) = ChatMessage(
        localId = ++localIdSeq,
        conversationId = convId,
        text = text,
        timestampSecs = System.currentTimeMillis() / 1000,
        incoming = false,
        status = MsgStatus.Sending,
    )

    // ---- incoming frames --------------------------------------------------

    private fun onFrame(f: Incoming) {
        when (f) {
            is Incoming.Self -> {
                _self.value = f.info
                scope.launch { link.send(Requests.getContacts()) }
            }
            is Incoming.ContactsStart -> contactAccumulator.clear()
            is Incoming.ContactEntry -> contactAccumulator.add(f.contact)
            is Incoming.ContactsEnd -> {
                _contacts.value = contactAccumulator.sortedByDescending { it.lastAdvert }
                contactAccumulator.clear()
                startDrain() // pull any queued messages
            }

            Incoming.MsgWaiting -> startDrain()
            is Incoming.Message -> {
                storeIncoming(f.message)
                // keep draining: ask for the next queued message
                scope.launch { link.send(Requests.syncNextMessage()) }
            }
            Incoming.NoMoreMessages -> draining = false

            is Incoming.Sent -> onSentReply(f.result.expectedAck, f.result.estTimeoutMs)
            Incoming.Ok -> onSendAcceptedNoAck()
            is Incoming.Err -> onSendFailed()
            is Incoming.SendConfirmed -> markDelivered(f.ackId, f.roundTripMs)

            else -> Unit // adverts/status/etc. handled elsewhere later
        }
    }

    private fun startDrain() {
        if (draining) return
        draining = true
        scope.launch { link.send(Requests.syncNextMessage()) }
    }

    private fun storeIncoming(m: org.thepacket.meshcore.protocol.IncomingMessage) {
        val convId = if (m.isChannel) Conversation.channelId(m.channelIdx)
        else Conversation.dmId(m.pubKeyPrefix)
        appendMessage(
            ChatMessage(
                localId = ++localIdSeq,
                conversationId = convId,
                text = m.text,
                timestampSecs = m.senderTimestamp,
                incoming = true,
                status = MsgStatus.Received,
                snrDb = if (m.snrQ != 0) m.snrDb else null,
            )
        )
    }

    private fun onSentReply(expectedAck: Long, estTimeoutMs: Long) {
        val localId = pendingSends.pollFirst() ?: return
        updateMessage(localId) {
            it.copy(status = MsgStatus.Sent, expectedAck = expectedAck)
        }
        if (expectedAck != 0L) {
            // mark failed if no SendConfirmed arrives within the firmware's estimate (+slack)
            val timeout = (if (estTimeoutMs > 0) estTimeoutMs else 10_000) + 3_000
            scope.launch {
                delay(timeout)
                updateMessage(localId) {
                    if (it.status == MsgStatus.Sent) it.copy(status = MsgStatus.Failed) else it
                }
            }
        }
    }

    /** Channel sends (and other ok-replying sends) get RESP_CODE_OK with no ack. */
    private fun onSendAcceptedNoAck() {
        val localId = pendingSends.pollFirst() ?: return
        updateMessage(localId) { it.copy(status = MsgStatus.Sent) }
    }

    private fun onSendFailed() {
        val localId = pendingSends.pollFirst() ?: return
        updateMessage(localId) { it.copy(status = MsgStatus.Failed) }
    }

    private fun markDelivered(ackId: Long, roundTripMs: Long) {
        _messages.update { map ->
            map.mapValues { (_, list) ->
                list.map { if (it.expectedAck == ackId && !it.incoming) it.copy(status = MsgStatus.Delivered) else it }
            }
        }
    }

    // ---- message store helpers -------------------------------------------

    private fun appendMessage(msg: ChatMessage) {
        _messages.update { map ->
            val list = map[msg.conversationId].orEmpty() + msg
            map + (msg.conversationId to list)
        }
    }

    private fun updateMessage(localId: Long, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { map ->
            map.mapValues { (_, list) -> list.map { if (it.localId == localId) transform(it) else it } }
        }
    }
}
