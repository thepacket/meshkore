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
    data object ContactsStart : Incoming
    data object ContactsEnd : Incoming

    data class Sent(val result: SendResult) : Incoming
    data class DeviceTime(val epochSecs: Long) : Incoming
    data object NoMoreMessages : Incoming
    data class Battery(val info: BattAndStorage) : Incoming

    data class Message(val message: IncomingMessage) : Incoming

    // ---- pushes ----
    data class AdvertHeard(val publicKey: ByteArray) : Incoming {
        override fun equals(other: Any?) = other is AdvertHeard && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = publicKey.contentHashCode()
    }
    data class PathUpdated(val publicKey: ByteArray) : Incoming {
        override fun equals(other: Any?) = other is PathUpdated && publicKey.contentEquals(other.publicKey)
        override fun hashCode() = publicKey.contentHashCode()
    }
    data class SendConfirmed(val ackId: Long, val roundTripMs: Long) : Incoming
    data object MsgWaiting : Incoming
    data object LoginSuccess : Incoming
    data object LoginFail : Incoming
    data class Status(val stats: RepeaterStats) : Incoming
    data class Trace(val result: TraceResult) : Incoming
    data class RxPacket(val log: RxLog) : Incoming

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
                Resp.CONTACTS_START -> Incoming.ContactsStart
                Resp.CONTACT -> Incoming.ContactEntry(parseContact(r))
                Resp.END_OF_CONTACTS -> Incoming.ContactsEnd
                Resp.SENT -> Incoming.Sent(parseSent(r))
                Resp.CURR_TIME -> Incoming.DeviceTime(r.u32())
                Resp.NO_MORE_MESSAGES -> Incoming.NoMoreMessages
                Resp.BATT_AND_STORAGE -> Incoming.Battery(parseBattery(r))
                Resp.CONTACT_MSG_RECV, Resp.CONTACT_MSG_RECV_V3 ->
                    Incoming.Message(parseMessage(r, channel = false, v3 = code == Resp.CONTACT_MSG_RECV_V3))
                Resp.CHANNEL_MSG_RECV, Resp.CHANNEL_MSG_RECV_V3 ->
                    Incoming.Message(parseMessage(r, channel = true, v3 = code == Resp.CHANNEL_MSG_RECV_V3))

                Push.ADVERT -> Incoming.AdvertHeard(r.bytes(minOf(32, r.remaining)))
                Push.PATH_UPDATED -> Incoming.PathUpdated(r.bytes(minOf(32, r.remaining)))
                Push.SEND_CONFIRMED -> Incoming.SendConfirmed(r.u32(), if (r.remaining >= 4) r.u32() else 0)
                Push.MSG_WAITING -> Incoming.MsgWaiting
                Push.LOGIN_SUCCESS -> Incoming.LoginSuccess
                Push.LOGIN_FAIL -> Incoming.LoginFail
                Push.STATUS_RESPONSE -> Incoming.Status(parseRepeaterStats(r))
                Push.TRACE_DATA -> Incoming.Trace(parseTrace(r))
                Push.LOG_RX_DATA -> Incoming.RxPacket(parseRxLog(r))

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

    // RESP_CODE_SENT: flood(1) expectedAck(u32)
    private fun parseSent(r: FrameReader): SendResult {
        val flood = r.u8() != 0
        val ack = if (r.remaining >= 4) r.u32() else 0
        return SendResult(flood, ack)
    }

    // RESP_CODE_BATT_AND_STORAGE: battMv(u16) usedKb(u32) totalKb(u32)
    // (storage fields optional on older firmware — guarded by remaining.)
    private fun parseBattery(r: FrameReader): BattAndStorage {
        val mv = r.u16()
        val used = if (r.remaining >= 4) r.u32() else 0
        val total = if (r.remaining >= 4) r.u32() else 0
        return BattAndStorage(mv, used, total)
    }

    // RESP_CODE_SELF_INFO: type(1) txPower(1) maxTxPower(1) pubKey(32)
    // advLat(i32) advLon(i32) freq(u32) bw(u32) sf(1) cr(1) name(rest, cstr)
    // TODO: confirm field order/widths against the firmware SELF_INFO writer.
    private fun parseSelfInfo(r: FrameReader): SelfInfo {
        val type = r.u8()
        val tx = r.u8()
        val maxTx = r.u8()
        val pub = r.bytes(32)
        val lat = r.i32()
        val lon = r.i32()
        val freq = r.u32()
        val bw = r.u32()
        val sf = r.u8()
        val cr = r.u8()
        val name = r.restAsString()
        return SelfInfo(type, tx, maxTx, pub, lat, lon, freq, bw, sf, cr, name)
    }

    // RESP_CODE_CONTACT_MSG_RECV(_V3): pubKeyPrefix(6) pathLen(1) txtType(1)
    // senderTimestamp(u32) text(rest). Channel variant: channelIdx replaces the
    // 6-byte prefix's role. TODO: confirm V3 deltas (SNR/extra header) against firmware.
    private fun parseMessage(r: FrameReader, channel: Boolean, v3: Boolean): IncomingMessage {
        return if (channel) {
            val idx = r.u8()
            val pathLen = r.u8()
            val txtType = r.u8()
            val ts = r.u32()
            IncomingMessage(ByteArray(0), pathLen, txtType, ts, r.restAsString(), isChannel = true, channelIdx = idx)
        } else {
            val prefix = r.bytes(6)
            val pathLen = r.u8()
            val txtType = r.u8()
            val ts = r.u32()
            IncomingMessage(prefix, pathLen, txtType, ts, r.restAsString(), isChannel = false)
        }
    }

    // PUSH_CODE_STATUS_RESPONSE: 56-byte little-endian RepeaterStats after the code byte.
    // TODO: byte-for-byte verify this layout (field widths + ordering) against the
    // firmware RepeaterStats struct before trusting the numbers on-device.
    private fun parseRepeaterStats(r: FrameReader): RepeaterStats {
        val batteryMv = r.u16()
        val txQueue = r.u16()
        val noise = r.i8()
        val rssi = r.i8()
        val nRecv = r.u32()
        val nSent = r.u32()
        val airTx = r.u32()
        val uptime = r.u32()
        val sentFlood = r.u32()
        val sentDirect = r.u32()
        val recvFlood = r.u32()
        val recvDirect = r.u32()
        val errEvents = r.u16()
        val snrQ = r.i8()
        // one reserved/pad byte keeps the 16-bit and 32-bit fields word-aligned
        if (r.remaining > 0) r.bytes(1)
        val airRx = r.u32()
        val recvErrors = r.u16()
        return RepeaterStats(
            batteryMilliVolts = batteryMv, txQueueLen = txQueue, noiseFloor = noise, lastRssi = rssi,
            nPacketsRecv = nRecv, nPacketsSent = nSent, airtimeSecs = airTx, uptimeSecs = uptime,
            sentFlood = sentFlood, sentDirect = sentDirect, recvFlood = recvFlood, recvDirect = recvDirect,
            errEvents = errEvents, lastSnrQ = snrQ, airtimeRxSecs = airRx, recvErrors = recvErrors,
        )
    }

    // PUSH_CODE_TRACE_DATA: tag(u32) finalSnrQ(i8) then pairs of (hashByte, snrQ).
    // TODO: confirm hop record layout/ordering against MyMesh::onTraceRecv.
    private fun parseTrace(r: FrameReader): TraceResult {
        val tag = r.u32()
        val finalSnr = if (r.remaining > 0) r.i8() else 0
        val hops = ArrayList<TraceHop>()
        while (r.remaining >= 2) hops.add(TraceHop(r.u8(), r.i8()))
        return TraceResult(tag, hops, finalSnr)
    }

    // PUSH_CODE_LOG_RX_DATA: snrQ(i8) rssi(i8) payloadType(1) raw(rest).
    private fun parseRxLog(r: FrameReader): RxLog {
        val snr = r.i8()
        val rssi = r.i8()
        val type = if (r.remaining > 0) r.u8() else 0
        return RxLog(snr, rssi, type, r.rest())
    }
}
