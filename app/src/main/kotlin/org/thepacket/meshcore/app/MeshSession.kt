package org.thepacket.meshcore.app

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.thepacket.meshcore.ble.MeshCoreLink
import org.thepacket.meshcore.protocol.AutoAddConfig
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.CoreStats
import org.thepacket.meshcore.protocol.Incoming
import org.thepacket.meshcore.protocol.PacketStats
import org.thepacket.meshcore.protocol.PayloadType
import org.thepacket.meshcore.protocol.toHex
import org.thepacket.meshcore.protocol.RadioStats
import org.thepacket.meshcore.protocol.Requests
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.StatsType
import org.thepacket.meshcore.protocol.TuningParams
import java.util.ArrayDeque

/** Result of an applied settings write, for UI feedback. */
data class SettingsResult(val label: String, val ok: Boolean)

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
    private val chatStore: ChatStore? = null,
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

    /** Recently-heard stations (most-recent first). */
    private val _heard = MutableStateFlow<List<HeardEntry>>(emptyList())
    val heard: StateFlow<List<HeardEntry>> = _heard.asStateFlow()

    // ---- settings (config that SELF_INFO doesn't return is fetched on connect) ----
    private val _tuning = MutableStateFlow<TuningParams?>(null)
    val tuning: StateFlow<TuningParams?> = _tuning.asStateFlow()

    private val _autoAdd = MutableStateFlow<AutoAddConfig?>(null)
    val autoAdd: StateFlow<AutoAddConfig?> = _autoAdd.asStateFlow()

    /** Emitted after each settings write (OK/ERR) for UI feedback. */
    private val _settingsResult = MutableSharedFlow<SettingsResult>(extraBufferCapacity = 8)
    val settingsResult: SharedFlow<SettingsResult> = _settingsResult.asSharedFlow()

    /** Labels of settings writes awaiting their OK/ERR reply (FIFO; link is sequential). */
    private val pendingSettings = ArrayDeque<String>()

    private var statsJob: Job? = null
    /** Signal from the most recent ADVERT-type RX packet, to attach to the next advert push. */
    private var lastAdvertSnrQ: Int? = null
    private var lastAdvertRssi: Int? = null

    // --- internal sync/send bookkeeping ---
    private val contactAccumulator = mutableListOf<Contact>()
    private val channelAccumulator = mutableListOf<ChannelEntry>()
    private var enumeratingChannels = false
    private var draining = false
    private var localIdSeq = 0L
    private var saveJob: Job? = null
    /** Outgoing messages awaiting their immediate SENT/OK reply, FIFO (link is sequential). */
    private val pendingSends = ArrayDeque<Long>()

    init {
        // Restore persisted chat history (survives disconnects + app restarts).
        chatStore?.load()?.let { saved ->
            if (saved.isNotEmpty()) {
                _messages.value = saved
                localIdSeq = saved.values.flatten().maxOfOrNull { it.localId } ?: 0L
            }
        }
        scope.launch { link.incoming.collect(::onFrame) }
    }

    /** Call once the link reports Connected. */
    fun start() {
        scope.launch { link.send(Requests.appStart()) }
        // Pre-fetch config that SELF_INFO doesn't carry, so the settings form pre-fills.
        scope.launch { link.send(Requests.getTuningParams()) }
        scope.launch { link.send(Requests.getAutoAddConfig()) }
        startStatsPolling()
    }

    fun reset() {
        statsJob?.cancel(); statsJob = null
        _self.value = null
        _contacts.value = emptyList()
        _channels.value = emptyList()
        // NB: _messages is intentionally NOT cleared — chat history persists across disconnects.
        _packets.value = emptyList()
        _radioStats.value = null
        _coreStats.value = null
        _packetStats.value = null
        _noiseHistory.value = emptyList()
        _heard.value = emptyList()
        _tuning.value = null
        _autoAdd.value = null
        pendingSettings.clear()
        lastAdvertSnrQ = null; lastAdvertRssi = null
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

    // ---- settings writes (each elicits an OK/ERR -> settingsResult) -------

    fun applyNodeName(name: String) = applySetting("Node name", Requests.setAdvertName(name))

    fun applyRadio(freqMhz: Double, bwKhz: Double, sf: Int, cr: Int, clientRepeat: Boolean) =
        applySetting("Radio", Requests.setRadioParams((freqMhz * 1000).toLong(), (bwKhz * 1000).toLong(), sf, cr, clientRepeat))

    fun applyTxPower(dbm: Int) = applySetting("TX power", Requests.setRadioTxPower(dbm))

    fun applyPosition(latE6: Int, lonE6: Int) = applySetting("Position", Requests.setAdvertLatLon(latE6, lonE6))

    fun applyOtherParams(manualAdd: Boolean, telemBase: Int, telemLoc: Int, telemEnv: Int, shareLocation: Boolean, multiAcks: Int) =
        applySetting(
            "Network & telemetry",
            Requests.setOtherParams(
                manualAdd, telemBase, telemLoc, telemEnv,
                if (shareLocation) org.thepacket.meshcore.protocol.AdvertLoc.SHARE else org.thepacket.meshcore.protocol.AdvertLoc.NONE,
                multiAcks,
            ),
        )

    fun applyAutoAdd(flags: Int, maxHops: Int) = applySetting("Auto-add", Requests.setAutoAddConfig(flags, maxHops))

    fun applyTuning(rxDelayBase: Double, airtimeFactor: Double) =
        applySetting("Tuning", Requests.setTuningParams(rxDelayBase, airtimeFactor))

    fun applyPathHashMode(mode: Int) = applySetting("Path-hash mode", Requests.setPathHashMode(mode))

    fun syncTimeFromPhone() = applySetting("Time", Requests.setDeviceTime(System.currentTimeMillis() / 1000))

    private fun applySetting(label: String, frame: ByteArray) {
        pendingSettings.addLast(label)
        scope.launch { link.send(frame) }
    }

    private fun resolveSetting(ok: Boolean) {
        val label = pendingSettings.pollFirst() ?: return
        _settingsResult.tryEmit(SettingsResult(label, ok))
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
            Incoming.Ok ->
                if (pendingSettings.isNotEmpty()) resolveSetting(true) // settings write ack
                else onSendAcceptedNoAck()
            is Incoming.Err -> when {
                pendingSettings.isNotEmpty() -> resolveSetting(false)
                enumeratingChannels -> finishChannelEnumeration() // err = no more channel slots
                else -> onSendFailed()
            }
            is Incoming.Tuning -> _tuning.value = f.params
            is Incoming.AutoAdd -> _autoAdd.value = f.config
            is Incoming.SendConfirmed -> markDelivered(f.ackId, f.roundTripMs)

            is Incoming.RxPacket -> {
                _packets.update { (listOf(f.log) + it).take(MAX_PACKETS) }
                // Remember the signal of the latest advert packet to attach to the advert push.
                if (f.log.payloadType == PayloadType.ADVERT) {
                    lastAdvertSnrQ = f.log.snrQ; lastAdvertRssi = f.log.rssi
                }
            }
            is Incoming.AdvertHeard -> upsertHeardByKey(f.publicKey)
            is Incoming.NewAdvert -> {
                // also fold the new node into the contact list
                _contacts.update { list ->
                    if (list.any { it.publicKey.contentEquals(f.contact.publicKey) }) list
                    else (list + f.contact).sortedByDescending { it.lastAdvert }
                }
                upsertHeard(
                    HeardEntry(
                        pubKeyHex = f.contact.publicKey.toHex(),
                        name = f.contact.name,
                        type = f.contact.type,
                        lastHeardMs = System.currentTimeMillis(),
                        snrQ = lastAdvertSnrQ, rssi = lastAdvertRssi,
                        gpsLat = f.contact.gpsLat, gpsLon = f.contact.gpsLon,
                    )
                )
            }
            is Incoming.RadioStatsResp -> {
                _radioStats.value = f.stats
                _noiseHistory.update { (it + f.stats.noiseFloor).takeLast(NOISE_HISTORY) }
            }
            is Incoming.CoreStatsResp -> _coreStats.value = f.stats
            is Incoming.PacketStatsResp -> _packetStats.value = f.stats

            else -> Unit // adverts/path-updates/etc. handled elsewhere later
        }
    }

    /** Re-advert from a known node: enrich with its contact details if we have them. */
    private fun upsertHeardByKey(pubKey: ByteArray) {
        val hex = pubKey.toHex()
        val c = _contacts.value.firstOrNull { it.publicKey.contentEquals(pubKey) }
        upsertHeard(
            HeardEntry(
                pubKeyHex = hex,
                name = c?.name ?: hex.take(12),
                type = c?.type ?: 0,
                lastHeardMs = System.currentTimeMillis(),
                snrQ = lastAdvertSnrQ, rssi = lastAdvertRssi,
                gpsLat = c?.gpsLat ?: 0, gpsLon = c?.gpsLon ?: 0,
            )
        )
    }

    private fun upsertHeard(entry: HeardEntry) {
        _heard.update { list ->
            (listOf(entry) + list.filterNot { it.pubKeyHex == entry.pubKeyHex })
                .sortedByDescending { it.lastHeardMs }
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
        // Dedup: skip if an identical incoming message is already stored (e.g. across reconnects).
        val existing = _messages.value[convId].orEmpty()
        if (existing.any { it.incoming && it.timestampSecs == m.senderTimestamp && it.text == m.text }) return
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
        if (matched) scheduleSave()
    }

    // ---- message store helpers -------------------------------------------

    private fun appendMessage(msg: ChatMessage) {
        _messages.update { map ->
            val list = map[msg.conversationId].orEmpty() + msg
            map + (msg.conversationId to list)
        }
        scheduleSave()
    }

    private fun updateMessage(localId: Long, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { map ->
            map.mapValues { (_, list) -> list.map { if (it.localId == localId) transform(it) else it } }
        }
        scheduleSave()
    }

    /** Persist chat history shortly after a change (debounced to coalesce bursts). */
    private fun scheduleSave() {
        val store = chatStore ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            store.save(_messages.value)
        }
    }
}
