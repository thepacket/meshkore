package org.thepacket.meshcore.protocol

/**
 * Cayenne LPP decoder for MeshCore telemetry buffers (see src/helpers/sensors/LPPDataHelpers.h).
 * Layout: repeated [channel(1)][type(1)][big-endian data(N)]; channel 0 = end-of-data.
 */
object Lpp {
    const val ANALOG_INPUT = 2
    const val ANALOG_OUTPUT = 3
    const val LUMINOSITY = 101
    const val TEMPERATURE = 103
    const val RELATIVE_HUMIDITY = 104
    const val BAROMETRIC_PRESSURE = 115
    const val VOLTAGE = 116
    const val CURRENT = 117
    const val ALTITUDE = 121
    const val POWER = 128
    const val DIRECTION = 132
    const val GPS = 136

    /** One decoded telemetry reading. [values] has 3 entries for GPS (lat, lon, alt), else 1. */
    data class Reading(val channel: Int, val type: Int, val values: List<Double>)

    fun decode(buf: ByteArray): List<Reading> {
        val out = ArrayList<Reading>()
        var i = 0
        while (i + 2 <= buf.size) {
            val ch = buf[i].toInt() and 0xFF
            val type = buf[i + 1].toInt() and 0xFF
            if (ch == 0) break // end-of-data
            i += 2
            val size = dataSize(type)
            if (size <= 0 || i + size > buf.size) break
            out.add(Reading(ch, type, readValues(type, buf, i, size)))
            i += size
        }
        return out
    }

    /** Decode one scalar value of [type] at [off] (used for MMA min/max/avg triples). */
    fun decodeValue(type: Int, buf: ByteArray, off: Int): Double =
        readValues(type, buf, off, dataSize(type)).firstOrNull() ?: 0.0

    private fun readValues(type: Int, buf: ByteArray, off: Int, size: Int): List<Double> = when (type) {
        GPS -> listOf(
            getFloat(buf, off, 3, 10000, true),
            getFloat(buf, off + 3, 3, 10000, true),
            getFloat(buf, off + 6, 3, 100, true),
        )
        TEMPERATURE -> listOf(getFloat(buf, off, 2, 10, true))
        RELATIVE_HUMIDITY -> listOf(getFloat(buf, off, 1, 2, false))
        BAROMETRIC_PRESSURE -> listOf(getFloat(buf, off, 2, 10, false))
        VOLTAGE -> listOf(getFloat(buf, off, 2, 100, false))
        CURRENT -> listOf(getFloat(buf, off, 2, 1000, true))
        POWER -> listOf(getFloat(buf, off, 2, 1, false))
        ALTITUDE -> listOf(getFloat(buf, off, 2, 1, true))
        ANALOG_INPUT, ANALOG_OUTPUT -> listOf(getFloat(buf, off, 2, 100, true))
        LUMINOSITY, DIRECTION -> listOf(getFloat(buf, off, 2, 1, false))
        else -> listOf(getFloat(buf, off, size, 1, false)) // best-effort for unknown types
    }

    /** Total data byte length for a type (mirrors LPPReader::skipData). */
    fun dataSize(type: Int): Int = when (type) {
        GPS -> 9
        240 -> 8                    // POLYLINE (minimum)
        113, 134 -> 6               // accelerometer / gyrometer
        100, 118, 130, 131, 133 -> 4 // generic / frequency / distance / energy / unixtime
        135 -> 3                    // colour
        ANALOG_INPUT, ANALOG_OUTPUT, LUMINOSITY, TEMPERATURE, 125, BAROMETRIC_PRESSURE,
        ALTITUDE, VOLTAGE, CURRENT, DIRECTION, POWER -> 2
        else -> 1
    }

    /** Big-endian, value/multiplier, two's-complement when [signed] (matches LPPReader::getFloat). */
    private fun getFloat(buf: ByteArray, off: Int, size: Int, mult: Int, signed: Boolean): Double {
        var value = 0L
        for (i in 0 until size) value = (value shl 8) + (buf[off + i].toLong() and 0xFF)
        var sign = 1
        if (signed) {
            val bit = 1L shl (size * 8 - 1)
            if (value and bit == bit) { value = (bit shl 1) - value; sign = -1 }
        }
        return sign * (value.toDouble() / mult)
    }
}
