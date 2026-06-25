package org.thepacket.meshcore.protocol

/**
 * A decoded frame from the firmware. [FrameDecoder.decode] turns raw NUS notify
 * bytes into one of these. Frames we don't yet fully parse surface as [Raw] with
 * their code + payload preserved, so nothing is silently dropped.
 */
sealed interface Incoming {
    /** Generic ack (RESP_CODE_OK). */
    data object Ok : Incoming

    /** Error reply (RESP_CODE_ERR); [code] is the firmware error byte if present. */
    data class Err(val code: Int) : Incoming

    /** RESP_CODE_DISABLED — feature off (e.g. BLE companion mode disabled). */
    data object Disabled : Incoming

    data class Self(val info: SelfInfo) : Incoming

    /** One contact during a GET_CONTACTS sync (RESP_CODE_CONTACT). */
    data class ContactEntry(val contact: Contact) : Incoming
    /** Start of a contacts sync; [total] is the device's total contact count. */
    data class ContactsStart(val total: Long) : Incoming
    /** End of a contacts sync; [mostRecentLastMod] can be passed as `since` to the next GET_CONTACTS. */
    data class ContactsEnd(val mostRecentLastMod: Long) : Incoming

    data class Sent(val result: SendResult) : Incoming
    data class DeviceTime(val epochSecs: Long) : Incoming
    data object NoMoreMessages : Incoming
    data class Battery(val info: BattAndStorage) : Incoming

    data class Message(val message: IncomingMessage) : Incoming

    /** Reply to CMD_GET_CHANNEL (RESP_CODE_CHANNEL_INFO). */
    data class ChannelInfo(val index: Int, val name: String, val secret: ByteArray) : Incoming {
        override fun equals(other: Any?) = other is ChannelInfo && index == other.index
        override fun hashCode() = index
    }

    /** Reply to CMD_EXPORT_CONTACT (RESP_CODE_EXPORT_CONTACT): the raw advert-packet "card" bytes. */
    data class ExportedContact(val card: ByteArray) : Incoming {
        override fun equals(other: Any?) = other is ExportedContact && card.contentEquals(other.card)
        override fun hashCode() = card.contentHashCode()
    }

    // ---- pushes ----
    data class AdvertHeard(val publicKey: ByteArray) : Incoming {
        override fun equals(other: Any?) = other is AdvertHeard && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = publicKey.contentHashCode()
    }
    data class PathUpdated(val publicKey: ByteArray) : Incoming {
        override fun equals(other: Any?) = other is PathUpdated && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = publicKey.contentHashCode()
    }
    /** A newly discovered node (PUSH_CODE_NEW_ADVERT) — full contact frame, like RESP_CODE_CONTACT. */
    data class NewAdvert(val contact: Contact) : Incoming
    data class SendConfirmed(val ackId: Long, val roundTripMs: Long) : Incoming
    data object MsgWaiting : Incoming
    /** PUSH_CODE_LOGIN_SUCCESS — [isAdmin/permissions] byte then the repeater's 6-byte key prefix. */
    data class LoginSuccess(val pubKeyPrefix: ByteArray, val isAdmin: Boolean) : Incoming {
        override fun equals(other: Any?) = other is LoginSuccess &&
            pubKeyPrefix.contentEquals(other.pubKeyPrefix) && isAdmin == other.isAdmin
        override fun hashCode() = pubKeyPrefix.contentHashCode() * 31 + isAdmin.hashCode()
    }
    /** PUSH_CODE_LOGIN_FAIL — reserved byte then the repeater's 6-byte key prefix. */
    data class LoginFail(val pubKeyPrefix: ByteArray) : Incoming {
        override fun equals(other: Any?) = other is LoginFail && pubKeyPrefix.contentEquals(other.pubKeyPrefix)
        override fun hashCode() = pubKeyPrefix.contentHashCode()
    }
    /** PUSH_CODE_STATUS_RESPONSE — the repeater's 6-byte key prefix + its decoded stats. */
    data class Status(val pubKeyPrefix: ByteArray, val stats: RepeaterStats) : Incoming {
        override fun equals(other: Any?) = other is Status &&
            pubKeyPrefix.contentEquals(other.pubKeyPrefix) && stats == other.stats
        override fun hashCode() = pubKeyPrefix.contentHashCode() * 31 + stats.hashCode()
    }
    data class Trace(val result: TraceResult) : Incoming
    data class RxPacket(val log: RxLog) : Incoming
    data class NodeDiscovered(val node: DiscoveredNode) : Incoming
    /** RESP_CODE_ALLOWED_REPEAT_FREQ — frequency ranges (kHz) where client-repeat is permitted. */
    data class AllowedRepeatFreqs(val rangesKhz: List<LongRange>) : Incoming

