package org.thepacket.meshcore.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.random.Random
import kotlinx.coroutines.launch
import org.thepacket.meshcore.ble.LinkState
import org.thepacket.meshcore.ble.MeshCoreLink
import org.thepacket.meshcore.protocol.AdvertPathInfo
import org.thepacket.meshcore.protocol.AutoAddConfig
import org.thepacket.meshcore.protocol.BattAndStorage
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.ContactType
import org.thepacket.meshcore.protocol.DiscoveredNode
import org.thepacket.meshcore.protocol.GroupCipher
import org.thepacket.meshcore.protocol.PacketInspector
import org.thepacket.meshcore.protocol.CoreStats
import org.thepacket.meshcore.protocol.CustomVar
import org.thepacket.meshcore.protocol.DeviceInfo
import org.thepacket.meshcore.protocol.Incoming
import org.thepacket.meshcore.protocol.Lpp
import org.thepacket.meshcore.protocol.PacketStats
import org.thepacket.meshcore.protocol.PayloadType
import org.thepacket.meshcore.protocol.Acl
import org.thepacket.meshcore.protocol.AclEntry
import org.thepacket.meshcore.protocol.Neighbour
import org.thepacket.meshcore.protocol.AnonReqType
import org.thepacket.meshcore.protocol.FrameReader
import org.thepacket.meshcore.protocol.Mma
import org.thepacket.meshcore.protocol.MmaReading
import org.thepacket.meshcore.protocol.Neighbours
import org.thepacket.meshcore.protocol.OwnerInfo
import org.thepacket.meshcore.protocol.RepeaterStats
import org.thepacket.meshcore.protocol.TxtType
import org.thepacket.meshcore.protocol.hexToBytes
import org.thepacket.meshcore.protocol.toHex
import org.thepacket.meshcore.protocol.RadioStats
import org.thepacket.meshcore.protocol.RawDataFrame
import org.thepacket.meshcore.protocol.Requests
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.StatsType
import org.thepacket.meshcore.protocol.PathDiscoveryResult
import org.thepacket.meshcore.protocol.TraceResult
import org.thepacket.meshcore.protocol.TuningParams
import java.util.ArrayDeque

/** Result of an applied settings write, for UI feedback. */
data class SettingsResult(val label: String, val ok: Boolean)

/** Outcome of a private-key export request. */
sealed interface PrivateKeyExport {
    /** The 64-byte private(32)+public(32) identity, as 128 hex chars. */
    data class Ready(val hex: String) : PrivateKeyExport
    /** The firmware was not built with key export enabled (replied DISABLED). */
    data object Unsupported : PrivateKeyExport
}

enum class RepeaterLogin { None, LoggingIn, LoggedIn, Failed }

