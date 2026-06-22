package org.thepacket.meshcore.protocol

/**
 * Little-endian frame writer. MeshCore frames are packed structs with no padding;
 * all multi-byte integers are little-endian.
 */
class FrameWriter(initialCapacity: Int = 32) {
    private var buf = ByteArray(initialCapacity)
    private var len = 0

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) {
            var n = buf.size * 2
            while (n < len + extra) n *= 2
            buf = buf.copyOf(n)
        }
    }

    fun u8(v: Int) = apply {
        ensure(1)
        buf[len++] = (v and 0xFF).toByte()
    }

    fun u16(v: Int) = apply {
        ensure(2)
        buf[len++] = (v and 0xFF).toByte()
        buf[len++] = ((v ushr 8) and 0xFF).toByte()
    }

    fun u32(v: Long) = apply {
        ensure(4)
        buf[len++] = (v and 0xFF).toByte()
        buf[len++] = ((v ushr 8) and 0xFF).toByte()
        buf[len++] = ((v ushr 16) and 0xFF).toByte()
        buf[len++] = ((v ushr 24) and 0xFF).toByte()
    }

    fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun bytes(b: ByteArray) = apply {
        ensure(b.size)
        b.copyInto(buf, len)
        len += b.size
    }

    /** Raw UTF-8 bytes, no terminator. */
    fun str(s: String) = bytes(s.toByteArray(Charsets.UTF_8))

    fun build(): ByteArray = buf.copyOf(len)
}

/**
 * Little-endian frame reader over an immutable byte array. Reads advance an
 * internal cursor. Out-of-bounds reads throw [IndexOutOfBoundsException].
 */
class FrameReader(private val data: ByteArray, start: Int = 0) {
    var pos: Int = start
        private set

    val remaining: Int get() = data.size - pos

    fun u8(): Int = data[pos++].toInt() and 0xFF

    fun u16(): Int {
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        val v = (data[pos].toLong() and 0xFF) or
            ((data[pos + 1].toLong() and 0xFF) shl 8) or
            ((data[pos + 2].toLong() and 0xFF) shl 16) or
            ((data[pos + 3].toLong() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun i8(): Int = data[pos++].toInt()

    fun i16(): Int = u16().toShort().toInt()

    fun i32(): Int = u32().toInt()

    fun bytes(n: Int): ByteArray {
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** Reads a fixed-width field and trims at the first NUL (C string in a fixed buffer). */
    fun cstr(width: Int): String {
        val raw = bytes(width)
        var end = 0
        while (end < raw.size && raw[end].toInt() != 0) end++
        return String(raw, 0, end, Charsets.UTF_8)
    }

    /** Reads the rest of the buffer as a UTF-8 string (trimming a trailing NUL if present). */
    fun restAsString(): String {
        var end = data.size
        if (end > pos && data[end - 1].toInt() == 0) end--
        val s = String(data, pos, end - pos, Charsets.UTF_8)
        pos = data.size
        return s
    }

    fun rest(): ByteArray = bytes(remaining)
}

/** Lowercase hex, no separators — handy for pubkeys and prefixes. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Parses an even-length hex string into bytes. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }
}