    /** PUSH_CODE_TELEMETRY_RESPONSE — decoded LPP readings from [pubKeyPrefix]. */
    data class Telemetry(val pubKeyPrefix: ByteArray, val readings: List<Lpp.Reading>) : Incoming {
        override fun equals(other: Any?) = other is Telemetry && pubKeyPrefix.contentEquals(other.pubKeyPrefix) && readings == other.readings
        override fun hashCode() = pubKeyPrefix.contentHashCode() * 31 + readings.hashCode()
    }

    // ---- stats (replies to CMD_GET_STATS) ----
    data class CoreStatsResp(val stats: CoreStats) : Incoming
    data class RadioStatsResp(val stats: RadioStats) : Incoming
    data class PacketStatsResp(val stats: PacketStats) : Incoming
    /** A RESP_CODE_STATS with an unrecognised sub-type. */
    data class UnknownStats(val subType: Int) : Incoming

    data class Tuning(val params: TuningParams) : Incoming
    data class AutoAdd(val config: AutoAddConfig) : Incoming
    data class Device(val info: DeviceInfo) : Incoming

    /** Any frame not (yet) structurally decoded. [code] is the leading byte. */
    data class Raw(val code: Int, val payload: ByteArray) : Incoming {
        override fun equals(other: Any?) = other is Raw && code == other.code && payload.contentEquals(other.payload)
        override fun hashCode() = code * 31 + payload.contentHashCode()
    }
}

object FrameDecoder {

    /** Decode one complete frame (a full NUS notify payload). Never throws on a short/unknown frame. */
    fun decode(frame: ByteArray): Incoming {
        if (frame.isEmpty()) return Incoming.Raw(-1, frame)
        val code = frame[0].toInt() and 0xFF
        val r = FrameReader(frame, start = 1)
        return try {
            when (code) {
                Resp.OK -> Incoming.Ok
                Resp.ERR -> Incoming.Err(if (r.remaining > 0) r.u8() else 0)
                Resp.DISABLED -> Incoming.Disabled
                Resp.SELF_INFO -> Incoming.Self(parseSelfInfo(r))
                Resp.CONTACTS_START -> Incoming.ContactsStart(if (r.remaining >= 4) r.u32() else 0)
                Resp.CONTACT -> Incoming.ContactEntry(parseContact(r))
                Resp.END_OF_CONTACTS -> Incoming.ContactsEnd(if (r.remaining >= 4) r.u32() else 0)
                Resp.SENT -> Incoming.Sent(parseSent(r))
                Resp.CURR_TIME -> Incoming.DeviceTime(r.u32())
                Resp.NO_MORE_MESSAGES -> Incoming.NoMoreMessages
                Resp.BATT_AND_STORAGE -> Incoming.Battery(parseBattery(r))
                Resp.DEVICE_INFO -> Incoming.Device(parseDeviceInfo(r))
                Resp.STATS -> parseStats(r)
                Resp.TUNING_PARAMS -> Incoming.Tuning(TuningParams(r.u32() / 1000.0, r.u32() / 1000.0))
                Resp.AUTOADD_CONFIG -> Incoming.AutoAdd(
                    AutoAddConfig(if (r.remaining > 0) r.u8() else 0, if (r.remaining > 0) r.u8() else 0)
                )
                Resp.ALLOWED_REPEAT_FREQ -> {
                    val ranges = ArrayList<LongRange>()
                    while (r.remaining >= 8) { // (lower, upper) kHz pairs
                        val lo = r.u32(); val hi = r.u32()
                        ranges.add(lo..hi)
                    }
                    Incoming.AllowedRepeatFreqs(ranges)
                }
                Resp.CONTACT_MSG_RECV, Resp.CONTACT_MSG_RECV_V3 ->
                    Incoming.Message(parseMessage(r, channel = false, v3 = code == Resp.CONTACT_MSG_RECV_V3))
                Resp.CHANNEL_MSG_RECV, Resp.CHANNEL_MSG_RECV_V3 ->
                    Incoming.Message(parseMessage(r, channel = true, v3 = code == Resp.CHANNEL_MSG_RECV_V3))
                Resp.CHANNEL_INFO -> Incoming.ChannelInfo(r.u8(), r.cstr(32), r.bytes(minOf(16, r.remaining)))
                Resp.EXPORT_CONTACT -> Incoming.ExportedContact(r.rest())

                Push.ADVERT -> Incoming.AdvertHeard(r.bytes(minOf(32, r.remaining)))
                Push.NEW_ADVERT -> Incoming.NewAdvert(parseContact(r))
                Push.PATH_UPDATED -> Incoming.PathUpdated(r.bytes(minOf(32, r.remaining)))
                Push.SEND_CONFIRMED -> Incoming.SendConfirmed(r.u32(), if (r.remaining >= 4) r.u32() else 0)
                Push.MSG_WAITING -> Incoming.MsgWaiting
                Push.LOGIN_SUCCESS -> {
                    val perms = if (r.remaining > 0) r.u8() else 0 // permissions / is_admin
                    Incoming.LoginSuccess(r.bytes(minOf(6, r.remaining)), isAdmin = perms != 0)
                }
                Push.LOGIN_FAIL -> {
                    if (r.remaining > 0) r.u8() // reserved
                    Incoming.LoginFail(r.bytes(minOf(6, r.remaining)))
                }
                Push.STATUS_RESPONSE -> {
                    r.u8() // reserved
                    val prefix = r.bytes(minOf(6, r.remaining))
                    Incoming.Status(prefix, parseRepeaterStats(r))
                }
                Push.TRACE_DATA -> Incoming.Trace(parseTrace(r))
                Push.LOG_RX_DATA -> Incoming.RxPacket(parseRxLog(r))
                Push.TELEMETRY_RESPONSE -> {
                    r.u8() // reserved
                    val prefix = r.bytes(minOf(6, r.remaining))
                    Incoming.Telemetry(prefix, Lpp.decode(r.rest()))
                }
                Push.CONTROL_DATA -> parseControlData(r) ?: Incoming.Raw(code, frame.copyOfRange(1, frame.size))

                else -> Incoming.Raw(code, frame.copyOfRange(1, frame.size))
            }
        } catch (_: IndexOutOfBoundsException) {
            // Truncated/unexpected frame — preserve raw rather than crashing the link.
            Incoming.Raw(code, frame.copyOfRange(1, frame.size))
        }
    }

