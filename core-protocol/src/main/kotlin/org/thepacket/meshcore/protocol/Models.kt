package org.thepacket.meshcore.protocol

/** Advert/contact type byte (ADV_TYPE_* in firmware). */
object ContactType {
    const val NONE = 0
    const val CHAT = 1
    const val REPEATER = 2
    const val ROOM = 3
    const val SENSOR = 4
}

/**
 * A contact as returned by RESP_CODE_CONTACT during a GET_CONTACTS sync.
 * gps fields are in units of 1e-6 degrees (divide by 1e6 for decimal degrees);
 * 0 means "no position known".
 */
data class Contact(
    val publicKey: ByteArray,
    val type: Int,
    val flags: Int,
    val outPathLen: Int,
    val outPath: ByteArray,
    val name: String,
    val lastAdvert: Long,
    val gpsLat: Int,
    val gpsLon: Int,
    val lastMod: Long,
) {
    val latDegrees: Double? get() = if (gpsLat == 0 && gpsLon == 0) null else gpsLat / 1e6
    val lonDegrees: Double? get() = if (gpsLat == 0 && gpsLon == 0) null else gpsLon / 1e6
    val isRepeater: Boolean get() = type == ContactType.REPEATER
    val keyPrefixHex: String get() = publicKey.copyOf(6).toHex()

    // data class with arrays: identity by public key is what we actually want.
    override fun equals(other: Any?) = other is Contact && publicKey.contentEquals(other.publicKey)
    override fun hashCode() = publicKey.contentHashCode()
}

/**
 * Reply to CMD_APP_START (RESP_CODE_SELF_INFO): this device's own identity/config.
 * Field order matches the firmware writer (MyMesh.cpp). Note the frame's mixed units:
 * frequency is in kHz (MHz×1000), bandwidth is in Hz (kHz×1000).
 */
data class SelfInfo(
    val type: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val publicKey: ByteArray,
    val advLat: Int,
    val advLon: Int,
    val multiAcks: Int,
    val advertLocPolicy: Int,
    val telemetryModeBase: Int,
    val telemetryModeLoc: Int,
    val telemetryModeEnv: Int,
    val manualAddContacts: Int,
    val radioFreqKhz: Long,
    val radioBwHz: Long,
    val radioSf: Int,
    val radioCr: Int,
    val name: String,
) {
    val freqMhz: Double get() = radioFreqKhz / 1000.0
    val bwKhz: Double get() = radioBwHz / 1000.0

    override fun equals(other: Any?) = other is SelfInfo && publicKey.contentEquals(other.publicKey)
    override fun hashCode() = publicKey.contentHashCode()
}

/** Reply to CMD_GET_BATT_AND_STORAGE. */
data class BattAndStorage(
    val batteryMilliVolts: Int,
    val storageUsedKb: Long,
    val storageTotalKb: Long,
)

/** Result of sending a text message (RESP_CODE_SENT). */
data class SendResult(
    val sentFlood: Boolean,
    /** Non-zero ACK id to correlate with a later [Event.SendConfirmed]; 0 if none expected. */
    val expectedAck: Long,
)

/** A received text message (RESP_CODE_CONTACT_MSG_RECV / _V3). */
data class IncomingMessage(
    val pubKeyPrefix: ByteArray,
    val pathLen: Int,
    val txtType: Int,
    val senderTimestamp: Long,
    val text: String,
    val isChannel: Boolean,
    val channelIdx: Int = -1,
) {
    override fun equals(other: Any?) = other is IncomingMessage &&
        pubKeyPrefix.contentEquals(other.pubKeyPrefix) &&
        senderTimestamp == other.senderTimestamp && text == other.text
    override fun hashCode() = senderTimestamp.hashCode() * 31 + text.hashCode()
}

/**
 * Parsed repeater status blob (PUSH_CODE_STATUS_RESPONSE) — the 56-byte
 * little-endian RepeaterStats struct that follows the response code.
 * Field order mirrors the firmware's status reply.
 */
data class RepeaterStats(
    val batteryMilliVolts: Int,
    val txQueueLen: Int,
    val noiseFloor: Int,
    val lastRssi: Int,
    val nPacketsRecv: Long,
    val nPacketsSent: Long,
    val airtimeSecs: Long,
    val uptimeSecs: Long,
    val sentFlood: Long,
    val sentDirect: Long,
    val recvFlood: Long,
    val recvDirect: Long,
    val errEvents: Int,
    val lastSnrQ: Int,
    val airtimeRxSecs: Long,
    val recvErrors: Int,
)

/** One hop of a trace-route result (PUSH_CODE_TRACE_DATA). */
data class TraceHop(val hashByte: Int, val snrQ: Int) {
    /** SNR is transmitted as quarter-dB (snr * 4). */
    val snrDb: Double get() = snrQ / 4.0
}

data class TraceResult(
    val tag: Long,
    val hops: List<TraceHop>,
    val finalSnrQ: Int,
) {
    val finalSnrDb: Double get() = finalSnrQ / 4.0
}

/** A logged raw RX packet (PUSH_CODE_LOG_RX_DATA) — the packet-monitor feed. */
data class RxLog(
    val snrQ: Int,
    val rssi: Int,
    val payloadType: Int,
    val raw: ByteArray,
) {
    val snrDb: Double get() = snrQ / 4.0
    override fun equals(other: Any?) = other is RxLog && raw.contentEquals(other.raw) && rssi == other.rssi
    override fun hashCode() = raw.contentHashCode() * 31 + rssi
}
