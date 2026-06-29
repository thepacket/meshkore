package org.thepacket.meshcore.app

import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.hexToBytes
import org.thepacket.meshcore.protocol.toHex

/**
 * A recently-heard station (from advert pushes), with signal correlated from the
 * most recent advert-type packet in the RX feed. gps in 1e-6 degrees (0 = unknown).
 */
data class HeardEntry(
    val pubKeyHex: String,
    val name: String,
    val type: Int,
    val lastHeardMs: Long,
    val snrQ: Int? = null,
    val rssi: Int? = null,
    val gpsLat: Int = 0,
    val gpsLon: Int = 0,
) {
    val snrDb: Double? get() = snrQ?.let { it / 4.0 }
    val hasGps: Boolean get() = gpsLat != 0 || gpsLon != 0
    val latDeg: Double get() = gpsLat / 1e6
    val lonDeg: Double get() = gpsLon / 1e6
}

/** Great-circle distance in km between two lat/lon pairs (degrees). */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/**
 * MeshCore's well-known **Public** channel: always slot 0, name "Public", and the standard
 * 128-bit PSK (base64 `izOH6cXN6mrJ5e26oRXNcg==`). It's reserved and protected from edits so it
 * can't be accidentally renamed, re-keyed, or deleted — and can be restored if it drifts.
 */
object PublicChannel {
    const val INDEX = 0
    const val NAME = "Public"
    val SECRET: ByteArray = "8b3387e9c5cdea6ac9e5edbaa115cd72".hexToBytes()

    /** True if [e] is exactly the canonical Public channel (right slot, name, and key). */
    fun isCanonical(e: ChannelEntry): Boolean =
        e.index == INDEX && e.name == NAME && e.secret.size >= 16 && e.secret.copyOf(16).contentEquals(SECRET)
}

/** A group channel slot enumerated from the device. [secret] is the 128-bit key (empty if unknown). */
data class ChannelEntry(val index: Int, val name: String, val secret: ByteArray = ByteArray(0)) {
    val displayName: String get() = name.ifBlank { if (index == 0) "Public" else "Channel $index" }

    override fun equals(other: Any?) = other is ChannelEntry &&
        index == other.index && name == other.name && secret.contentEquals(other.secret)
    override fun hashCode() = (index * 31 + name.hashCode()) * 31 + secret.contentHashCode()
}

/** Delivery lifecycle of a chat message. */
enum class MsgStatus { Sending, Sent, Delivered, Failed, Received }

/** A single message in a conversation (UI model, in-memory only — see roadmap M7 for persistence). */
data class ChatMessage(
    val localId: Long,
    val conversationId: String,
    val text: String,
    val timestampSecs: Long,
    val incoming: Boolean,
    val status: MsgStatus,
    val snrDb: Double? = null,
    val expectedAck: Long = 0,
    /** For room-server posts: hex of the author's 4-byte key prefix (null for normal DMs/channels). */
    val authorPrefix: String? = null,
)

/**
 * A conversation target: either a direct contact or a group channel. [id] is the stable
 * key used to bucket messages and to navigate.
 */
sealed interface Conversation {
    val id: String
    val title: String

    data class Direct(val contact: Contact) : Conversation {
        override val id: String get() = dmId(contact)
        override val title: String get() = contact.name.ifBlank { contact.keyPrefixHex }
    }

    data class Channel(val index: Int, val name: String) : Conversation {
        override val id: String get() = channelId(index)
        override val title: String get() = name
    }

    companion object {
        fun dmId(contact: Contact): String = contact.publicKey.copyOf(6).toHex()
        fun dmId(pubKeyPrefix6: ByteArray): String = pubKeyPrefix6.copyOf(6).toHex()
        fun channelId(index: Int): String = "ch:$index"
    }
}