    // RESP_CODE_CONTACT layout (confirmed against firmware):
    // pubKey(32) type(1) flags(1) outPathLen(1) outPath(64) name(32, cstr)
    // lastAdvert(u32) gpsLat(i32) gpsLon(i32) lastMod(u32)
    private fun parseContact(r: FrameReader): Contact {
        val pub = r.bytes(32)
        val type = r.u8()
        val flags = r.u8()
        val outPathLen = r.u8()
        val outPath = r.bytes(64)
        val name = r.cstr(32)
        val lastAdvert = r.u32()
        val lat = r.i32()
        val lon = r.i32()
        val lastMod = r.u32()
        return Contact(pub, type, flags, outPathLen, outPath, name, lastAdvert, lat, lon, lastMod)
    }

    // RESP_CODE_SENT (10 bytes): flood(1) expectedAck(u32) estTimeout(u32)
    private fun parseSent(r: FrameReader): SendResult {
        val flood = r.u8() != 0
        val ack = if (r.remaining >= 4) r.u32() else 0
        val est = if (r.remaining >= 4) r.u32() else 0
        return SendResult(flood, ack, est)
    }

    // RESP_CODE_DEVICE_INFO: verCode(1) maxContacts/2(1) maxChannels(1) blePin(u32)
    //   buildDate(12 cstr) manufacturer(40 cstr) firmwareVersion(20 cstr) clientRepeat(1) pathHashMode(1)
    private fun parseDeviceInfo(r: FrameReader): DeviceInfo {
        val ver = r.u8()
        val maxContacts = r.u8() * 2
        val maxChannels = r.u8()
        val blePin = r.u32()
        val buildDate = r.cstr(12)
        val manufacturer = r.cstr(40)
        val firmwareVersion = r.cstr(20)
        val clientRepeat = if (r.remaining > 0) r.u8() != 0 else false
        val pathHashMode = if (r.remaining > 0) r.u8() else 0
        return DeviceInfo(ver, maxContacts, maxChannels, blePin, buildDate, manufacturer, firmwareVersion, clientRepeat, pathHashMode)
    }

    // RESP_CODE_BATT_AND_STORAGE: battMv(u16) usedKb(u32) totalKb(u32)
    // (storage fields optional on older firmware — guarded by remaining.)
    private fun parseBattery(r: FrameReader): BattAndStorage {
        val mv = r.u16()
        val used = if (r.remaining >= 4) r.u32() else 0
        val total = if (r.remaining >= 4) r.u32() else 0
        return BattAndStorage(mv, used, total)
    }

