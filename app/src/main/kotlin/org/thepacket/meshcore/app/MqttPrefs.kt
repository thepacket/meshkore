package org.thepacket.meshcore.app

import android.content.Context

/**
 * Settings for the optional meshcore.ca live-packet subscription (MQTT over WebSocket+TLS).
 * The broker is chosen from a fixed list (its URL is hidden); the region picks the topic.
 */
class MqttPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mqtt", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** Index into [BROKERS] of the selected broker. */
    var broker: Int
        get() = prefs.getInt(KEY_BROKER, 0).coerceIn(BROKERS.indices)
        set(v) { prefs.edit().putInt(KEY_BROKER, v).apply() }

    /** All broker URLs, in list order — the feed fails over across these starting from [broker]. */
    val brokerUrls: List<String> get() = BROKERS.map { it.second }

    /** Selected region IATA code. A previously-stored "+" (all regions, now removed) falls back to default. */
    var region: String
        get() = (prefs.getString(KEY_REGION, DEFAULT_REGION) ?: DEFAULT_REGION).let { if (it == "+") DEFAULT_REGION else it }
        set(v) { prefs.edit().putString(KEY_REGION, v.trim()).apply() }

    /** MQTT topic derived from the region — every node's packets under that region. */
    val topic: String get() = "meshcore/$region/+/packets"

    /** Username (blank for anonymous; the broker requires auth, so usually needed). */
    var username: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        set(v) { prefs.edit().putString(KEY_USER, v.trim()).apply() }

    /** Password or JWT token (the broker is JWT-gated — put the token here). */
    var password: String
        get() = prefs.getString(KEY_PASS, "") ?: ""
        set(v) { prefs.edit().putString(KEY_PASS, v.trim()).apply() }

    companion object {
        const val DEFAULT_REGION = "YOW" // Ottawa

        /** Selectable brokers as (label, hidden URL). */
        val BROKERS: List<Pair<String, String>> = listOf(
            "Primary" to "wss://mqtt1.meshcore.ca:443/mqtt",
            "Secondary" to "wss://mqtt2.meshcore.ca:443/mqtt",
        )

        /** Selectable regions as (city, IATA). */
        val REGIONS: List<Pair<String, String>> = listOf(
            "Toronto" to "YYZ",
            "Vancouver" to "YVR",
            "Montréal" to "YUL",
            "Calgary" to "YYC",
            "Edmonton" to "YEG",
            "Ottawa" to "YOW",
            "Halifax" to "YHZ",
            "Winnipeg" to "YWG",
        )
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BROKER = "broker"
        private const val KEY_REGION = "region"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
    }
}
