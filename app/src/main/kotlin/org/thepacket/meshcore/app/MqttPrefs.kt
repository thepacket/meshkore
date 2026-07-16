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

    /** Selected region IATA code, or "+" for all regions (subscribes to every region's topic). */
    var region: String
        get() = prefs.getString(KEY_REGION, DEFAULT_REGION) ?: DEFAULT_REGION
        set(v) { prefs.edit().putString(KEY_REGION, v.trim()).apply() }

    /**
     * Where the BLE companion physically is, as an IATA code from [REGIONS]. Packets the companion
     * hears carry no region of their own, so this is what attributes them to a place. Null until the
     * user chooses: only they know where their radio is, and guessing files their traffic under
     * someone else's city. See [regionOf].
     */
    var homeRegion: String?
        get() = prefs.getString(KEY_HOME, null)?.takeIf { it.isNotBlank() }
        set(v) { prefs.edit().putString(KEY_HOME, v?.trim()?.takeIf { it.isNotBlank() }).apply() }

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
        /** Region the feed observes until the user picks another. Says nothing about where *they* are. */
        const val DEFAULT_REGION = "YOW" // Ottawa

        /** Selectable brokers as (label, hidden URL). */
        val BROKERS: List<Pair<String, String>> = listOf(
            "Primary" to "wss://mqtt1.meshcore.ca:443/mqtt",
            "Secondary" to "wss://mqtt2.meshcore.ca:443/mqtt",
        )

        /**
         * Selectable regions as (city, IATA). Canadian cities first (the app's home turf), then the
         * international regions seen on the meshcore.ca feed so they show a city name instead of a raw
         * code. Any region not listed still works — it just displays as its bare code (see [regionLabel]).
         */
        val REGIONS: List<Pair<String, String>> = listOf(
            "All" to "+",
            // Canada
            "Toronto" to "YYZ",
            "Vancouver" to "YVR",
            "Montréal" to "YUL",
            "Calgary" to "YYC",
            "Edmonton" to "YEG",
            "Ottawa" to "YOW",
            "Halifax" to "YHZ",
            "Winnipeg" to "YWG",
            "Québec City" to "YQB",
            "Hamilton" to "YHM",
            "London" to "YXU",
            "Saskatoon" to "YXE",
            "Victoria" to "YYJ",
            "Kingston" to "YGK",
            "Kitchener" to "YKF",
            "Kamloops" to "YKA",
            "Nanaimo" to "YCD",
            "Thunder Bay" to "YQT",
            "Lethbridge" to "YQL",
            "Saint John" to "YSJ",
            "Prince Albert" to "YPA",
            "Muskoka" to "YQA",
            "Barrie" to "YLK",
            "Trenton" to "YTR",
            "Pembroke" to "YTA",
            "Alma" to "YTF",
            "St-Jean" to "YJN",
            "St-Hubert" to "YHU",
            // International
            "Appleton" to "ATW",
            "Barcelona" to "BCN",
            "Cape Town" to "CPT",
            "New Bern" to "EWN",
            "Münster" to "FMO",
            "Spokane" to "GEG",
            "Greensboro" to "GSO",
            "Łódź" to "LCJ",
            "Lisbon" to "LIS",
            "Linz" to "LNZ",
            "Manchester" to "MAN",
            "Milwaukee" to "MKE",
            "Prague" to "PRG",
            "Pontiac" to "PTK",
            "Rotterdam" to "RTM",
            "Seattle" to "SEA",
            "San Francisco" to "SFO",
            "St. George" to "SGU",
            "Salt Lake City" to "SLC",
            "Orange County" to "SNA",
            "Warsaw" to "WAW",
        )
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BROKER = "broker"
        private const val KEY_REGION = "region"
        private const val KEY_HOME = "home"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
    }
}
