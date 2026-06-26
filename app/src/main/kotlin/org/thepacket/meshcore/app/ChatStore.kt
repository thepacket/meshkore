package org.thepacket.meshcore.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Simple on-disk persistence for chat history so conversations survive disconnects and
 * app restarts. Stored as a single JSON file in app-private storage (no extra deps).
 *
 * Keyed by conversationId → list of messages (oldest first), mirroring [MeshSession]'s
 * in-memory map.
 */
class ChatStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "chats.json"))

    fun load(): Map<String, List<ChatMessage>> {
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            buildMap {
                for (key in root.keys()) {
                    val arr = root.getJSONArray(key)
                    val list = ArrayList<ChatMessage>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            ChatMessage(
                                localId = o.getLong("id"),
                                conversationId = key,
                                text = o.getString("text"),
                                timestampSecs = o.getLong("ts"),
                                incoming = o.getBoolean("in"),
                                status = runCatching { MsgStatus.valueOf(o.getString("st")) }
                                    .getOrDefault(MsgStatus.Received),
                                snrDb = if (o.has("snr") && !o.isNull("snr")) o.getDouble("snr") else null,
                                expectedAck = o.optLong("ack", 0),
                                authorPrefix = if (o.has("auth") && !o.isNull("auth")) o.getString("auth") else null,
                            )
                        )
                    }
                    put(key, list)
                }
            }
        } catch (_: Exception) {
            emptyMap() // corrupt/old file — start clean rather than crash
        }
    }

    fun save(map: Map<String, List<ChatMessage>>) {
        try {
            val root = JSONObject()
            map.forEach { (key, list) ->
                val arr = JSONArray()
                list.forEach { m ->
                    arr.put(JSONObject().apply {
                        put("id", m.localId)
                        put("text", m.text)
                        put("ts", m.timestampSecs)
                        put("in", m.incoming)
                        put("st", m.status.name)
                        m.snrDb?.let { put("snr", it) }
                        if (m.expectedAck != 0L) put("ack", m.expectedAck)
                        m.authorPrefix?.let { put("auth", it) }
                    })
                }
                root.put(key, arr)
            }
            file.writeText(root.toString())
        } catch (_: Exception) {
            // best-effort; losing one save is non-fatal
        }
    }
}
