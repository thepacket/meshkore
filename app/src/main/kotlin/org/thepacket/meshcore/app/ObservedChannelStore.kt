package org.thepacket.meshcore.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.thepacket.meshcore.protocol.hexToBytes
import org.thepacket.meshcore.protocol.toHex
import java.io.File

/**
 * On-disk persistence for [ObservedChannel]s — the keys we listen with when observing.
 *
 * Separate from the companion's own channels by design: those live in device slots and are read back
 * over BLE, whereas these exist only here, survive with no device attached at all, and are what makes
 * observed traffic decodable.
 *
 * Stored as a single JSON array in app-private storage, mirroring [ContactStore].
 */
class ObservedChannelStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "observed_channels.json"))

    fun load(): List<ObservedChannel> {
        if (!file.exists()) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(channels: List<ObservedChannel>) {
        runCatching { file.writeText(encode(channels)) } // best-effort; losing one save is non-fatal
    }

    companion object {
        /** Serialize as a JSON array — also used for export. */
        fun encode(channels: List<ObservedChannel>): String {
            val arr = JSONArray()
            channels.forEach { c ->
                arr.put(JSONObject().apply {
                    put("name", c.name)
                    put("secret", c.secret.toHex())
                })
            }
            return arr.toString(2)
        }

        /**
         * Parse a JSON array produced by [encode]; skips entries without a 128-bit key. Channels are
         * global, so a legacy file that stored the same key once per region collapses to one entry
         * here (first name wins); the next [save] rewrites it region-free.
         */
        fun decode(text: String): List<ObservedChannel> {
            val arr = JSONArray(text)
            val byKey = LinkedHashMap<String, ObservedChannel>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val secret = o.optString("secret", "").hexToBytes()
                if (secret.size < 16) continue
                val key = secret.copyOf(16)
                byKey.getOrPut(key.toHex()) { ObservedChannel(o.optString("name", ""), key) }
            }
            return byKey.values.toList()
        }
    }
}
