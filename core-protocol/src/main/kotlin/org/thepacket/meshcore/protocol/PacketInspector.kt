package org.thepacket.meshcore.protocol

/**
 * Structural decode of a raw MeshCore packet (the bytes carried by PUSH_CODE_LOG_RX_DATA).
 *
 * Wire format (Packet.cpp writeTo/readFrom):
 *   header(1)
 *   [transport_codes: u16 + u16]  — only when route is TRANSPORT_FLOOD/DIRECT
 *   path_len(1)  — low 6 bits = hash count, top 2 bits = (hash size − 1)
 *   path bytes   — count × size; each hop hash is a prefix of that hop's public key
 *   payload(...) — type-specific
 *
 * The 1-byte src/dest/path hashes are just `pub_key[0]` (PATH_HASH_SIZE = 1), so they can be
 * resolved to a contact by matching the first public-key byte. Payloads are encrypted; only
 * the cleartext routing prefixes (and the fully-public ADVERT body) are recoverable.
 */
data class ParsedPacket(
    val header: Int,
    val routeType: Int,
    val payloadType: Int,
    val version: Int,
    val payloadLen: Int,
    val transportCodes: IntArray? = null,
    val pathHashSize: Int = 1,
    val pathHashes: List<Int> = emptyList(),
    val destHash: Int? = null,
    val srcHash: Int? = null,
    val channelHash: Int? = null,
    val mac: ByteArray? = null,
    val advertPubKey: ByteArray? = null,
    val advertType: Int? = null,
    val advertName: String? = null,
    val advertTimestamp: Long? = null,
    val advertLat: Int? = null,
    val advertLon: Int? = null,
    val ephemeralPubKey: ByteArray? = null,
    val ackCode: ByteArray? = null,
    val traceTag: Long? = null,
) {
    val routeName: String get() = RouteType.name(routeType)
    val typeName: String get() = PayloadType.name(payloadType)
    val isFlood: Boolean get() = routeType == RouteType.FLOOD || routeType == RouteType.TRANSPORT_FLOOD
}

object PacketInspector {

    fun parse(raw: ByteArray): ParsedPacket {
        val header = if (raw.isNotEmpty()) raw[0].toInt() and 0xFF else 0
        val route = header and 0x03
        val type = (header ushr 2) and 0x0F
        val ver = (header ushr 6) and 0x03

        var i = 1
        var transport: IntArray? = null
        if ((route == RouteType.TRANSPORT_FLOOD || route == RouteType.TRANSPORT_DIRECT) && raw.size >= i + 4) {
            transport = intArrayOf(u16(raw, i), u16(raw, i + 2)); i += 4
        }

        var hashSize = 1
        val hops = ArrayList<Int>()
        if (raw.size > i) {
            val pl = raw[i].toInt() and 0xFF; i++
            val count = pl and 63
            hashSize = (pl ushr 6) + 1
            if (raw.size >= i + count * hashSize) {
                for (h in 0 until count) hops.add(raw[i + h * hashSize].toInt() and 0xFF)
                i += count * hashSize
            }
        }

        val p = if (i <= raw.size) raw.copyOfRange(i, raw.size) else ByteArray(0)

        var destHash: Int? = null; var srcHash: Int? = null; var channelHash: Int? = null
        var mac: ByteArray? = null; var ephem: ByteArray? = null; var ack: ByteArray? = null
        var advKey: ByteArray? = null; var advType: Int? = null; var advName: String? = null
        var advTs: Long? = null; var advLat: Int? = null; var advLon: Int? = null; var traceTag: Long? = null

        when (type) {
            PayloadType.REQ, PayloadType.RESPONSE, PayloadType.TXT_MSG, PayloadType.PATH ->
                if (p.size >= 4) { destHash = p[0].u(); srcHash = p[1].u(); mac = p.copyOfRange(2, 4) }
            PayloadType.GRP_TXT, PayloadType.GRP_DATA ->
                if (p.size >= 3) { channelHash = p[0].u(); mac = p.copyOfRange(1, 3) }
            PayloadType.ANON_REQ -> {
                if (p.isNotEmpty()) destHash = p[0].u()
                if (p.size >= 33) ephem = p.copyOfRange(1, 33)
                if (p.size >= 35) mac = p.copyOfRange(33, 35)
            }
            PayloadType.ACK -> if (p.size >= 4) ack = p.copyOfRange(0, 4)
            PayloadType.TRACE -> if (p.size >= 4) traceTag = u32(p, 0)
            PayloadType.ADVERT -> if (p.size >= 36) {
                advKey = p.copyOfRange(0, 32)
                advTs = u32(p, 32)
                // signature is p[36..99]; app_data follows
                if (p.size > 100) {
                    val ad = p.copyOfRange(100, p.size)
                    val flags = ad[0].u()
                    advType = flags and 0x0F
                    var j = 1
                    if (flags and 0x10 != 0 && ad.size >= j + 8) { advLat = i32(ad, j); advLon = i32(ad, j + 4); j += 8 }
                    if (flags and 0x80 != 0 && ad.size > j) advName = String(ad, j, ad.size - j, Charsets.UTF_8)
                }
            }
        }

        return ParsedPacket(
            header = header, routeType = route, payloadType = type, version = ver,
            payloadLen = p.size, transportCodes = transport, pathHashSize = hashSize, pathHashes = hops,
            destHash = destHash, srcHash = srcHash, channelHash = channelHash, mac = mac,
            advertPubKey = advKey, advertType = advType, advertName = advName, advertTimestamp = advTs,
            advertLat = advLat, advertLon = advLon, ephemeralPubKey = ephem, ackCode = ack, traceTag = traceTag,
        )
    }

    private fun Byte.u() = toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun i32(b: ByteArray, o: Int) = u32(b, o).toInt()
}
