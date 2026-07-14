package org.thepacket.meshcore.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.hexToBytes
import org.thepacket.meshcore.protocol.toHex
import java.io.File

/**
 * On-disk persistence for the *aggregate* address book: the union of every contact seen on
 * any device this app has connected to. Deduped by full public key, it survives disconnects
 * and app restarts so the user can later push the whole set onto a freshly-connected device.
 *
 * Stored as a single JSON array in app-private storage (no extra deps). Each entry carries
 * the full [Contact] fields needed to re-add it via ADD_UPDATE_CONTACT.
 */
class ContactStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "all_contacts.json"))

    fun load(): List<Contact> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val key = o.getString("pubkey").hexToBytes()
                    if (key.isEmpty()) continue
                    add(
                        Contact(
                            publicKey = key,
                            type = o.getInt("type"),
                            flags = o.optInt("flags", 0),
                            outPathLen = o.optInt("outPathLen", 0xFF),
                            outPath = o.optString("outPath", "").hexToBytes().copyOf(64),
                            name = o.optString("name", ""),
                            lastAdvert = o.optLong("lastAdvert", 0),
                            gpsLat = o.optInt("gpsLat", 0),
                            gpsLon = o.optInt("gpsLon", 0),
                            lastMod = o.optLong("lastMod", 0),
                            region = o.optString("region", "").ifBlank { null },
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList() // corrupt/old file — start clean rather than crash
        }
    }

    fun save(contacts: List<Contact>) {
        try {
            val arr = JSONArray()
            contacts.forEach { c ->
                arr.put(JSONObject().apply {
                    put("pubkey", c.publicKey.toHex())
                    put("type", c.type)
                    put("flags", c.flags)
                    put("outPathLen", c.outPathLen)
                    put("outPath", c.outPath.toHex())
                    put("name", c.name)
                    put("lastAdvert", c.lastAdvert)
                    put("gpsLat", c.gpsLat)
                    put("gpsLon", c.gpsLon)
                    put("lastMod", c.lastMod)
                    c.region?.let { put("region", it) }
                })
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
            // best-effort; losing one save is non-fatal
        }
    }
}
