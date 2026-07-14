package org.thepacket.meshcore.app

import android.content.Context
import org.thepacket.meshcore.protocol.ContactType

/**
 * User preferences for the aggregate address book. [pushTypes] selects which contact *types* get
 * pushed onto the connected device (the book fills with every observed node from adverts, but only
 * these types are worth adding to a companion). Defaults to chat contacts only.
 */
class ContactPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("contacts", Context.MODE_PRIVATE)

    var pushTypes: Set<Int>
        get() = prefs.getStringSet(KEY_PUSH_TYPES, null)?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: setOf(ContactType.CHAT)
        set(v) { prefs.edit().putStringSet(KEY_PUSH_TYPES, v.map { it.toString() }.toSet()).apply() }

    private companion object { const val KEY_PUSH_TYPES = "pushTypes" }
}