    // RESP_CODE_SELF_INFO (confirmed against firmware MyMesh.cpp writer):
    //   type(1) txPower(1) maxTxPower(1) pubKey(32) advLat(i32) advLon(i32)
    //   multiAcks(1) advertLocPolicy(1) telemetryModes(1) manualAddContacts(1)
    //   freq(u32, kHz) bw(u32, Hz) sf(1) cr(1) name(rest)
    // telemetryModes byte = (env<<4) | (loc<<2) | base.
    private fun parseSelfInfo(r: FrameReader): SelfInfo {
        val type = r.u8()
        val tx = r.u8()
        val maxTx = r.u8()
        val pub = r.bytes(32)
        val lat = r.i32()
        val lon = r.i32()
        val multiAcks = r.u8()
        val advertLocPolicy = r.u8()
        val telem = r.u8()
        val manualAdd = r.u8()
        val freq = r.u32()
        val bw = r.u32()
        val sf = r.u8()
        val cr = r.u8()
        val name = r.restAsString()
        return SelfInfo(
            type = type, txPower = tx, maxTxPower = maxTx, publicKey = pub,
            advLat = lat, advLon = lon,
            multiAcks = multiAcks, advertLocPolicy = advertLocPolicy,
            telemetryModeBase = telem and 0x03,
            telemetryModeLoc = (telem shr 2) and 0x03,
            telemetryModeEnv = (telem shr 4) and 0x03,
            manualAddContacts = manualAdd,
            radioFreqKhz = freq, radioBwHz = bw, radioSf = sf, radioCr = cr, name = name,
        )
    }

    // Message receive (confirmed against firmware MyMesh.cpp queueMessage/onChannelMessageRecv):
    //   V3 frames prepend snr(i8, =SNR*4) + reserved1(1) + reserved2(1) after the code byte.
    //   Contact: [v3 hdr] pubKeyPrefix(6) pathLen(1) txtType(1) ts(u32) [signer(4) if SIGNED] text(rest)
    //   Channel: [v3 hdr] channelIdx(1) pathLen(1) txtType(1) ts(u32) text(rest)
    private fun parseMessage(r: FrameReader, channel: Boolean, v3: Boolean): IncomingMessage {
        var snrQ = 0
        if (v3) {
            snrQ = r.i8()   // SNR * 4
            r.u8(); r.u8()  // reserved1, reserved2
        }
        return if (channel) {
            val idx = r.u8()
            val pathLen = r.u8()
            val txtType = r.u8()
            val ts = r.u32()
            IncomingMessage(ByteArray(0), pathLen, txtType, ts, r.restAsString(),
                isChannel = true, channelIdx = idx, snrQ = snrQ)
        } else {
            val prefix = r.bytes(6)
            val pathLen = r.u8()
            val txtType = r.u8()
            val ts = r.u32()
            if (txtType == TxtType.SIGNED_PLAIN && r.remaining >= 4) r.bytes(4) // signer prefix (extra)
            IncomingMessage(prefix, pathLen, txtType, ts, r.restAsString(),
                isChannel = false, snrQ = snrQ)
        }
    }

    // PUSH_CODE_STATUS_RESPONSE. Verified against firmware:
    //   companion (MyMesh.cpp onContactResponse): [code] reserved(1) pubKeyPrefix(6) <stats>
    //   the <stats> blob is a raw little-endian memcpy of the repeater's RepeaterStats struct
    //   (simple_repeater/MyMesh.h), 56 bytes, all fields naturally aligned (no padding):
    //     batt_milli_volts u16, curr_tx_queue_len u16, noise_floor i16, last_rssi i16,
    //     n_packets_recv u32, n_packets_sent u32, total_air_time_secs u32, total_up_time_secs u32,
    //     n_sent_flood u32, n_sent_direct u32, n_recv_flood u32, n_recv_direct u32,
    //     err_events u16, last_snr i16 (x4), n_direct_dups u16, n_flood_dups u16,
    //     total_rx_air_time_secs u32, n_recv_errors u32
    private fun parseRepeaterStats(r: FrameReader): RepeaterStats {
        val batteryMv = r.u16()
        val txQueue = r.u16()
        val noise = r.i16()
        val rssi = r.i16()
        val nRecv = r.u32()
        val nSent = r.u32()
        val airTx = r.u32()
        val uptime = r.u32()
        val sentFlood = r.u32()
        val sentDirect = r.u32()
        val recvFlood = r.u32()
        val recvDirect = r.u32()
        val errEvents = r.u16()
        val snrQ = r.i16()
        val directDups = r.u16()
        val floodDups = r.u16()
        val airRx = r.u32()
        val recvErrors = r.u32()
        return RepeaterStats(
            batteryMilliVolts = batteryMv, txQueueLen = txQueue, noiseFloor = noise, lastRssi = rssi,
            nPacketsRecv = nRecv, nPacketsSent = nSent, airtimeSecs = airTx, uptimeSecs = uptime,
            sentFlood = sentFlood, sentDirect = sentDirect, recvFlood = recvFlood, recvDirect = recvDirect,
            errEvents = errEvents, lastSnrQ = snrQ, directDups = directDups, floodDups = floodDups,
            airtimeRxSecs = airRx, recvErrors = recvErrors,
        )
    }

