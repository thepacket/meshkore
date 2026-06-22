package org.thepacket.meshcore.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.thepacket.meshcore.ble.MeshCoreLink
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.CoreStats
import org.thepacket.meshcore.protocol.Incoming
import org.thepacket.meshcore.protocol.PacketStats
import org.thepacket.meshcore.protocol.RadioStats
import org.thepacket.meshcore.protocol.Requests
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.StatsType
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
    private companion object {
        const val MAX_CHANNELS = 8 // safety cap when probing channel slots
        const val MAX_PACKETS = 200 // cap the live packet feed
        const val NOISE_HISTORY = 120 // noise-floor samples retained for the graph
        const val TAG = "MeshSession"
    }

    private val _self = MutableStateFlow<SelfInfo?>(null)
    val self: StateFlow<SelfInfo?> = _self.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _channels = MutableStateFlow<List<ChannelEntry>>(emptyList())
    val channels: StateFlow<List<ChannelEntry>> = _channels.asStateFlow()

    /** conversationId -> messages (oldest first). */
    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    // ---- instrumentation (P2) ----
    /** Live raw-packet feed (newest first, capped). */
    private val _packets = MutableStateFlow<List<RxLog>>(emptyList())
    val packets: StateFlow<List<RxLog>> = _packets.asStateFlow()

    private val _radioStats = MutableStateFlow<RadioStats?>(null)
    val radioStats: StateFlow<RadioStats?> = _radioStats.asStateFlow()

    private val _coreStats = MutableStateFlow<CoreStats?>(null)
    val coreStats: StateFlow<CoreStats?> = _coreStats.asStateFlow()

    private val _packetStats = MutableStateFlow<PacketStats?>(null)
    val packetStats: StateFlow<PacketStats?> = _packetStats.asStateFlow()

    /** Rolling noise-floor history (dBm), oldest first, capped — for the noise graph. */
    private val _noiseHistory = MutableStateFlow<List<Int>>(emptyList())
    val noiseHistory: StateFlow<List<Int>> = _noiseHistory.asStateFlow()

    private var statsJob: Job? = null

    // --- internal sync/send bookkeeping ---
    private val contactAccumulator = mutableListOf<Contact>()
    private val channelAccumulator = mutableListOf<ChannelEntry>()
    private var enumeratingChannels = false
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
        startStatsPolling()
    }

    fun reset() {
        statsJob?.cancel(); statsJob = null
        _self.value = null
        _contacts.value = emptyList()
        _channels.value = emptyList()
        _messages.value = emptyMap()
        _packets.value = emptyList()
        _radioStats.value = null
        _coreStats.value = null
        _packetStats.value = null
        _noiseHistory.value = emptyList()
        contactAccumulator.clear()
        channelAccumulator.clear()
        pendingSends.clear()
        enumeratingChannels = false
        draining = false
    }

    /** Poll radio stats every 2s (noise/rssi/snr) and core/packet stats less often. */
    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var tick = 0
            while (true) {
                link.send(Requests.getStats(StatsType.RADIO))
                if (tick % 3 == 0) {
                    link.send(Requests.getStats(StatsType.CORE))
                    link.send(Requests.getStats(StatsType.PACKETS))
                }
                tick++
                delay(2_000)
            }
        }
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
        Log.d(TAG, "rx ${f::class.simpleName}")
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
                startChannelEnumeration() // then drains messages when done
            }

            is Incoming.ChannelInfo -> {
                channelAccumulator.add(ChannelEntry(f.index, f.name))
                _channels.value = channelAccumulator.toList()
                if (f.index + 1 < MAX_CHANNELS) {
                    scope.launch { link.send(Requests.getChannel(f.index + 1)) }
                } else {
                    finishChannelEnumeration()
                }
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
            is Incoming.Err ->
                if (enumeratingChannels) finishChannelEnumeration() // err = no more channel slots
                else onSendFailed()
            is Incoming.SendConfirmed -> markDelivered(f.ackId, f.roundTripMs)

            is Incoming.RxPacket -> _packets.update { (listOf(f.log) + it).take(MAX_PACKETS) }
            is Incoming.RadioStatsResp -> {
                _radioStats.value = f.stats
                _noiseHistory.update { (it + f.stats.noiseFloor).takeLast(NOISE_HISTORY) }
            }
            is Incoming.CoreStatsResp -> _coreStats.value = f.stats
            is Incoming.PacketStatsResp -> _packetStats.value = f.stats

            else -> Unit // adverts/path-updates/etc. handled elsewhere later
        }
    }

    private fun startChannelEnumeration() {
        enumeratingChannels = true
        channelAccumulator.clear()
        scope.launch { link.send(Requests.getChannel(0)) }
    }

    private fun finishChannelEnumeration() {
        enumeratingChannels = false
        if (channelAccumulator.isEmpty()) _channels.value = listOf(ChannelEntry(0, "Public"))
        startDrain() // now pull any queued messages
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
        Log.d(TAG, "SENT localId=$localId expectedAck=$expectedAck estTimeout=$estTimeoutMs")
        updateMessage(localId) {
            it.copy(status = MsgStatus.Sent, expectedAck = expectedAck)
        }
        // Do NOT hard-fail on a client timer: the mesh may still be delivering, and a late
        // SendConfirmed can upgrade Sent -> Delivered. Leave it at "Sent" if no ack returns.
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
        var matched = false
        _messages.update { map ->
            map.mapValues { (_, list) ->
                list.map {
                    if (it.expectedAck == ackId && !it.incoming) {
                        matched = true
                        it.copy(status = MsgStatus.Delivered)
                    } else it
                }
            }
        }
        Log.d(TAG, "CONFIRMED ackId=$ackId trip=${roundTripMs}ms matched=$matched")
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
