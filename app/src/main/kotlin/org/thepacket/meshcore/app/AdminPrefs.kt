package org.thepacket.meshcore.app

import android.content.Context

/**
 * Remembered repeater/room admin passwords, keyed by the node's 6-byte key-prefix hex.
 * A password is stored only after the node accepts it (LOGIN_SUCCESS), then reused for
 * silent auto-login the next time that node's management screen or room chat is opened.
 * Blank is a valid stored password (public rooms accept it).
 */
class AdminPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("admin_passwords", Context.MODE_PRIVATE)

    /** The last password this node accepted, or null if we never logged in to it. */
    fun password(prefixHex: String): String? =
        if (prefs.contains(prefixHex)) prefs.getString(prefixHex, "") else null

    fun save(prefixHex: String, password: String) {
        prefs.edit().putString(prefixHex, password).apply()
    }

    fun forget(prefixHex: String) {
        prefs.edit().remove(prefixHex).apply()
    }
}
