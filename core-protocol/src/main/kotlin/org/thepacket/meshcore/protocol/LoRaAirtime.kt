package org.thepacket.meshcore.protocol

import kotlin.math.ceil
import kotlin.math.max

/**
 * LoRa time-on-air estimate (Semtech SX126x formula, as RadioLib's getTimeOnAir),
 * with MeshCore's preamble rule: 32 symbols for SF ≤ 8, else 16. Assumes an explicit
 * header and CRC on (MeshCore uses both), and low-data-rate optimization when the
 * symbol time exceeds 16 ms (SF11/SF12 at 125 kHz).
 */
object LoRaAirtime {

    /** Estimated on-air time in ms for a [lenBytes] packet, or null if params are unknown. */
    fun airtimeMs(lenBytes: Int, bwKhz: Double, sf: Int, cr: Int): Double? {
        if (lenBytes <= 0 || bwKhz <= 0 || sf < 5 || sf > 12 || cr < 5 || cr > 8) return null
        val tSymMs = (1 shl sf) / bwKhz
        val deOpt = if (tSymMs > 16.0) 1 else 0
        val preambleSyms = if (sf <= 8) 32 else 16
        val num = 8.0 * lenBytes - 4.0 * sf + 28 + 16 // explicit header, CRC on
        val payloadSyms = 8 + max(ceil(num / (4.0 * (sf - 2 * deOpt))) * cr, 0.0)
        return (preambleSyms + 4.25 + payloadSyms) * tSymMs
    }
}