    // PUSH_CODE_CONTROL_DATA (MyMesh::onControlDataRecv):
    //   snr(i8 x4) rssi(i8) pathLen(1) ctlPayload...
    //   A NODE_DISCOVER_RESP ctl payload is: type(0x90|nodeType) inSnr(i8 x4) tag(u32) pubKey(8|32).
    // Returns null for control data we don't decode (caller falls back to Raw).
    private fun parseControlData(r: FrameReader): Incoming? {
        val snr = r.i8()
        val rssi = r.i8()
        r.u8() // path_len
        if (r.remaining < 1) return null
        val ptype = r.u8()
        if (ptype and 0xF0 != CtlType.NODE_DISCOVER_RESP) return null
        val nodeType = ptype and 0x0F
        val inSnr = r.i8()
        val tag = r.u32()
        val key = r.bytes(r.remaining) // 8-byte prefix or full 32
        return Incoming.NodeDiscovered(
            DiscoveredNode(pubKey = key, type = nodeType, tag = tag, snrQ = snr, inSnrQ = inSnr, rssi = rssi)
        )
    }

    // PUSH_CODE_TRACE_DATA (confirmed vs MyMesh::onTraceRecv):
    //   reserved(1) pathLen(1) flags(1) tag(u32) auth(u32)
    //   pathHashes(pathLen) pathSnrs(pathLen >> (flags&3)) finalSnr(i8, to this node)
    private fun parseTrace(r: FrameReader): TraceResult {
        r.u8() // reserved
        val pathLen = if (r.remaining > 0) r.u8() else 0
        val flags = if (r.remaining > 0) r.u8() else 0
        val pathSz = flags and 0x03
        val tag = r.u32()
        r.u32() // auth code (unused here)
        val hashes = r.bytes(minOf(pathLen, r.remaining))
        val snrCount = pathLen shr pathSz
        val snrs = r.bytes(minOf(snrCount, r.remaining))
        val finalSnr = if (r.remaining > 0) r.i8() else 0
        val hops = hashes.indices.map { idx ->
            val si = idx shr pathSz
            TraceHop(hashes[idx].toInt() and 0xFF, if (si < snrs.size) snrs[si].toInt() else 0)
        }
        return TraceResult(tag, hops, finalSnr)
    }

    // PUSH_CODE_LOG_RX_DATA: snrQ(i8) rssi(i8) raw(rest). Payload/route type is in raw[0].
    private fun parseRxLog(r: FrameReader): RxLog {
        val snr = r.i8()
        val rssi = r.i8()
        return RxLog(snr, rssi, r.rest())
    }

    // RESP_CODE_STATS: subType(1) then sub-type-specific fields (CMD_GET_STATS handler).
    private fun parseStats(r: FrameReader): Incoming = when (val sub = r.u8()) {
        StatsType.CORE -> Incoming.CoreStatsResp(
            CoreStats(
                batteryMilliVolts = r.u16(),
                uptimeSecs = r.u32(),
                errFlags = r.u16(),
                txQueueLen = r.u8(),
            )
        )
        StatsType.RADIO -> Incoming.RadioStatsResp(
            RadioStats(
                noiseFloor = r.i16(),
                lastRssi = r.i8(),
                lastSnrQ = r.i8(),
                txAirtimeSecs = r.u32(),
                rxAirtimeSecs = r.u32(),
            )
        )
        StatsType.PACKETS -> Incoming.PacketStatsResp(
            PacketStats(
                recv = r.u32(), sent = r.u32(),
                sentFlood = r.u32(), sentDirect = r.u32(),
                recvFlood = r.u32(), recvDirect = r.u32(),
                recvErrors = r.u32(),
            )
        )
        else -> Incoming.UnknownStats(sub)
    }
}
