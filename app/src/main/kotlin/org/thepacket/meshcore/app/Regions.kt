package org.thepacket.meshcore.app

/**
 * Bucket for companion traffic heard before a Home region was chosen. Four characters, so it can
 * never collide with a three-letter IATA code in [MqttPrefs.REGIONS].
 */
const val HOME_UNSET = "HOME"

/**
 * Sentinel region meaning "all regions": the MQTT subscription widens to every region's topic
 * (`meshcore/+/+/packets`) and region-scoped views merge every bucket. Packets still carry their own
 * concrete region, so 1-byte hash resolution stays region-scoped — only the intake/display widen.
 */
const val ALL_REGIONS = "+"

/**
 * Region tag for a message our own companion radio received (as opposed to one observed over MQTT in
 * a named region). Shown on the chat row as "radio". Lowercase, so it can't collide with an IATA code.
 */
const val RADIO_REGION = "radio"

/** Whether a view on [selected] should show an item filed under [itemRegion]. "All" matches everything. */
fun regionMatches(selected: String, itemRegion: String): Boolean =
    selected == ALL_REGIONS || selected == itemRegion

/**
 * The region a packet or contact belongs to.
 *
 * MQTT packets carry their region in the topic. Traffic the companion hears carries none, and
 * belongs to wherever its radio is — [home], which only the user can tell us. Resolution happens
 * here at read time rather than being stored, so choosing (or changing) Home re-attributes every
 * companion-heard packet and contact already on disk.
 */
fun regionOf(region: String?, home: String?): String = region ?: home ?: HOME_UNSET

/** Label for [region] in the UI, spelling out the unset-Home bucket. */
fun regionLabel(region: String): String =
    if (region == HOME_UNSET) "Home (not set)"
    else if (region == RADIO_REGION) "radio"
    else MqttPrefs.REGIONS.firstOrNull { it.second == region }?.first ?: region
