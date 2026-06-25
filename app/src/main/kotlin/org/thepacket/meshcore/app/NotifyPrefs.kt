package org.thepacket.meshcore.app

import android.content.Context

/**
 * User preferences for background message notifications. Both default ON so the app
 * notifies on everything out of the box (the choice the user made: DMs + channels).
 */
class NotifyPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notify", Context.MODE_PRIVATE)

    var notifyDirect: Boolean
        get() = prefs.getBoolean(KEY_DIRECT, true)
        set(v) { prefs.edit().putBoolean(KEY_DIRECT, v).apply() }

    var notifyChannels: Boolean
        get() = prefs.getBoolean(KEY_CHANNELS, true)
        set(v) { prefs.edit().putBoolean(KEY_CHANNELS, v).apply() }

    private companion object {
        const val KEY_DIRECT = "direct"
        const val KEY_CHANNELS = "channels"
    }
}
