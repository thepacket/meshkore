package org.thepacket.meshcore.app

/**
 * Bucket for companion traffic heard before a Home region was chosen. Four characters, so it can
 * never collide with a three-letter IATA code in [MqttPrefs.REGIONS].
 */
const val HOME_UNSET = "HOME"

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
    else MqttPrefs.REGIONS.firstOrNull { it.second == region }?.first ?: region
