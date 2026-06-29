package org.thepacket.meshcore.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One channel from the community region catalog, with its 16-byte secret already resolved. */
data class RegionChannel(val name: String, val description: String, val secret: ByteArray) {
    override fun equals(other: Any?) = other is RegionChannel &&
        name == other.name && secret.contentEquals(other.secret)
    override fun hashCode() = name.hashCode() * 31 + secret.contentHashCode()
}

/**
 * Fetches the community catalog of MeshCore channels grouped by country
 * (github.com/marcelverdult/meshcore-channels). Hashtag channels (`#name`) derive their key
 * from the name; other entries carry an explicit base64 key.
 */
object RegionChannels {
    private const val URL =
        "https://raw.githubusercontent.com/marcelverdult/meshcore-channels/main/channels-by-country.json"

    /** MeshCore hashtag-channel key: first 16 bytes of SHA-256 of the "#name" (UTF-8). */
    fun hashChannelSecret(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)

    /** Country code (ISO-2, lowercase) → its channels. Only non-empty countries are returned. */
    suspend fun fetch(): Map<String, List<RegionChannel>> = withContext(Dispatchers.IO) {
        val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        val text = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val countries = JSONObject(text).getJSONObject("countries")
        buildMap {
            for (code in countries.keys()) {
                val arr = countries.getJSONArray(code)
                if (arr.length() == 0) continue
                val list = ArrayList<RegionChannel>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ch = o.getString("channel")
                    val secret = if (o.has("key"))
                        runCatching { Base64.decode(o.getString("key"), Base64.DEFAULT) }.getOrNull()
                    else hashChannelSecret(ch)
                    if (secret != null && secret.size >= 16)
                        list.add(RegionChannel(ch, o.optString("description", ""), secret.copyOf(16)))
                }
                if (list.isNotEmpty()) put(code, list.sortedBy { it.name })
            }
        }
    }
}
