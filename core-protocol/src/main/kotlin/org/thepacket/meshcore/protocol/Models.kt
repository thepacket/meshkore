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
    /** meshcore.ca region (IATA) the node belongs to, learned from an observed advert; null = unknown/home. */
    val region: String? = null,
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
    /** Non-zero ACK id to correlate with a later [Incoming.SendConfirmed]; 0 if none expected. */
    val expectedAck: Long,
    /** Firmware's estimated round-trip timeout in ms — use to mark a message failed if no ack by then. */
    val estTimeoutMs: Long,
)

/**
 * A received text message (RESP_CODE_CONTACT_MSG_RECV / _V3, or the channel variants).
 * [pathLen] is 0xFF when delivered direct, else the flood path length. [snrQ] is SNR×4
 * (V3 frames only; 0 otherwise).
 */
data class IncomingMessage(
    val pubKeyPrefix: ByteArray,
    val pathLen: Int,
    val txtType: Int,
    val senderTimestamp: Long,
    val text: String,
    val isChannel: Boolean,
    val channelIdx: Int = -1,
    val snrQ: Int = 0,
    /**
     * For SIGNED_PLAIN messages (notably room-server posts, which arrive relayed by the room),
     * the first 4 bytes of the original *author's* public key. Empty otherwise.
     */
    val signerPrefix: ByteArray = ByteArray(0),
) {
    val snrDb: Double get() = snrQ / 4.0
    val deliveredDirect: Boolean get() = pathLen == 0xFF
    val keyPrefixHex: String get() = pubKeyPrefix.toHex()
    val signerPrefixHex: String get() = signerPrefix.toHex()

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
    val directDups: Int,
    val floodDups: Int,
    val airtimeRxSecs: Long,
    val recvErrors: Long,
)

/**
 * A node that answered a blind NODE_DISCOVER_REQ (PUSH_CODE_CONTROL_DATA / NODE_DISCOVER_RESP).
 * [pubKey] is an 8-byte prefix (prefix-only request) or the full 32 bytes. SNRs are quarter-dB.
 */
data class DiscoveredNode(
    val pubKey: ByteArray,
    val type: Int,        // ContactType.*
    val tag: Long,
    val snrQ: Int,        // our SNR of their reply
    val inSnrQ: Int,      // their SNR of our request
    val rssi: Int,
) {
    val keyPrefixHex: String get() = pubKey.copyOf(minOf(6, pubKey.size)).toHex()
    val snrDb: Double get() = snrQ / 4.0

    override fun equals(other: Any?) = other is DiscoveredNode && pubKey.contentEquals(other.pubKey)
    override fun hashCode() = pubKey.contentHashCode()
}

/** A node's owner info (REQ_TYPE_GET_OWNER_INFO reply: "firmware\nnode_name\nowner"). */
data class OwnerInfo(val firmwareVersion: String, val nodeName: String, val owner: String) {
    companion object {
        fun decode(data: ByteArray): OwnerInfo {
            val parts = String(data, Charsets.UTF_8).split('\n')
            fun part(i: Int) = parts.getOrElse(i) { "" }.trim().trim('\u0000')
            return OwnerInfo(part(0), part(1), part(2))
        }
    }
}

/** One client in a repeater/room's access-control list (REQ_TYPE_GET_ACCESS_LIST reply). */
data class AclEntry(val pubKeyPrefix: ByteArray, val permissions: Int) {
    val keyPrefixHex: String get() = pubKeyPrefix.copyOf(6).toHex()
    val roleName: String get() = AclRole.name(permissions)

    override fun equals(other: Any?) = other is AclEntry && pubKeyPrefix.contentEquals(other.pubKeyPrefix)
    override fun hashCode() = pubKeyPrefix.contentHashCode()
}

/** Min/max/avg of one telemetry channel over a time window (REQ_TYPE_GET_AVG_MIN_MAX reply). */
data class MmaReading(val channel: Int, val type: Int, val min: Double, val max: Double, val avg: Double)

/** A node in a repeater's neighbour table (REQ_TYPE_GET_NEIGHBOURS reply). */
data class Neighbour(
    val pubKey: ByteArray,  // key prefix of the requested length
    val secsAgo: Int,       // how long ago this neighbour was last heard
    val snrQ: Int,          // SNR (quarter-dB)
) {
    val keyPrefixHex: String get() = pubKey.copyOf(minOf(6, pubKey.size)).toHex()
    val snrDb: Double get() = snrQ / 4.0

    override fun equals(other: Any?) = other is Neighbour && pubKey.contentEquals(other.pubKey)
    override fun hashCode() = pubKey.contentHashCode()
}

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

/**
 * PUSH_CODE_PATH_DISCOVERY_RESPONSE — the firmware's reply to CMD_SEND_PATH_DISCOVERY_REQ.
 * [outPath]/[inPath] are the outbound/return route hops; each hop is a 1-byte key-prefix hash
 * (matching a contact by its first public-key byte). An empty path means a direct (0-hop) link.
 */
data class PathDiscoveryResult(
    val pubKeyPrefix: ByteArray,
    val outPath: List<Int>,
    val inPath: List<Int>,
) {
    val keyPrefixHex: String get() = pubKeyPrefix.copyOf(6).toHex()

    override fun equals(other: Any?) = other is PathDiscoveryResult &&
        pubKeyPrefix.contentEquals(other.pubKeyPrefix) && outPath == other.outPath && inPath == other.inPath
    override fun hashCode() =
        (pubKeyPrefix.contentHashCode() * 31 + outPath.hashCode()) * 31 + inPath.hashCode()
}

