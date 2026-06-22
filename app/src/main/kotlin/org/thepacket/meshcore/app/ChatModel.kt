package org.thepacket.meshcore.app

import org.thepacket.meshcore.protocol.Contact
import org.thepacket.meshcore.protocol.toHex

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
