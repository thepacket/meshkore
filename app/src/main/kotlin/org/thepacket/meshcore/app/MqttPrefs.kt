package org.thepacket.meshcore.app

import android.content.Context

/**
 * Settings for the optional meshcore.ca live-packet subscription (MQTT over WebSocket+TLS).
 * Anonymous read (blank credentials); the broker URL and topic filter are user-editable so a
 * different region/IATA or the backup broker can be used.
 */
class MqttPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mqtt", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** Full WebSocket URL of the broker. */
    var brokerUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(v) { prefs.edit().putString(KEY_URL, v.trim()).apply() }

    /** Selected region IATA code ("+" = all regions). */
    var region: String
        get() = prefs.getString(KEY_REGION, DEFAULT_REGION) ?: DEFAULT_REGION
        set(v) { prefs.edit().putString(KEY_REGION, v.trim()).apply() }

    /** MQTT topic derived from the region — every node's packets under it (or all regions). */
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
        const val DEFAULT_URL = "wss://mqtt1.meshcore.ca:443/mqtt"
        const val DEFAULT_REGION = "YOW" // Ottawa

        /** Selectable regions as (city, IATA); "+" = all regions (MQTT single-level wildcard). */
        val REGIONS: List<Pair<String, String>> = listOf(
            "All regions" to "+",
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
        private const val KEY_URL = "url"
        private const val KEY_REGION = "region"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
    }
}
