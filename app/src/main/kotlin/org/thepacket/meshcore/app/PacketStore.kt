package org.thepacket.meshcore.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.hexToBytes
import org.thepacket.meshcore.protocol.toHex
import java.io.File

/**
 * On-disk persistence for the RX packet history that feeds the Stats → Traffic analysis, so it
 * survives disconnects and app restarts (the live packet-monitor feed stays in-memory + capped).
 *
 * Stored as a single JSON array, newest-first, of the raw [RxLog] essentials (snr/rssi/raw/time).
 * Best-effort: a corrupt/old file just starts empty rather than crashing.
 */
class PacketStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "packets.json"))

    fun load(): List<RxLog> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val raw = o.optString("raw", "").hexToBytes()
                    if (raw.isEmpty()) continue
                    add(RxLog(
                        snrQ = o.optInt("snr", 0),
                        rssi = o.optInt("rssi", 0),
                        raw = raw,
                        receivedAtMs = o.optLong("ts", 0),
                        region = o.optString("rgn", "").ifBlank { null },
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(packets: List<RxLog>) {
        try {
            val arr = JSONArray()
            packets.forEach { p ->
                arr.put(JSONObject().apply {
                    put("snr", p.snrQ)
                    put("rssi", p.rssi)
                    put("raw", p.raw.toHex())
                    put("ts", p.receivedAtMs)
                    p.region?.let { put("rgn", it) }
                })
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
            // best-effort; losing one save is non-fatal
        }
    }
}