/**
 * A logged raw RX packet (PUSH_CODE_LOG_RX_DATA) — the packet-monitor feed.
 * The payload/route type is derived from the packet header byte (raw[0]).
 */
data class RxLog(
    val snrQ: Int,
    val rssi: Int,
    val raw: ByteArray,
    /** Wall-clock time the packet was received (defaults to construction/parse time). */
    val receivedAtMs: Long = System.currentTimeMillis(),
    /** meshcore.ca region (IATA) the packet was observed in, from the MQTT topic; null for BLE. */
    val region: String? = null,
) {
    val snrDb: Double get() = snrQ / 4.0
    val length: Int get() = raw.size
    val header: Int get() = if (raw.isNotEmpty()) raw[0].toInt() and 0xFF else 0
    val payloadType: Int get() = (header shr 2) and 0x0F
    val routeType: Int get() = header and 0x03
    val typeName: String get() = PayloadType.name(payloadType)
    val routeName: String get() = RouteType.name(routeType)
    val hex: String get() = raw.toHex()

    /**
     * Full structural decode of [raw], parsed once and cached for the life of this instance.
     * The same RxLog objects flow through ingestion and every UI derivation (filters, node
     * resolution, grouping, per-row rendering), so they all share this one parse instead of
     * re-decoding the same bytes on the main thread. Thread-safe: ingestion and Compose may
     * touch it concurrently.
     */
    val parsed: ParsedPacket by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { PacketInspector.parse(raw) }

    override fun equals(other: Any?) = other is RxLog && raw.contentEquals(other.raw) && rssi == other.rssi
    override fun hashCode() = raw.contentHashCode() * 31 + rssi
}

/** RESP_CODE_STATS, STATS_TYPE_CORE. */
data class CoreStats(
    val batteryMilliVolts: Int,
    val uptimeSecs: Long,
    val errFlags: Int,
    val txQueueLen: Int,
)

/** RESP_CODE_STATS, STATS_TYPE_RADIO. */
data class RadioStats(
    val noiseFloor: Int,
    val lastRssi: Int,
    val lastSnrQ: Int,
    val txAirtimeSecs: Long,
    val rxAirtimeSecs: Long,
) {
    val lastSnrDb: Double get() = lastSnrQ / 4.0
}

/** RESP_CODE_DEVICE_INFO (reply to CMD_DEVICE_QUERY). */
data class DeviceInfo(
    val firmwareVerCode: Int,
    val maxContacts: Int,
    val maxChannels: Int,
    val blePin: Long,
    val buildDate: String,
    val manufacturer: String,
    val firmwareVersion: String,
    val clientRepeat: Boolean,
    val pathHashMode: Int,
)

/** RESP_CODE_TUNING_PARAMS (values are stored ×1000 on the wire). */
data class TuningParams(val rxDelayBase: Double, val airtimeFactor: Double)

/** One firmware custom variable / sensor setting (RESP_CODE_CUSTOM_VARS `name:value`). */
data class CustomVar(val name: String, val value: String)

/**
 * RESP_CODE_ADVERT_PATH — the locally-cached flood path that a contact's advert last took to
 * reach this node, and when it was received. [pathLen] encodes the route: hop count = low 6 bits,
 * bytes-per-hop hash = (pathLen>>6)+1. [path] is the raw path bytes (hopCount×hashSize).
 */
data class AdvertPathInfo(val recvTimestamp: Long, val pathLen: Int, val path: ByteArray) {
    val hopCount: Int get() = pathLen and 63
    val hashSize: Int get() = (pathLen shr 6) + 1
    /** Single-byte hop hashes (only when hashSize == 1, the common path-hash mode); else null. */
    val singleByteHops: List<Int>? get() =
        if (hashSize == 1) path.map { it.toInt() and 0xFF } else null
    val hex: String get() = path.toHex()

    override fun equals(other: Any?) = other is AdvertPathInfo &&
        recvTimestamp == other.recvTimestamp && pathLen == other.pathLen && path.contentEquals(other.path)
    override fun hashCode() = (recvTimestamp.hashCode() * 31 + pathLen) * 31 + path.contentHashCode()
}

/**
 * A received raw custom-payload packet (PUSH_CODE_RAW_DATA) — PAYLOAD_TYPE_RAW_CUSTOM bytes
 * an app defines itself. [snrQ] is SNR×4; [payload] is the raw application bytes.
 */
data class RawDataFrame(
    val snrQ: Int,
    val rssi: Int,
    val payload: ByteArray,
    val receivedAtMs: Long = System.currentTimeMillis(),
) {
    val snrDb: Double get() = snrQ / 4.0
    val hex: String get() = payload.toHex()

    override fun equals(other: Any?) = other is RawDataFrame &&
        snrQ == other.snrQ && rssi == other.rssi && payload.contentEquals(other.payload) &&
        receivedAtMs == other.receivedAtMs
    override fun hashCode() = (snrQ * 31 + rssi) * 31 + payload.contentHashCode()
}

/** RESP_CODE_AUTOADD_CONFIG: [AutoAdd] flag bitmask + max hops. */
data class AutoAddConfig(val flags: Int, val maxHops: Int)

/** RESP_CODE_STATS, STATS_TYPE_PACKETS. */
data class PacketStats(
    val recv: Long,
    val sent: Long,
    val sentFlood: Long,
    val sentDirect: Long,
    val recvFlood: Long,
    val recvDirect: Long,
    val recvErrors: Long,
)