/** Live state of a managed repeater/room session (keyed by 6-byte key-prefix hex). */
data class RepeaterSession(
    val login: RepeaterLogin = RepeaterLogin.None,
    val isAdmin: Boolean = false,
    val stats: RepeaterStats? = null,
    val console: List<String> = emptyList(), // CLI command echoes + responses, oldest first
    val neighbours: List<Neighbour>? = null,  // null = not requested yet
    val acl: List<AclEntry>? = null,          // access-control list (admin only); null = not requested
    val owner: OwnerInfo? = null,             // firmware/owner info; null = not requested
    /** Pre-login anon probe results (CMD_SEND_ANON_REQ); null = not requested. */
    val anonOwner: OwnerInfo? = null,         // owner/name from an anonymous (no-login) query
    val anonClockSecs: Long? = null,          // the node's clock (epoch secs) from a no-login query
    /** Firmware-confirmed session state from HAS_CONNECTION: true/false, null = not checked. */
    val sessionActive: Boolean? = null,
)

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
    private val adminPrefs: AdminPrefs? = null,
    private val contactStore: ContactStore? = null,
    private val packetStore: PacketStore? = null,
    private val contactPrefs: ContactPrefs? = null,
    private val observedChannelStore: ObservedChannelStore? = null,
    initialRegion: String = MqttPrefs.DEFAULT_REGION,
    initialHomeRegion: String? = null,
) {
    private companion object {
        const val MAX_CHANNELS = 8 // safety cap when probing channel slots
        const val MAX_PACKETS = 5000 // cap the received raw-data frame list (newest kept, earliest dropped)
        const val MAX_PACKET_HISTORY = 5000 // cap the persisted analytics history
        const val UI_THROTTLE_MS = 400L // rate-limit heavy per-packet UI flows (~2.5 Hz) under a busy feed
        const val NOISE_HISTORY = 120 // noise-floor samples retained for the graph
        const val TAG = "MeshSession"
    }

    /** BLE companion link state — exposed so the UI can gate device-dependent actions. */
    val linkState: StateFlow<LinkState> get() = link.state

    private val _self = MutableStateFlow<SelfInfo?>(null)
    val self: StateFlow<SelfInfo?> = _self.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /**
     * The aggregate address book: the deduped union of contacts seen on every device this
     * app has connected to. Persisted, so it survives disconnects/restarts and can be pushed
     * onto a freshly-connected device. Unlike [contacts], it is NOT cleared on disconnect.
     */
    private val _allContacts = MutableStateFlow<List<Contact>>(emptyList())
    val allContacts: StateFlow<List<Contact>> = _allContacts.asStateFlow()

    /** Which contact types the user wants pushed onto the connected device (default: chat only). */
    private val _pushTypes = MutableStateFlow(contactPrefs?.pushTypes ?: setOf(ContactType.CHAT))
    val pushTypes: StateFlow<Set<Int>> = _pushTypes.asStateFlow()
    fun setPushTypes(types: Set<Int>) { _pushTypes.value = types; contactPrefs?.pushTypes = types }

    /** Emitted after a "send all contacts to device" push completes, carrying the count sent. */
    private val _contactPushResult = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val contactPushResult: SharedFlow<Int> = _contactPushResult.asSharedFlow()

    private val _channels = MutableStateFlow<List<ChannelEntry>>(emptyList())
    val channels: StateFlow<List<ChannelEntry>> = _channels.asStateFlow()

    /**
     * Channels we listen with in observed regions — see [ObservedChannel]. Unlike [channels] these
     * are local, unslotted and outlive any connection, so they are NOT cleared on disconnect: with no
     * companion attached at all they are the only thing that makes a region's traffic decodable.
     * Kept flat like [allContacts]; screens filter down to the region they show.
     */
    private val _observedChannels = MutableStateFlow<List<ObservedChannel>>(emptyList())
    val observedChannels: StateFlow<List<ObservedChannel>> = _observedChannels.asStateFlow()

    /** Follow [items] (name → key) in [region], skipping keys already followed there. Returns how many were new. */
    fun addObservedChannels(region: String, items: List<Pair<String, ByteArray>>): Int {
        val cur = _observedChannels.value
        val add = items
            .mapNotNull { (name, key) ->
                key.takeIf { it.size >= 16 }?.let { ObservedChannel(region, name.trim(), it.copyOf(16)) }
            }
            .distinct() // identity is region+key, so this also collapses dupes within the batch
            .filter { it !in cur }
        if (add.isEmpty()) return 0
        _observedChannels.value = cur + add
        observedChannelStore?.save(_observedChannels.value)
        return add.size
    }

    /**
     * Stop following [channel]. Its collected history is deliberately left in place — re-adding the
     * same key in the same region resolves to the same conversation id and picks it back up.
     */
    fun removeObservedChannel(channel: ObservedChannel) {
        val next = _observedChannels.value.filter { it != channel }
        if (next.size == _observedChannels.value.size) return
        _observedChannels.value = next
        observedChannelStore?.save(next)
    }

    /** conversationId -> messages (oldest first). */
    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    /** Unread incoming-message counts per conversation id (cleared when its chat is open). */
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    /** The conversation currently on screen; its incoming messages never count as unread. */
    @Volatile private var activeConv: String? = null

    /** Mark a conversation open (or null when leaving); opening also clears its unread count. */
    fun setActiveConversation(convId: String?) {
        activeConv = convId
        if (convId != null && _unread.value.containsKey(convId)) {
            _unread.update { it - convId }
            chatStore?.saveUnread(_unread.value)
        }
    }

    // ---- instrumentation (P2) ----
    /** The app's currently-selected region (drives which per-region feed the monitor shows). */
    private val _region = MutableStateFlow(initialRegion)
    val region: StateFlow<String> = _region.asStateFlow()
    fun setRegion(r: String) {
        if (_region.value == r) return
        _region.value = r
        _heard.value = emptyList() // Heard is region-mixed; clear it so it repopulates for the new region
    }

    /**
     * Where the companion's radio is — the region its packets are attributed to. Null until chosen;
     * see [MqttPrefs.homeRegion]. Exposed as state so region-scoped screens re-filter when it changes.
     */
    private val _homeRegion = MutableStateFlow(initialHomeRegion)
    val homeRegion: StateFlow<String?> = _homeRegion.asStateFlow()

    /**
     * Re-attribute companion traffic to [h]. Stored packets keep `region = null`, so home is only ever
     * resolved at bucket time — which means everything already collected re-files itself here, and the
     * merged buckets re-sort by arrival (concatenation order would otherwise break newest-first).
     */
    fun setHomeRegion(h: String?) {
        if (_homeRegion.value == h) return
        _homeRegion.value = h
        _historyByRegion.update { m -> m.regroupBy(h, MAX_PACKET_HISTORY) }
        _heard.value = emptyList()
    }

    private fun Map<String, List<RxLog>>.regroupBy(home: String?, cap: Int): Map<String, List<RxLog>> =
        values.flatten().groupBy { regionOf(it.region, home) }
            .mapValues { (_, v) -> v.sortedByDescending { it.receivedAtMs }.take(cap) }

    /**
     * The collected RX packets, kept as **N per-region lists** (newest first, each capped) so a busy
     * region can't evict a quiet one. Persisted and restored on app start, and NOT cleared on
     * disconnect: the companion is only one of the sources, and the MQTT feed keeps collecting without it.
     *
     * [packetHistory] exposes the *currently-selected* region's packets, so the monitor and every
     * analytics tool scope to that region automatically and stay bounded however many regions are observed.
     */
    private val _historyByRegion = MutableStateFlow<Map<String, List<RxLog>>>(emptyMap())
    // The all-regions feed churns _historyByRegion on every packet, and these views (flatten+sort, counts)
    // are expensive — so sample them to ~UI_THROTTLE_MS instead of recomputing per packet, which was
    // burning CPU. Region switches still respond immediately: they re-combine against _region directly.
    @OptIn(FlowPreview::class)
    val packetHistory: StateFlow<List<RxLog>> =
        combine(_historyByRegion.sample(UI_THROTTLE_MS), _region) { m, r ->
            // "All" merges every region's bucket (newest-first, bounded); otherwise show just that region's.
            if (r == ALL_REGIONS) m.values.flatten().sortedByDescending { it.receivedAtMs }.take(MAX_PACKET_HISTORY)
            else m[r] ?: emptyList()
        }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Packet count per region across everything collected — spans all regions regardless of the
     * selected view, so the Top Regions chart is meaningful even in a single-region view. Bounded by
     * the per-region history cap. Keys are resolved regions (concrete code, home, or [HOME_UNSET]).
     */
    @OptIn(FlowPreview::class)
    val regionCounts: StateFlow<Map<String, Int>> =
        _historyByRegion.sample(UI_THROTTLE_MS).map { m -> m.mapValues { it.value.size } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun addToHistory(log: RxLog) {
        val r = regionOf(log.region, _homeRegion.value)
        _historyByRegion.update { m -> m + (r to (listOf(log) + (m[r] ?: emptyList())).take(MAX_PACKET_HISTORY)) }
    }

    private val _radioStats = MutableStateFlow<RadioStats?>(null)
    val radioStats: StateFlow<RadioStats?> = _radioStats.asStateFlow()

    private val _coreStats = MutableStateFlow<CoreStats?>(null)
    val coreStats: StateFlow<CoreStats?> = _coreStats.asStateFlow()

    private val _packetStats = MutableStateFlow<PacketStats?>(null)
    val packetStats: StateFlow<PacketStats?> = _packetStats.asStateFlow()

    /** Rolling noise-floor history (dBm), oldest first, capped — for the noise graph. */
    private val _noiseHistory = MutableStateFlow<List<Int>>(emptyList())
    val noiseHistory: StateFlow<List<Int>> = _noiseHistory.asStateFlow()

    /** Recently-heard stations (most-recent first). Sampled so an advert flood can't recompose per packet. */
    private val _heard = MutableStateFlow<List<HeardEntry>>(emptyList())
    @OptIn(FlowPreview::class)
    val heard: StateFlow<List<HeardEntry>> =
        _heard.sample(UI_THROTTLE_MS).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Received raw custom-payload packets (PUSH_CODE_RAW_DATA), newest first, capped. */
    private val _rawData = MutableStateFlow<List<RawDataFrame>>(emptyList())
    val rawData: StateFlow<List<RawDataFrame>> = _rawData.asStateFlow()

    /** Managed repeater/room sessions, keyed by the node's 6-byte key-prefix hex. */
    private val _repeaters = MutableStateFlow<Map<String, RepeaterSession>>(emptyMap())
    val repeaters: StateFlow<Map<String, RepeaterSession>> = _repeaters.asStateFlow()

    /** Nodes that answered the last blind discovery request (Tools → Discover). */
    private val _discovered = MutableStateFlow<List<DiscoveredNode>>(emptyList())
    val discovered: StateFlow<List<DiscoveredNode>> = _discovered.asStateFlow()
    /** Tag of the in-flight discovery request, to match replies to it. */
    private var discoverTag = 0L

    // ---- settings (config that SELF_INFO doesn't return is fetched on connect) ----
    private val _tuning = MutableStateFlow<TuningParams?>(null)
    val tuning: StateFlow<TuningParams?> = _tuning.asStateFlow()

    private val _autoAdd = MutableStateFlow<AutoAddConfig?>(null)
    val autoAdd: StateFlow<AutoAddConfig?> = _autoAdd.asStateFlow()

    /** Frequencies (kHz ranges) on which the firmware permits client-repeat (Settings → Radio). */
    private val _allowedRepeatFreqs = MutableStateFlow<List<LongRange>>(emptyList())
    val allowedRepeatFreqs: StateFlow<List<LongRange>> = _allowedRepeatFreqs.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    /** The device's custom variables ("sensor settings"), as name→value pairs. */
    private val _customVars = MutableStateFlow<List<CustomVar>>(emptyList())
    val customVars: StateFlow<List<CustomVar>> = _customVars.asStateFlow()

    private val _battStorage = MutableStateFlow<BattAndStorage?>(null)
    val battStorage: StateFlow<BattAndStorage?> = _battStorage.asStateFlow()

    /**
     * Device clock offset in ms (device epoch − phone epoch) at the last GET_DEVICE_TIME reply.
     * Add it to the phone's current time to render a live device clock; null until first read.
     */
    private val _deviceTimeOffsetMs = MutableStateFlow<Long?>(null)
    val deviceTimeOffsetMs: StateFlow<Long?> = _deviceTimeOffsetMs.asStateFlow()

    /** Rolling in-app debug log (newest last, capped) for the Debug Logs viewer. */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** This node's own telemetry (LPP readings), for the "My telemetry" view. */
    private val _telemetry = MutableStateFlow<List<Lpp.Reading>>(emptyList())
    val telemetry: StateFlow<List<Lpp.Reading>> = _telemetry.asStateFlow()

    /** Remote contacts' telemetry, keyed by the contact's 6-byte key-prefix hex. */
    private val _contactTelemetry = MutableStateFlow<Map<String, List<Lpp.Reading>>>(emptyMap())
    val contactTelemetry: StateFlow<Map<String, List<Lpp.Reading>>> = _contactTelemetry.asStateFlow()

    /** Remote contacts' telemetry min/max/avg over a window (GET_MMA), keyed by key-prefix hex. */
    private val _contactMma = MutableStateFlow<Map<String, List<MmaReading>>>(emptyMap())
    val contactMma: StateFlow<Map<String, List<MmaReading>>> = _contactMma.asStateFlow()

    /** Latest trace-path result (Tools → Trace path). */
    private val _traceResult = MutableStateFlow<TraceResult?>(null)
    val traceResult: StateFlow<TraceResult?> = _traceResult.asStateFlow()

    /** Discovered routes per node, keyed by 6-byte key-prefix hex (from "Get path to node"). */
    private val _pathDiscovery = MutableStateFlow<Map<String, PathDiscoveryResult>>(emptyMap())
    val pathDiscovery: StateFlow<Map<String, PathDiscoveryResult>> = _pathDiscovery.asStateFlow()

    /**
     * Cached advert paths per contact, keyed by 6-byte key-prefix hex (from GET_ADVERT_PATH).
     * A present null value means "queried, none stored on the device".
     */
    private val _advertPaths = MutableStateFlow<Map<String, AdvertPathInfo?>>(emptyMap())
    val advertPaths: StateFlow<Map<String, AdvertPathInfo?>> = _advertPaths.asStateFlow()
    /** Key-prefix hex of the in-flight GET_ADVERT_PATH (its reply carries no key to match on). */
    @Volatile private var pendingAdvertPathKey: String? = null

    /** Emitted after each settings write (OK/ERR) for UI feedback. */
    private val _settingsResult = MutableSharedFlow<SettingsResult>(extraBufferCapacity = 8)
    val settingsResult: SharedFlow<SettingsResult> = _settingsResult.asSharedFlow()

    /** Emitted (as hex) when an exported contact "card" arrives, for the share/copy dialog. */
    private val _exportedContact = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val exportedContact: SharedFlow<String> = _exportedContact.asSharedFlow()

    /** Emitted (key-prefix hex) when a contact is imported, so the UI can scroll to it. */
    private val _importedContact = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val importedContact: SharedFlow<String> = _importedContact.asSharedFlow()

    /** Emitted when a private-key export completes (the identity, or Unsupported). */
    private val _privateKeyExport = MutableSharedFlow<PrivateKeyExport>(extraBufferCapacity = 4)
    val privateKeyExport: SharedFlow<PrivateKeyExport> = _privateKeyExport.asSharedFlow()
    /** True while a CMD_EXPORT_PRIVATE_KEY is in flight (its reply/DISABLED carries no tag). */
    @Volatile private var pendingKeyExport = false

    /** Emitted for each freshly-received incoming message — drives background notifications. */
    private val _incomingMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 16)
    val incomingMessages: SharedFlow<ChatMessage> = _incomingMessages.asSharedFlow()

    /** Labels of settings writes awaiting their OK/ERR reply (FIFO; link is sequential). */
    private val pendingSettings = ArrayDeque<String>()

    private var statsJob: Job? = null
    /** Signal from the most recent ADVERT-type RX packet, to attach to the next advert push. */
    private var lastAdvertSnrQ: Int? = null
    private var lastAdvertRssi: Int? = null

    // --- internal sync/send bookkeeping ---
    private val contactAccumulator = mutableListOf<Contact>()
    private val channelAccumulator = mutableListOf<ChannelEntry>()
    /** True once CONTACTS_START was seen for the current sync (so END can update even when empty). */
    private var sawContactsStart = false
    /** Public key of an in-flight GET_CONTACT_BY_KEY, so its lone reply is matched precisely. */
    @Volatile private var pendingByKeyContact: ByteArray? = null
    private var enumeratingChannels = false
    private var draining = false
    private var localIdSeq = 0L
    private var saveJob: Job? = null
    private var packetSaveJob: Job? = null
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
        chatStore?.loadUnread()?.let { if (it.isNotEmpty()) _unread.value = it }
        contactStore?.load()?.let { if (it.isNotEmpty()) _allContacts.value = it }
        observedChannelStore?.load()?.let { if (it.isNotEmpty()) _observedChannels.value = it }
        packetStore?.load()?.let { loaded ->
            if (loaded.isNotEmpty()) _historyByRegion.value =
                loaded.groupBy { regionOf(it.region, initialHomeRegion) }.mapValues { it.value.take(MAX_PACKET_HISTORY) }
        }
        scope.launch { link.incoming.collect(::onFrame) }
    }

    /** Call once the link reports Connected. */
    fun start() {
        scope.launch { link.send(Requests.appStart()) }
        // Pre-fetch config that SELF_INFO doesn't carry, so the settings form pre-fills.
        scope.launch { link.send(Requests.getTuningParams()) }
        scope.launch { link.send(Requests.getAutoAddConfig()) }
        scope.launch { link.send(Requests.getAllowedRepeatFreq()) }
        scope.launch { link.send(Requests.deviceQuery()) }
        scope.launch { link.send(Requests.getBattAndStorage()) }
        scope.launch { link.send(Requests.selfTelemetry()) }
        scope.launch { link.send(Requests.getCustomVars()) }
        scope.launch { link.send(Requests.getDeviceTime()) }
        startStatsPolling()
    }

    /** Clear the collected RX packets — the monitor feed and the traffic analytics are the same store. */
    fun clearPacketHistory() {
        packetSaveJob?.cancel(); packetSaveJob = null
        _historyByRegion.value = emptyMap()
        packetStore?.save(emptyList())
    }

    /** Re-request this node's own telemetry. */
    fun refreshTelemetry() = scope.launch { runCatching { link.send(Requests.selfTelemetry()) } }.let {}

    /**
     * Inject an externally-sourced RX packet (e.g. from the meshcore.ca MQTT feed) into the live
     * packet feed + persisted history, so the packet monitor and analytics include it. Works with
     * or without a BLE link (the session outlives any single connection).
     */
    fun injectPacket(log: RxLog) {
        addToHistory(log)
        schedulePacketSave()
        // An observed ADVERT is a station heard on the air — create/refresh a regional contact and
        // feed the Heard list, whether it arrived over MQTT or the companion's own radio.
        if (log.payloadType == PayloadType.ADVERT) ingestAdvert(log)
        // The same logic for channel chatter: an observed GRP_TXT is a channel message heard on the air.
        else if (log.payloadType == PayloadType.GRP_TXT) ingestGroupText(log)
    }

    /**
     * Decode an observed GRP_TXT into its region's channel history, using the keys we follow there
     * ([observedChannels]). The counterpart of [ingestAdvert] for chat rather than stations.
     *
     * Only MQTT-sourced packets are ingested. Traffic the companion hears is already delivered
     * decrypted as a proper channel message (see [storeIncoming]); ingesting the raw LOG_RX_DATA copy
     * of it as well would store every message twice.
     */
    private fun ingestGroupText(log: RxLog) {
        val region = log.region ?: return
        val p = PacketInspector.parse(log.raw)
        val hash = p.channelHash ?: return
        if (p.payload.size < 2) return
        val body = p.payload.copyOfRange(1, p.payload.size) // after the channel-hash byte
        for (ch in _observedChannels.value) {
            // The hash is one byte, so it only narrows the candidates — the MAC check in decrypt is
            // what actually identifies the channel.
            if (ch.region != region || GroupCipher.channelHash(ch.secret) != hash) continue
            val plain = GroupCipher.decrypt(ch.secret, body) ?: continue
            val txt = GroupCipher.parseGroupText(plain) ?: continue
            storeObserved(ch, txt)
            return
        }
    }

    /**
     * Store an observed channel message under the channel's region-scoped conversation.
     *
     * Deduped on (timestamp, text): a flooded message reaches the broker via every node that relayed
     * it, so the same message arrives many times over.
     *
     * Unlike [storeIncoming] this deliberately does NOT emit to [incomingMessages] — that drives phone
     * notifications, and we are observing other people's regions, not corresponding on them.
     *
     * No SNR is recorded either: our radio never heard this. The figure the broker carries belongs to
     * whichever node relayed it, and showing that as the message's signal would be a plain lie.
     */
    private fun storeObserved(ch: ObservedChannel, txt: GroupCipher.GroupText) {
        val convId = ch.conversationId
        val existing = _messages.value[convId].orEmpty()
        if (existing.any { it.incoming && it.timestampSecs == txt.timestamp && it.text == txt.text }) return
        appendMessage(
            ChatMessage(
                localId = ++localIdSeq,
                conversationId = convId,
                text = txt.text,
                timestampSecs = txt.timestamp,
                incoming = true,
                status = MsgStatus.Received,
            )
        )
        if (convId != activeConv) {
            _unread.update { it + (convId to ((it[convId] ?: 0) + 1)) }
            chatStore?.saveUnread(_unread.value)
        }
    }

    /**
     * Decode an observed ADVERT (from MQTT or the companion) into a regional contact + a Heard entry.
     * Adverts carry the full 32-byte public key (and optionally name/type/location) in the clear, so
     * every observed node becomes a contact tagged with the region it was heard in (null over BLE = home).
     */
    private fun ingestAdvert(log: RxLog) {
        val p = PacketInspector.parse(log.raw)
        val key = p.advertPubKey?.takeIf { it.size >= 32 }?.copyOf(32) ?: return
        val hex = key.toHex()
        upsertObservedContact(
            Contact(
                publicKey = key,
                type = p.advertType ?: ContactType.CHAT,
                flags = 0,
                outPathLen = 0xFF, // path unknown until learned
                outPath = ByteArray(64),
                name = p.advertName?.trim().orEmpty(),
                lastAdvert = p.advertTimestamp ?: (log.receivedAtMs / 1000),
                gpsLat = p.advertLat ?: 0,
                gpsLon = p.advertLon ?: 0,
                lastMod = log.receivedAtMs / 1000,
                region = log.region,
            )
        )
        val known = _contacts.value.firstOrNull { it.publicKey.contentEquals(key) }
            ?: _allContacts.value.firstOrNull { it.publicKey.contentEquals(key) }
        val name = p.advertName?.takeIf { it.isNotBlank() } ?: known?.name ?: hex.take(12)
        upsertHeard(
            HeardEntry(
                pubKeyHex = hex,
                name = name,
                type = p.advertType ?: known?.type ?: 0,
                lastHeardMs = log.receivedAtMs,
                snrQ = log.snrQ, rssi = log.rssi,
                gpsLat = p.advertLat ?: known?.gpsLat ?: 0,
                gpsLon = p.advertLon ?: known?.gpsLon ?: 0,
            )
        )
    }

    /**
     * Insert or refresh an advert-observed contact in the aggregate book. Deduped by full key; a name
     * never regresses to blank and a known region is kept. Disk saves are debounced so a busy advert
     * stream doesn't thrash storage. Only writes when a stored field actually changed.
     */
    private fun upsertObservedContact(c: Contact) {
        val list = _allContacts.value
        val idx = list.indexOfFirst { it.publicKey.contentEquals(c.publicKey) }
        val merged = if (idx < 0) c else list[idx].let { ex ->
            ex.copy(
                name = c.name.ifBlank { ex.name },
                type = if (c.type != ContactType.NONE) c.type else ex.type,
                lastAdvert = maxOf(c.lastAdvert, ex.lastAdvert),
                gpsLat = if (c.gpsLat != 0 || c.gpsLon != 0) c.gpsLat else ex.gpsLat,
                gpsLon = if (c.gpsLat != 0 || c.gpsLon != 0) c.gpsLon else ex.gpsLon,
                region = c.region ?: ex.region,
                lastMod = maxOf(c.lastMod, ex.lastMod),
            )
        }
        if (idx >= 0) {
            val ex = list[idx]
            // Skip re-adverts that only bump recency — updating the whole book on every advert would
            // thrash storage and recomposition. Only a material change (name/type/gps/region) emits.
            val material = merged.name != ex.name || merged.type != ex.type || merged.gpsLat != ex.gpsLat ||
                merged.gpsLon != ex.gpsLon || merged.region != ex.region || merged.outPathLen != ex.outPathLen
            if (!material) return
        }
        _allContacts.value = if (idx < 0) list + merged else list.toMutableList().also { it[idx] = merged }
        scheduleContactSave()
        // Reflect a newly-learned region on the device-contacts view too, if this node is on the device.
        if (merged.region != null) {
            _contacts.update { l -> l.map { if (it.publicKey.contentEquals(c.publicKey) && it.region != merged.region) it.copy(region = merged.region) else it } }
        }
    }

    private var contactSaveJob: Job? = null
    private fun scheduleContactSave() {
        if (contactSaveJob?.isActive == true) return
        contactSaveJob = scope.launch { delay(3000); runCatching { contactStore?.save(_allContacts.value) } }
    }

    /**
     * Request a remote contact's telemetry. The device replies SENT immediately; the
     * readings arrive later (or not, if unreachable) and land in [contactTelemetry]
     * keyed by the contact's 6-byte key-prefix hex.
     */
    fun requestTelemetry(contact: Contact) =
        scope.launch { runCatching { link.send(Requests.requestTelemetry(contact.publicKey)) } }.let {}

    /**
     * Ask the device to discover the route to [pubKey] (the node's full 32-byte public key).
     * The device replies SENT immediately; the route arrives later (or not, if unreachable) via
     * PUSH_CODE_PATH_DISCOVERY_RESPONSE and lands in [pathDiscovery] keyed by 6-byte prefix hex.
     */
    fun discoverPath(pubKey: ByteArray) {
        if (pubKey.size < 32) return
        dbg("tx pathDiscovery key=${pubKey.copyOf(6).toHex()}")
        scope.launch {
            runCatching { link.send(Requests.sendPathDiscoveryReq(pubKey)) }
                .onFailure { dbg("tx pathDiscovery FAILED: ${it.message}") }
        }
    }

    /**
     * Ask for the device's cached advert path to [contact] — the route its last advert took to
     * reach us, and when. Instant local lookup; the reply lands in [advertPaths] keyed by the
     * contact's 6-byte key-prefix hex (a null entry means the device has no path stored).
     */
    fun requestAdvertPath(contact: Contact) {
        val hex = contact.keyPrefixHex
        pendingAdvertPathKey = hex
        scope.launch {
            runCatching { link.send(Requests.getAdvertPath(contact.publicKey)) }
            // A "not found" comes back as a generic ERR (no key); clear the pending marker after a
            // short grace period so a stale request can't misattribute a later reply.
            delay(3_000)
            if (pendingAdvertPathKey == hex) {
                pendingAdvertPathKey = null
                // Record "none" if nothing arrived, so the UI can distinguish loading from empty.
                if (!_advertPaths.value.containsKey(hex)) _advertPaths.update { it + (hex to null) }
            }
        }
    }

    /** Trace a path through the given ordered hop hashes (each = a node's public-key prefix byte). */
    fun sendTrace(path: ByteArray) {
        if (path.isEmpty()) return
        _traceResult.value = null
        val tag = System.nanoTime() and 0xFFFFFFFFL
        scope.launch { runCatching { link.send(Requests.sendTracePath(tag, path)) } }
    }

    /** Discard the last trace result (e.g. to start a new trace). */
    fun clearTrace() { _traceResult.value = null }

    /**
     * Send a blind, zero-hop discovery request. Every direct neighbour whose type is in
     * [typeFilter] (a bitmask of `1 shl ContactType.*`) replies; replies land in [discovered].
     */
    fun discoverNodes(typeFilter: Int) {
        val tag = Random.nextLong(1, 0x1_0000_0000L)
        discoverTag = tag
        // Don't clear results — they accumulate across requests until the user clears them.
        // Ask for full public keys (not just prefixes) so a discovered node can be added as a contact.
        scope.launch { runCatching { link.send(Requests.nodeDiscoverReq(typeFilter, tag, prefixOnly = false)) } }
    }

    /** Empty the discovered-nodes list (Tools → Discover → Clear). */
    fun clearDiscovered() { _discovered.value = emptyList() }

    // ---- repeater / room management -------------------------------------

    private fun updateRepeater(prefixHex: String, f: (RepeaterSession) -> RepeaterSession) {
        _repeaters.update { it + (prefixHex to f(it[prefixHex] ?: RepeaterSession())) }
    }

    /** Passwords of in-flight login attempts; persisted only once the node accepts them. */
    private val pendingLoginPw = mutableMapOf<String, String>()

    /** Log in to a repeater/room with [password]; result arrives as a LOGIN_SUCCESS/FAIL push. */
    fun loginRepeater(contact: Contact, password: String) {
        val hex = contact.keyPrefixHex
        pendingLoginPw[hex] = password
        updateRepeater(hex) { it.copy(login = RepeaterLogin.LoggingIn) }
        scope.launch { runCatching { link.send(Requests.sendLogin(contact.publicKey, password)) } }
        // A wrong password usually gets no reply at all (the node won't confirm it), and even a
        // success can be lost over the mesh — so fail the attempt if nothing arrives in time.
        scope.launch {
            delay(20_000)
            if (_repeaters.value[hex]?.login == RepeaterLogin.LoggingIn) {
                pendingLoginPw.remove(hex)
                updateRepeater(hex) { it.copy(login = RepeaterLogin.Failed) }
            }
        }
    }

    /** The password this node last accepted (remembered across restarts), or null. */
    fun savedPassword(contact: Contact): String? = adminPrefs?.password(contact.keyPrefixHex)

    /**
     * Silently log in with the remembered password, if we have one and no session is already
     * up (or in progress). Call when a repeater screen / room chat opens; a no-op otherwise.
     */
    fun autoLoginIfSaved(contact: Contact) {
        val login = _repeaters.value[contact.keyPrefixHex]?.login ?: RepeaterLogin.None
        if (login == RepeaterLogin.LoggedIn || login == RepeaterLogin.LoggingIn) return
        savedPassword(contact)?.let { loginRepeater(contact, it) }
    }

    /** Drop this node's remembered password (it stays logged in until logout/expiry). */
    fun forgetPassword(contact: Contact) {
        adminPrefs?.forget(contact.keyPrefixHex)
    }

    /** Request a repeater/room's status; the reply lands in [repeaters] as its stats. */
    fun requestRepeaterStatus(contact: Contact) {
        scope.launch { runCatching { link.send(Requests.sendStatusRequest(contact.publicKey)) } }
    }

    /** End the session with a repeater/room. */
    fun logoutRepeater(contact: Contact) {
        scope.launch { runCatching { link.send(Requests.logout(contact.publicKey)) } }
        updateRepeater(contact.keyPrefixHex) { it.copy(login = RepeaterLogin.None) }
    }

    private enum class BinKind { Neighbours, Acl, Owner, Mma, AnonOwner, AnonClock }
    /** The in-flight binary request (key-prefix hex + kind); only one at a time. */
    private var pendingBinaryReq: Pair<String, BinKind>? = null
    /** Key-prefix hex of an in-flight HAS_CONNECTION check (its OK/ERR reply carries no key). */
    @Volatile private var pendingConnectionCheck: String? = null

    /**
     * Request a contact's telemetry min/max/avg over the last [windowSecs] (GET_MMA). The reply
     * lands in [contactMma] keyed by the contact's 6-byte key-prefix hex. Needs a read-only
     * session with the node (sensors/repeaters/rooms).
     */
    fun requestContactMma(contact: Contact, windowSecs: Long = 24 * 3600) {
        pendingBinaryReq = contact.keyPrefixHex to BinKind.Mma
        scope.launch { runCatching { link.send(Requests.requestMma(contact.publicKey, windowSecs, 0)) } }
    }

    /**
     * Ask the device whether it still has an active login session with [contact]. The OK/ERR reply
     * updates that node's [RepeaterSession.sessionActive]. Skipped if another OK/ERR-eliciting
     * command is in flight (the reply carries no tag to correlate on).
     */
    fun checkConnection(contact: Contact) {
        if (pendingSettings.isNotEmpty() || pendingSends.isNotEmpty() || pendingConnectionCheck != null) return
        val hex = contact.keyPrefixHex
        pendingConnectionCheck = hex
        scope.launch { runCatching { link.send(Requests.hasConnection(contact.publicKey)) } }
        scope.launch { delay(8_000); if (pendingConnectionCheck == hex) pendingConnectionCheck = null }
    }

    /** Anonymous (no-login) owner/name query to a repeater; result lands in [RepeaterSession.anonOwner]. */
    fun requestAnonOwner(contact: Contact) {
        pendingBinaryReq = contact.keyPrefixHex to BinKind.AnonOwner
        scope.launch { runCatching { link.send(Requests.sendAnonReq(contact.publicKey, AnonReqType.OWNER)) } }
    }

    /** Anonymous (no-login) clock query to a repeater; result lands in [RepeaterSession.anonClockSecs]. */
    fun requestAnonClock(contact: Contact) {
        pendingBinaryReq = contact.keyPrefixHex to BinKind.AnonClock
        scope.launch { runCatching { link.send(Requests.sendAnonReq(contact.publicKey, AnonReqType.CLOCK)) } }
    }

    /** Ask a repeater for its neighbour table; the result lands in [repeaters] as its neighbours. */
    fun requestRepeaterNeighbours(contact: Contact) {
        pendingBinaryReq = contact.keyPrefixHex to BinKind.Neighbours
        val nonce = Random.nextLong(1, 0x1_0000_0000L)
        scope.launch { runCatching { link.send(Requests.requestNeighbours(contact.publicKey, nonce)) } }
    }

    /** Keep a logged-in repeater/room session alive (fire-and-forget; no state change). */
    fun keepAliveRepeater(contact: Contact) {
        scope.launch { runCatching { link.send(Requests.keepAlive(contact.publicKey)) } }
    }

    /** Ask a repeater/room for its access-control list (admin only); lands in [repeaters] as its acl. */
    fun requestRepeaterAcl(contact: Contact) {
        pendingBinaryReq = contact.keyPrefixHex to BinKind.Acl
        scope.launch { runCatching { link.send(Requests.requestAcl(contact.publicKey)) } }
    }

    /** Ask a node for its owner info (firmware version, node name, owner); lands in [repeaters]. */
    fun requestRepeaterOwnerInfo(contact: Contact) {
        pendingBinaryReq = contact.keyPrefixHex to BinKind.Owner
        scope.launch { runCatching { link.send(Requests.requestOwnerInfo(contact.publicKey)) } }
    }

    /** Send a CLI/admin command to a logged-in repeater/room; replies append to its console. */
    fun sendRepeaterCommand(contact: Contact, command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        updateRepeater(contact.keyPrefixHex) { it.copy(console = (it.console + "> $cmd").takeLast(200)) }
        val ts = System.currentTimeMillis() / 1000
        scope.launch {
            runCatching { link.send(Requests.sendRepeaterCommand(contact.publicKey.copyOf(6), cmd, ts)) }
        }
    }

    /** Add a freshly-discovered node to contacts if we don't already have it (needs the full 32-byte key). */
    private fun addDiscoveredContact(node: DiscoveredNode) {
        if (node.pubKey.size < 32) return
        if (_contacts.value.any { it.publicKey.contentEquals(node.pubKey) }) return
        val c = Contact(
            publicKey = node.pubKey,
            type = node.type,
            flags = 0,
            outPathLen = 0, // direct neighbour — discovery is zero-hop
            outPath = ByteArray(64),
            name = "", // unknown until an advert provides it
            lastAdvert = 0,
            gpsLat = 0,
            gpsLon = 0,
            lastMod = System.currentTimeMillis() / 1000,
        )
        _contacts.update { list -> (list + c).sortedByDescending { it.lastAdvert } }
        mergeIntoAggregate(listOf(c))
        scope.launch { runCatching { link.send(Requests.addUpdateContact(c)) } }
    }

    private fun dbg(msg: String) {
        Log.d(TAG, msg)
        val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}  $msg"
        _logs.update { (it + line).takeLast(500) }
    }

    fun reset() {
        statsJob?.cancel(); statsJob = null
        _self.value = null
        _contacts.value = emptyList()
        _channels.value = emptyList()
        // NB: _messages is intentionally NOT cleared — chat history persists across disconnects.
        // Nor are the collected packets: the companion is one source among several, and dropping them
        // here would throw away everything the MQTT feed has gathered (it keeps running without BLE).
        // Flush now so the latest survives.
        packetSaveJob?.cancel(); packetSaveJob = null
        packetStore?.save(_historyByRegion.value.values.flatten())
        _radioStats.value = null
        _coreStats.value = null
        _packetStats.value = null
        _noiseHistory.value = emptyList()
        _heard.value = emptyList()
        _rawData.value = emptyList()
        _discovered.value = emptyList()
        _pathDiscovery.value = emptyMap()
        _advertPaths.value = emptyMap()
        pendingAdvertPathKey = null
        pendingKeyExport = false
        discoverTag = 0L
        _repeaters.value = emptyMap()
        pendingLoginPw.clear()
        pendingBinaryReq = null
        pendingConnectionCheck = null
        _tuning.value = null
        _autoAdd.value = null
        _allowedRepeatFreqs.value = emptyList()
        _deviceInfo.value = null
        _customVars.value = emptyList()
        _battStorage.value = null
        _deviceTimeOffsetMs.value = null
        _telemetry.value = emptyList()
        _contactTelemetry.value = emptyMap()
        _contactMma.value = emptyMap()
        _traceResult.value = null
        pendingSettings.clear()
        lastAdvertSnrQ = null; lastAdvertRssi = null
        contactAccumulator.clear()
        channelAccumulator.clear()
        pendingSends.clear()
        sawContactsStart = false
        pendingByKeyContact = null
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

    /** Re-send a previously-failed outgoing message in place (no new bubble). */
    fun resend(msg: ChatMessage) {
        if (msg.incoming) return
        updateMessage(msg.localId) { it.copy(status = MsgStatus.Sending) }
        pendingSends.addLast(msg.localId)
        val cid = msg.conversationId
        scope.launch {
            runCatching {
                if (cid.startsWith("ch:")) {
                    val idx = cid.removePrefix("ch:").toIntOrNull() ?: return@launch
                    link.send(Requests.sendChannelTextMessage(idx, msg.text, msg.timestampSecs))
                } else {
                    link.send(Requests.sendTextMessage(cid.hexToBytes(), msg.text, msg.timestampSecs))
                }
            }.onFailure { updateMessage(msg.localId) { it.copy(status = MsgStatus.Failed) } }
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

    /**
     * Set the BLE pairing PIN (0 = clear to the device default, else a 6-digit value). Result
     * surfaces on [settingsResult] as "Pairing PIN"; the device is re-queried so [deviceInfo]'s
     * PIN reflects the change.
     */
    fun applyDevicePin(pin: Long) {
        pendingSettings.addLast("Pairing PIN")
        scope.launch {
            link.send(Requests.setDevicePin(pin))
            link.send(Requests.deviceQuery()) // refresh the stored PIN shown in Device info
        }
    }

    fun syncTimeFromPhone() {
        applySetting("Time", Requests.setDeviceTime(System.currentTimeMillis() / 1000))
        refreshDeviceTime() // read it back so the displayed clock reflects the write
    }

    /** Re-read the device clock; the result lands in [deviceTimeOffsetMs]. */
    fun refreshDeviceTime() = scope.launch { runCatching { link.send(Requests.getDeviceTime()) } }.let {}

    /** Re-fetch the device's custom variables (they land in [customVars]). */
    fun refreshCustomVars() = scope.launch { runCatching { link.send(Requests.getCustomVars()) } }.let {}

    /**
     * Set one custom variable. The write elicits an OK/ERR (surfaced via [settingsResult]);
     * we then re-read the vars so [customVars] reflects what the firmware actually stored.
     */
    fun setCustomVar(name: String, value: String) {
        pendingSettings.addLast("Variable “$name”")
        scope.launch {
            link.send(Requests.setCustomVar(name, value))
            link.send(Requests.getCustomVars())
        }
    }

    // ---- extra tools ------------------------------------------------------

    fun reboot() = scope.launch { runCatching { link.send(Requests.reboot()) } }.let {}

    fun factoryReset() = scope.launch { runCatching { link.send(Requests.factoryReset()) } }.let {}

    /** Clear local app data (chat history, heard list, collected packets). Contacts re-sync on connect. */
    fun purgeLocalData() {
        _messages.value = emptyMap()
        _unread.value = emptyMap()
        chatStore?.save(emptyMap())
        chatStore?.saveUnread(emptyMap())
        _heard.value = emptyList()
        _historyByRegion.value = emptyMap()
        packetSaveJob?.cancel(); packetSaveJob = null
        packetStore?.save(emptyList())
    }

    /** Export the device settings we can read, as pretty JSON (for the Export Config tool). */
    fun exportConfigJson(): String {
        val o = JSONObject()
        _self.value?.let { s ->
            o.put("node_name", s.name)
            o.put("freq_mhz", s.freqMhz)
            o.put("bw_khz", s.bwKhz)
            o.put("sf", s.radioSf)
            o.put("cr", s.radioCr)
            o.put("tx_power_dbm", s.txPower)
            o.put("adv_lat", s.advLat)
            o.put("adv_lon", s.advLon)
            o.put("advert_loc_policy", s.advertLocPolicy)
            o.put("telemetry_base", s.telemetryModeBase)
            o.put("telemetry_loc", s.telemetryModeLoc)
            o.put("telemetry_env", s.telemetryModeEnv)
            o.put("manual_add_contacts", s.manualAddContacts)
            o.put("multi_acks", s.multiAcks)
        }
        _tuning.value?.let { o.put("rx_delay_base", it.rxDelayBase); o.put("airtime_factor", it.airtimeFactor) }
        _autoAdd.value?.let { o.put("autoadd_flags", it.flags); o.put("autoadd_max_hops", it.maxHops) }
        _deviceInfo.value?.let { o.put("client_repeat", it.clientRepeat); o.put("path_hash_mode", it.pathHashMode) }
        return o.toString(2)
    }

    /** Apply a config JSON produced by [exportConfigJson] (Import Config tool). */
    fun importConfigJson(json: String) {
        val o = JSONObject(json)
        if (o.has("node_name")) applyNodeName(o.getString("node_name"))
        if (o.has("freq_mhz") && o.has("bw_khz") && o.has("sf") && o.has("cr")) {
            applyRadio(o.getDouble("freq_mhz"), o.getDouble("bw_khz"), o.getInt("sf"), o.getInt("cr"),
                o.optBoolean("client_repeat", false))
        }
        if (o.has("tx_power_dbm")) applyTxPower(o.getInt("tx_power_dbm"))
        if (o.has("adv_lat") && o.has("adv_lon")) applyPosition(o.getInt("adv_lat"), o.getInt("adv_lon"))
        if (o.has("telemetry_base")) {
            applyOtherParams(
                o.optInt("manual_add_contacts", 0) and 1 == 1,
                o.getInt("telemetry_base"), o.optInt("telemetry_loc", 0), o.optInt("telemetry_env", 0),
                o.optInt("advert_loc_policy", 0) == 1, o.optInt("multi_acks", 0),
            )
        }
        if (o.has("rx_delay_base")) applyTuning(o.getDouble("rx_delay_base"), o.optDouble("airtime_factor", 0.0))
        if (o.has("autoadd_flags")) applyAutoAdd(o.getInt("autoadd_flags"), o.optInt("autoadd_max_hops", 0))
        if (o.has("path_hash_mode")) applyPathHashMode(o.getInt("path_hash_mode"))
    }

    /** Export the app's own data (contacts + chat history) as JSON (Export App Database). */
    fun exportAppDataJson(): String {
        val root = JSONObject()
        val contactsArr = JSONArray()
        _contacts.value.forEach { c ->
            contactsArr.put(JSONObject().apply {
                put("name", c.name); put("type", c.type); put("pubkey", c.publicKey.toHex())
                put("gps_lat", c.gpsLat); put("gps_lon", c.gpsLon)
            })
        }
        root.put("contacts", contactsArr)
        val chats = JSONObject()
        _messages.value.forEach { (conv, list) ->
            val arr = JSONArray()
            list.forEach { m ->
                arr.put(JSONObject().apply {
                    put("text", m.text); put("ts", m.timestampSecs); put("in", m.incoming); put("status", m.status.name)
                })
            }
            chats.put(conv, arr)
        }
        root.put("chats", chats)
        return root.toString(2)
    }

    // ---- contact management ----------------------------------------------

    /**
     * Fold [incoming] contacts into the persistent aggregate address book. Dedup is by full
     * public key; when a key is already known we pick the better record: a contact that carries
     * a friendly name always wins over a nameless one for the same key (a name never regresses
     * to blank), and only when both are named-or-both-blank do we fall back to the newer
     * [Contact.lastMod]. Persists if anything changed.
     */
    private fun mergeIntoAggregate(incoming: List<Contact>) {
        if (incoming.isEmpty()) return
        val byKey = LinkedHashMap<String, Contact>()
        _allContacts.value.forEach { byKey[it.publicKey.toHex()] = it }
        var changed = false
        for (c in incoming) {
            val k = c.publicKey.toHex()
            val existing = byKey[k]
            if (existing == null) {
                byKey[k] = c
                changed = true
            } else {
                val cHasName = c.name.isNotBlank()
                val exHasName = existing.name.isNotBlank()
                val picked = when {
                    cHasName && !exHasName -> c        // a name replaces a nameless entry
                    !cHasName && exHasName -> existing // never let a name regress to blank
                    else -> if (c.lastMod >= existing.lastMod) c else existing // both named/both blank
                }
                // Region, once known, never regresses to null (prefer the incoming, else keep existing).
                val merged = picked.copy(region = c.region ?: existing.region)
                if (!sameContactRecord(merged, existing)) {
                    byKey[k] = merged
                    changed = true
                }
            }
        }
        if (changed) {
            val list = byKey.values.sortedBy { (it.name.ifBlank { it.keyPrefixHex }).lowercase() }
            _allContacts.value = list
            contactStore?.save(list)
        }
    }

    /** True if two contacts carry identical stored fields (Contact.equals only checks the key). */
    private fun sameContactRecord(a: Contact, b: Contact): Boolean =
        a.name == b.name && a.type == b.type && a.lastMod == b.lastMod &&
            a.lastAdvert == b.lastAdvert && a.gpsLat == b.gpsLat && a.gpsLon == b.gpsLon &&
            a.outPathLen == b.outPathLen && a.region == b.region


    /**
     * Push the entire aggregate address book onto the connected device, skipping contacts the
     * device already has (matched by public key). New contacts are added with their path reset
     * so the device re-learns routing. Emits the count sent on [contactPushResult] when done.
     */
    fun pushAllContactsToDevice(homeRegion: String) {
        val onDevice = _contacts.value
        // The book holds every observed node across regions, but a companion can only use nodes in its
        // own region — so push only the user-selected types AND contacts in [homeRegion] (region-less
        // contacts default to home). This keeps cross-region nodes off the device.
        val toPush = _allContacts.value.filter {
            it.type in _pushTypes.value && (it.region ?: homeRegion) == homeRegion
        }.filterNot { c -> onDevice.any { it.publicKey.contentEquals(c.publicKey) } }
        scope.launch {
            var sent = 0
            for (c in toPush) {
                // Path is device-specific; reset it so the target device floods until it learns one.
                val fresh = c.copy(outPathLen = 0xFF, outPath = ByteArray(64))
                runCatching { link.send(Requests.addUpdateContact(fresh)) }
                    .onSuccess { sent++ }
                    .onFailure { dbg("pushContacts failed for ${c.keyPrefixHex}: ${it.message}") }
                delay(60) // pace the writes so the link/firmware queue doesn't overflow
            }
            // Reflect the newly-added contacts locally without waiting for a full resync.
            if (sent > 0) {
                _contacts.update { list ->
                    (list + toPush).distinctBy { it.publicKey.toHex() }
                        .sortedByDescending { it.lastAdvert }
                }
            }
            _contactPushResult.tryEmit(sent)
        }
    }

    /** Drop a contact from the persistent aggregate address book (does not touch the device). */
    fun forgetAggregateContact(c: Contact) {
        val list = _allContacts.value.filterNot { it.publicKey.contentEquals(c.publicKey) }
        if (list.size != _allContacts.value.size) {
            _allContacts.value = list
            contactStore?.save(list)
        }
    }

    /** Remove a contact from the device store (optimistically drop it locally too). */
    fun removeContact(c: Contact) {
        _contacts.update { list -> list.filterNot { it.publicKey.contentEquals(c.publicKey) } }
        scope.launch { runCatching { link.send(Requests.removeContact(c.publicKey)) } }
    }

    // ---- bulk contact export / import / clear ----------------------------------

    /** The whole aggregate ("global") address book as JSON (full fields, incl. region). */
    fun exportAggregateJson(): String = ContactStore.encode(_allContacts.value)

    /** Merge a JSON contact list (from [exportAggregateJson]) into the aggregate book. Returns the count read. */
    fun importAggregateJson(json: String): Int {
        val list = ContactStore.decode(json)
        mergeIntoAggregate(list)
        return list.size
    }

    /** Wipe the aggregate address book (does not touch the connected device). */
    fun clearAggregateContacts() {
        _allContacts.value = emptyList()
        contactStore?.save(emptyList())
    }

    /** The connected device's contacts as JSON (full fields). */
    fun exportDeviceJson(): String = ContactStore.encode(_contacts.value)

    /** Add a JSON contact list onto the connected device (path reset so it re-learns). Returns the count. */
    fun importDeviceJson(json: String): Int {
        val list = ContactStore.decode(json)
        if (list.isEmpty()) return 0
        val fresh = list.map { it.copy(outPathLen = 0xFF, outPath = ByteArray(64)) }
        _contacts.update { cur -> (cur + fresh).distinctBy { it.publicKey.toHex() }.sortedByDescending { it.lastAdvert } }
        mergeIntoAggregate(fresh)
        scope.launch { for (c in fresh) { runCatching { link.send(Requests.addUpdateContact(c)) }; delay(60) } }
        return list.size
    }

    /** Remove every contact from the connected device (and locally). */
    fun clearDeviceContacts() {
        val toRemove = _contacts.value
        _contacts.value = emptyList()
        scope.launch { for (c in toRemove) { runCatching { link.send(Requests.removeContact(c.publicKey)) }; delay(40) } }
    }

    /** Re-advertise a contact zero-hop so direct neighbours can discover it. */
    fun shareContact(c: Contact) {
        scope.launch { runCatching { link.send(Requests.shareContact(c.publicKey)) } }
    }

    /** Forget a contact's learned return path; the next message re-floods. */
    fun resetPath(c: Contact) {
        _contacts.update { list ->
            list.map { if (it.publicKey.contentEquals(c.publicKey)) it.copy(outPathLen = 0xFF) else it }
        }
        scope.launch { runCatching { link.send(Requests.resetPath(c.publicKey)) } }
    }

    /** Request an exportable "card" for a contact; the result arrives on [exportedContact]. */
    fun exportContact(c: Contact) {
        scope.launch { runCatching { link.send(Requests.exportContact(c.publicKey)) } }
    }

    /**
     * Re-read a single contact's current record from the device (learned path, position, last
     * advert) without a full resync. The reply (a lone RESP_CODE_CONTACT, or ERR if the device no
     * longer has it) updates [contacts] in place via [upsertSingleContact].
     */
    fun refreshContact(c: Contact) {
        pendingByKeyContact = c.publicKey.copyOf()
        val key = pendingByKeyContact
        scope.launch {
            runCatching { link.send(Requests.getContactByKey(c.publicKey)) }
            delay(5_000); if (pendingByKeyContact === key) pendingByKeyContact = null
        }
    }

    /** Replace (or add) one contact in the live list + aggregate from a by-key fetch reply. */
    private fun upsertSingleContact(c: Contact) {
        _contacts.update { list ->
            (list.filterNot { it.publicKey.contentEquals(c.publicKey) } + c).sortedByDescending { it.lastAdvert }
        }
        mergeIntoAggregate(listOf(c))
    }

    /** Broadcast our own advert — zero-hop (direct neighbours) or flood-routed (whole mesh). */
    fun sendSelfAdvert(flood: Boolean) {
        scope.launch { runCatching { link.send(Requests.sendSelfAdvert(flood)) } }
    }

    /** Export THIS node's own advert "card"; the hex arrives on [exportedContact]. */
    fun exportSelfAdvert() {
        scope.launch { runCatching { link.send(Requests.exportContact(null)) } }
    }

    /**
     * Export this node's identity key. The 64-byte private+public blob (or [PrivateKeyExport.Unsupported]
     * if the firmware disables export) arrives on [privateKeyExport].
     */
    fun exportPrivateKey() {
        pendingKeyExport = true
        scope.launch { runCatching { link.send(Requests.exportPrivateKey()) } }
    }

    /**
     * Replace this node's identity with [identity64] (the 64-byte blob from [exportPrivateKey]).
     * Result (OK/ERR/disabled) surfaces on [settingsResult] as "Private key import". On success the
     * device reloads its contacts; call [refreshAfterIdentityChange] to re-sync our view.
     */
    fun importPrivateKey(identity64: ByteArray) {
        if (identity64.size != 64) {
            _settingsResult.tryEmit(SettingsResult("Private key import", false))
            return
        }
        applySetting("Private key import", Requests.importPrivateKey(identity64))
    }

    /** Re-run the post-connect handshake (self-info → contacts sync) after an identity change. */
    fun refreshAfterIdentityChange() {
        scope.launch { runCatching { link.send(Requests.appStart()) } }
    }

    /**
     * Import a contact from a pasted "card" (hex). The card is a self-advert packet, so we can
     * decode it locally and add the contact optimistically — no full contacts resync needed
     * (that re-downloads every contact and takes seconds). The device still persists it via
     * IMPORT_CONTACT; the next full sync reconciles any fields not carried by the advert.
     */
    fun importContact(hex: String) {
        // Accept either raw card hex or the official "meshcore://<hex>" contact URL (QR/clipboard).
        val cleaned = hex.trim().replace(" ", "").lowercase().removePrefix("meshcore://")
        val bytes = runCatching { cleaned.hexToBytes() }.getOrNull() ?: return
        if (bytes.size <= 2 + 32 + 64) return // firmware requires a full advert card
        scope.launch { runCatching { link.send(Requests.importContact(bytes)) } }

        val adv = PacketInspector.parse(bytes)
        val key = adv.advertPubKey ?: return
        val contact = Contact(
            publicKey = key,
            type = adv.advertType ?: ContactType.CHAT,
            flags = 0,
            outPathLen = 0xFF, // path unknown until learned (renders as "flood / unknown")
            outPath = ByteArray(64),
            name = adv.advertName ?: "",
            lastAdvert = adv.advertTimestamp ?: 0,
            gpsLat = adv.advertLat ?: 0,
            gpsLon = adv.advertLon ?: 0,
            lastMod = System.currentTimeMillis() / 1000,
        )
        _contacts.update { list ->
            (list.filterNot { it.publicKey.contentEquals(key) } + contact).sortedByDescending { it.lastAdvert }
        }
        mergeIntoAggregate(listOf(contact))
        _importedContact.tryEmit(contact.keyPrefixHex)
    }

    /**
     * Send a raw custom-payload packet to [contact]. Routing is direct: if we know the contact's
     * outbound path we send along it, otherwise we fall back to a zero-hop send (only reaches a
     * direct neighbour — the firmware doesn't support flooding raw data). [payload] must be at
     * least 4 bytes. The OK/ERR reply surfaces on [settingsResult] with the label "Raw data".
     */
    fun sendRawData(contact: Contact, payload: ByteArray) {
        val pathLen = if (contact.outPathLen in 0..63) contact.outPathLen else 0
        val path = if (pathLen > 0) contact.outPath.copyOf(pathLen) else ByteArray(0)
        applySetting("Raw data", Requests.sendRawData(path, payload))
    }

    // ---- channel management ----------------------------------------------

    /** Create or replace a channel slot (optimistically reflect it locally). */
    /** Restore slot 0 to the canonical MeshCore Public channel (name + well-known PSK). */
    fun restorePublicChannel() =
        setChannel(PublicChannel.INDEX, PublicChannel.NAME, PublicChannel.SECRET)

    /**
     * Add [items] (name → 16-byte secret) into free channel slots (1+, leaving Public alone).
     * Skips entries whose key already exists, and stops when slots run out. Returns count added.
     */
    fun addChannels(items: List<Pair<String, ByteArray>>): Int {
        val max = _deviceInfo.value?.maxChannels?.takeIf { it > 0 } ?: 8
        val used = _channels.value.map { it.index }.toMutableSet()
        val keys = _channels.value.mapNotNull { it.secret.takeIf { s -> s.size >= 16 }?.copyOf(16)?.toHex() }
            .toMutableSet()
        var added = 0
        for ((name, secret) in items) {
            val key = secret.copyOf(16)
            if (key.toHex() in keys) continue
            val slot = (1 until max).firstOrNull { it !in used } ?: break
            used.add(slot); keys.add(key.toHex())
            setChannel(slot, name, key)
            added++
        }
        return added
    }

    fun setChannel(index: Int, name: String, secret: ByteArray) {
        val key = secret.copyOf(16)
        val prev = _channels.value.firstOrNull { it.index == index }
        // The key identifies the channel: if this slot is becoming a *different* channel
        // (new slot, or the key changed), drop the old slot's chat history so the new
        // channel doesn't inherit the previous one's messages. A pure rename keeps history.
        val sameChannel = prev != null && prev.secret.isNotEmpty() && prev.secret.contentEquals(key)
        if (!sameChannel) {
            val cid = Conversation.channelId(index)
            if (_messages.value.containsKey(cid)) {
                _messages.update { it - cid }
                chatStore?.save(_messages.value)
            }
        }
        _channels.update { list ->
            (list.filterNot { it.index == index } + ChannelEntry(index, name, key)).sortedBy { it.index }
        }
        scope.launch { runCatching { link.send(Requests.setChannel(index, name, secret)) } }
    }

    /**
     * Delete a channel: MeshCore has no remove-channel command, so we clear the slot by
     * writing an empty name + zero key. The device then treats it as unused, and the app
     * hides blank slots. Its chat history is dropped too.
     */
    fun deleteChannel(index: Int) {
        _channels.update { list -> list.filterNot { it.index == index } }
        val cid = Conversation.channelId(index)
        if (_messages.value.containsKey(cid)) {
            _messages.update { it - cid }
            chatStore?.save(_messages.value)
        }
        scope.launch { runCatching { link.send(Requests.setChannel(index, "", ByteArray(16))) } }
    }

    private fun applySetting(label: String, frame: ByteArray) {
        pendingSettings.addLast(label)
        scope.launch { link.send(frame) }
    }

    private fun resolveSetting(ok: Boolean) {
        val label = pendingSettings.pollFirst() ?: return
        _settingsResult.tryEmit(SettingsResult(label, ok))
    }

    /** OK/ERR reply to HAS_CONNECTION: record whether the session is still active on the device. */
    private fun resolveConnectionCheck(active: Boolean) {
        val hex = pendingConnectionCheck ?: return
        pendingConnectionCheck = null
        updateRepeater(hex) {
            // A confirmed-active session upgrades login to LoggedIn; a lost one drops it to None.
            it.copy(sessionActive = active, login = if (active) RepeaterLogin.LoggedIn
            else if (it.login == RepeaterLogin.LoggedIn) RepeaterLogin.None else it.login)
        }
    }

    /** Anon owner reply payload: "node_name\nowner_info" (no firmware line, unlike GET_OWNER_INFO). */
    private fun decodeAnonOwner(payload: ByteArray): OwnerInfo {
        val parts = String(payload, Charsets.UTF_8).split('\n')
        return OwnerInfo(firmwareVersion = "", nodeName = parts.getOrElse(0) { "" }.trim(),
            owner = parts.getOrElse(1) { "" }.trim())
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
        dbg(when (f) {
            is Incoming.Raw -> "rx Raw code=0x%02X len=${f.payload.size} %s".format(f.code, f.payload.toHex())
            is Incoming.PathDiscovery ->
                "rx PathDiscovery key=${f.result.keyPrefixHex} out=${f.result.outPath} in=${f.result.inPath}"
            else -> "rx ${f::class.simpleName}"
        })
        when (f) {
            is Incoming.Self -> {
                _self.value = f.info
                scope.launch { link.send(Requests.getContacts()) }
            }
            is Incoming.ContactsStart -> {
                sawContactsStart = true
                pendingByKeyContact = null // a full sync supersedes any pending by-key refresh
                contactAccumulator.clear()
            }
            is Incoming.ContactEntry -> {
                // A lone reply to GET_CONTACT_BY_KEY matches the exact key we asked for → upsert it
                // live. Everything else is a full-sync entry and is always accumulated (the firmware
                // can stream a sync without a CONTACTS_START, so we must not gate accumulation).
                val byKey = pendingByKeyContact
                if (byKey != null && f.contact.publicKey.contentEquals(byKey)) {
                    pendingByKeyContact = null
                    upsertSingleContact(f.contact)
                } else {
                    contactAccumulator.add(f.contact)
                }
            }
            is Incoming.ContactsEnd -> {
                // Only publish if this looked like a real sync: a START was seen, or entries arrived.
                // A START-less, entry-less END must NOT clobber the existing list with an empty one.
                if (sawContactsStart || contactAccumulator.isNotEmpty()) {
                    val synced = contactAccumulator.distinctBy { it.publicKey.toHex() }
                    _contacts.value = synced.sortedByDescending { it.lastAdvert }
                    // Fold this device's contacts into the persistent cross-device address book.
                    mergeIntoAggregate(synced)
                }
                sawContactsStart = false
                contactAccumulator.clear()
                // Drop any locally-stored room history: rooms are server-owned, so we show only
                // what the room delivers this session. (Room posts arrive later, after login.)
                val rooms = roomConvIds()
                if (_messages.value.keys.any { it in rooms }) {
                    _messages.update { it - rooms }
                    scheduleSave()
                }
                startChannelEnumeration() // then drains messages when done
            }

            is Incoming.ChannelInfo -> {
                // The firmware reports every slot; a blank name means an unused/deleted slot — skip it.
                if (f.name.isNotBlank()) channelAccumulator.add(ChannelEntry(f.index, f.name, f.secret))
                _channels.value = channelAccumulator.toList()
                if (f.index + 1 < MAX_CHANNELS) {
                    scope.launch { link.send(Requests.getChannel(f.index + 1)) }
                } else {
                    finishChannelEnumeration()
                }
            }

            Incoming.MsgWaiting -> startDrain()
            is Incoming.Message -> {
                val m = f.message
                // CLI_DATA from a repeater/room is a command response, not chat — route to its console.
                if (!m.isChannel && m.txtType == TxtType.CLI_DATA) {
                    updateRepeater(m.pubKeyPrefix.toHex()) { it.copy(console = (it.console + m.text).takeLast(200)) }
                } else {
                    storeIncoming(m)
                }
                // keep draining: ask for the next queued message
                scope.launch { link.send(Requests.syncNextMessage()) }
            }
            is Incoming.LoginSuccess -> {
                val hex = f.pubKeyPrefix.toHex()
                // The node accepted the password — remember it for silent auto-login next time.
                pendingLoginPw.remove(hex)?.let { adminPrefs?.save(hex, it) }
                updateRepeater(hex) { it.copy(login = RepeaterLogin.LoggedIn, isAdmin = f.isAdmin) }
                _contacts.value.firstOrNull { it.keyPrefixHex == hex }?.let { requestRepeaterStatus(it) }
            }
            is Incoming.LoginFail -> {
                val hex = f.pubKeyPrefix.toHex()
                pendingLoginPw.remove(hex)
                updateRepeater(hex) { it.copy(login = RepeaterLogin.Failed) }
            }
            is Incoming.Status -> updateRepeater(f.pubKeyPrefix.toHex()) { it.copy(stats = f.stats) }
            is Incoming.BinaryResponse -> pendingBinaryReq?.let { (hex, kind) ->
                pendingBinaryReq = null
                when (kind) {
                    BinKind.Neighbours -> {
                        val (_, list) = Neighbours.decode(f.data, prefixLen = 6)
                        updateRepeater(hex) { it.copy(neighbours = list) }
                    }
                    BinKind.Acl -> updateRepeater(hex) { it.copy(acl = Acl.decode(f.data)) }
                    BinKind.Owner -> updateRepeater(hex) { it.copy(owner = OwnerInfo.decode(f.data)) }
                    // GET_MMA reply: [now(u32)][per-channel min/max/avg]; Mma.decode skips the 'now'.
                    BinKind.Mma -> _contactMma.update { it + (hex to Mma.decode(f.data)) }
                    // Anon replies are prefixed with the node's clock (u32); the rest is the payload.
                    BinKind.AnonOwner -> {
                        val payload = if (f.data.size > 4) f.data.copyOfRange(4, f.data.size) else ByteArray(0)
                        updateRepeater(hex) { it.copy(anonOwner = decodeAnonOwner(payload)) }
                    }
                    BinKind.AnonClock -> if (f.data.size >= 4) {
                        val clock = FrameReader(f.data).u32()
                        updateRepeater(hex) { it.copy(anonClockSecs = clock) }
                    }
                }
            }
            Incoming.NoMoreMessages -> draining = false

            is Incoming.Sent -> onSentReply(f.result.expectedAck, f.result.estTimeoutMs)
            Incoming.Ok -> when {
                pendingConnectionCheck != null -> resolveConnectionCheck(true)
                pendingSettings.isNotEmpty() -> resolveSetting(true) // settings write ack
                else -> onSendAcceptedNoAck()
            }
            is Incoming.Err -> when {
                pendingConnectionCheck != null -> resolveConnectionCheck(false)
                pendingSettings.isNotEmpty() -> resolveSetting(false)
                enumeratingChannels -> finishChannelEnumeration() // err = no more channel slots
                else -> onSendFailed()
            }
            is Incoming.PrivateKey -> if (pendingKeyExport) {
                pendingKeyExport = false
                _privateKeyExport.tryEmit(PrivateKeyExport.Ready(f.identity.toHex()))
            }
            // RESP_CODE_DISABLED: a feature the firmware wasn't built with. Correlate to an
            // in-flight key export/import; otherwise ignore (e.g. companion mode disabled).
            Incoming.Disabled -> when {
                pendingKeyExport -> { pendingKeyExport = false; _privateKeyExport.tryEmit(PrivateKeyExport.Unsupported) }
                pendingSettings.isNotEmpty() -> resolveSetting(false)
                else -> Unit
            }
            is Incoming.Tuning -> _tuning.value = f.params
            is Incoming.AutoAdd -> _autoAdd.value = f.config
            is Incoming.AllowedRepeatFreqs -> _allowedRepeatFreqs.value = f.rangesKhz
            is Incoming.Device -> _deviceInfo.value = f.info
            is Incoming.CustomVars -> _customVars.value = f.vars
            is Incoming.Battery -> _battStorage.value = f.info
            is Incoming.DeviceTime -> _deviceTimeOffsetMs.value = f.epochSecs * 1000 - System.currentTimeMillis()
            is Incoming.Telemetry -> {
                // Self telemetry (auto-requested on connect) carries our own prefix; anything
                // else is a remote contact's reply to requestTelemetry().
                val selfPrefix = _self.value?.publicKey?.copyOf(6)
                if (selfPrefix != null && selfPrefix.contentEquals(f.pubKeyPrefix)) {
                    _telemetry.value = f.readings
                } else {
                    _contactTelemetry.update { it + (f.pubKeyPrefix.toHex() to f.readings) }
                }
            }
            is Incoming.Trace -> _traceResult.value = f.result
            is Incoming.PathDiscovery -> _pathDiscovery.update { it + (f.result.keyPrefixHex to f.result) }
            is Incoming.AdvertPath -> pendingAdvertPathKey?.let { key ->
                pendingAdvertPathKey = null
                _advertPaths.update { it + (key to f.info) }
            }
            is Incoming.NodeDiscovered -> if (f.node.tag == discoverTag) {
                // Replace any prior entry for this node so its SNR refreshes; no duplicates.
                _discovered.update { list ->
                    list.filterNot { it.pubKey.contentEquals(f.node.pubKey) } + f.node
                }
                addDiscoveredContact(f.node)
            }
            is Incoming.ExportedContact -> _exportedContact.tryEmit(f.card.toHex())
            is Incoming.SendConfirmed -> markDelivered(f.ackId, f.roundTripMs)

            is Incoming.RxPacket -> {
                addToHistory(f.log)
                schedulePacketSave()
                // Remember the signal of the latest advert packet to attach to the advert push, and
                // fold the advertised node into the address book (region null over BLE = home).
                if (f.log.payloadType == PayloadType.ADVERT) {
                    lastAdvertSnrQ = f.log.snrQ; lastAdvertRssi = f.log.rssi
                    ingestAdvert(f.log)
                }
            }
            is Incoming.RawData -> _rawData.update { (listOf(f.frame) + it).take(MAX_PACKETS) }
            is Incoming.AdvertHeard -> upsertHeardByKey(f.publicKey)
            is Incoming.NewAdvert -> {
                // fold the new node into the contact list, and persist it on the device
                // so discovered nodes survive (esp. in manual-add mode).
                val isNewToUs = _contacts.value.none { it.publicKey.contentEquals(f.contact.publicKey) }
                _contacts.update { list ->
                    if (isNewToUs) (list + f.contact).sortedByDescending { it.lastAdvert } else list
                }
                if (isNewToUs) scope.launch { runCatching { link.send(Requests.addUpdateContact(f.contact)) } }
                mergeIntoAggregate(listOf(f.contact))
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
        val msg = ChatMessage(
            localId = ++localIdSeq,
            conversationId = convId,
            text = m.text,
            timestampSecs = m.senderTimestamp,
            incoming = true,
            status = MsgStatus.Received,
            snrDb = if (m.snrQ != 0) m.snrDb else null,
            authorPrefix = m.signerPrefix.takeIf { it.isNotEmpty() }?.toHex(),
        )
        appendMessage(msg)
        if (convId != activeConv) {
            _unread.update { it + (convId to ((it[convId] ?: 0) + 1)) }
            chatStore?.saveUnread(_unread.value)
        }
        _incomingMessages.tryEmit(msg)
    }

    private fun onSentReply(expectedAck: Long, estTimeoutMs: Long) {
        val localId = pendingSends.pollFirst() ?: return
        dbg("SENT localId=$localId expectedAck=$expectedAck estTimeout=$estTimeoutMs")
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
        dbg("CONFIRMED ackId=$ackId trip=${roundTripMs}ms matched=$matched")
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

    /** Conversation ids that belong to room servers — the room owns that history, so we
     *  never persist it locally (it is re-synced from the room on each connection). */
    private fun roomConvIds(): Set<String> =
        _contacts.value.filter { it.type == ContactType.ROOM }.map { Conversation.dmId(it) }.toSet()

    /** Persist chat history shortly after a change (debounced to coalesce bursts). */
    private fun scheduleSave() {
        val store = chatStore ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val rooms = roomConvIds()
            store.save(_messages.value.filterKeys { it !in rooms })
        }
    }

    /**
     * Persist the packet history at most ~once per 20s while packets keep arriving (a leading
     * throttle: the first packet in a burst schedules the save; later packets don't reschedule),
     * so a fast RX stream doesn't rewrite the file constantly.
     */
    private fun schedulePacketSave() {
        val store = packetStore ?: return
        if (packetSaveJob?.isActive == true) return
        packetSaveJob = scope.launch {
            delay(20_000)
            store.save(_historyByRegion.value.values.flatten())
        }
    }
}
