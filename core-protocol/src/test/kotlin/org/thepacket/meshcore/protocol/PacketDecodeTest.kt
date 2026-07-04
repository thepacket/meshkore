package org.thepacket.meshcore.protocol

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketDecodeTest {

    // ---- GroupCipher --------------------------------------------------------

    /** Firmware-side encryptThenMAC (Utils.cpp), reimplemented for the round-trip test. */
    private fun encryptThenMac(secret16: ByteArray, plain: ByteArray): ByteArray {
        val padded = plain.copyOf((plain.size + 15) / 16 * 16) // zero-pad to block size
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret16.copyOf(16), "AES"))
        val ciphertext = aes.doFinal(padded)
        val mac = Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(secret16.copyOf(32), "HmacSHA256")) }
            .doFinal(ciphertext)
        return mac.copyOf(2) + ciphertext
    }

    private val key = "8b3387e9c5cdea6ac9e5edbaa115cd72".hexToBytes() // the Public channel PSK

    @Test
    fun `group text round-trips through MAC-then-decrypt`() {
        val text = "Alice: hello mesh"
        val plain = byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x00) + text.toByteArray()
        val body = encryptThenMac(key, plain)

        val dec = GroupCipher.decrypt(key, body)
        assertNotNull(dec)
        val msg = GroupCipher.parseGroupText(dec!!)
        assertNotNull(msg)
        assertEquals(0x12345678L, msg!!.timestamp)
        assertEquals(0, msg.txtType)
        assertEquals(text, msg.text)
    }

    @Test
    fun `wrong key fails the MAC check`() {
        val body = encryptThenMac(key, byteArrayOf(0, 0, 0, 0, 0) + "hi".toByteArray())
        val wrong = ByteArray(16) { 0x42 }
        assertNull(GroupCipher.decrypt(wrong, body))
    }

    @Test
    fun `channel hash matches sha256 first byte of the 128-bit key`() {
        // sha256(key)[0], computed independently
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(key)[0].toInt() and 0xFF
        assertEquals(expected, GroupCipher.channelHash(key))
        // a 32-byte zero-padded secret must hash the same as the bare 16-byte key
        assertEquals(expected, GroupCipher.channelHash(key.copyOf(32)))
    }

    @Test
    fun `group data type parses little-endian`() {
        assertEquals(0x0201, GroupCipher.parseGroupDataType(byteArrayOf(0x01, 0x02, 0x33)))
    }

    // ---- TRACE decode -------------------------------------------------------

    @Test
    fun `trace packet decodes hop SNRs from the path and route from the payload`() {
        // header: route=FLOOD(1), type=TRACE(9), ver=0 -> 0b00_1001_01 = 0x25
        val header = (PayloadType.TRACE shl 2) or RouteType.FLOOD
        val raw = byteArrayOf(
            header.toByte(),
            2, // path_len: 2 hops, hash size 1
            20, (-6).toByte(), // per-hop SNRs (quarter-dB): +5.0 dB, -1.5 dB
            0x78, 0x56, 0x34, 0x12, // tag
            0x01, 0x02, 0x03, 0x04, // auth
            0x00, // flags: hash size 1
            0x3A, 0x7F, // target route hashes
        )
        val p = PacketInspector.parse(raw)
        assertEquals(PayloadType.TRACE, p.payloadType)
        assertEquals(0x12345678L, p.traceTag)
        assertEquals(listOf(20, -6), p.traceHopSnrsQ)
        assertEquals(listOf(0x3A, 0x7F), p.traceRoute)
        assertTrue("path should be empty", p.pathHashes.isEmpty()) // the path field holds SNRs, not hashes
    }

    // ---- airtime ------------------------------------------------------------

    @Test
    fun `airtime estimate is sane for typical MeshCore params`() {
        // SF7/250kHz/CR5, 20 B: Tsym=0.512ms, preamble 32 syms -> ~41 ms
        val t1 = LoRaAirtime.airtimeMs(20, 250.0, 7, 5)!!
        assertTrue("got $t1", t1 > 35 && t1 < 47)
        // SF11/250kHz/CR5, 50 B (typical NA MeshCore): symbol 8.19ms, no LDRO -> few hundred ms
        val t2 = LoRaAirtime.airtimeMs(50, 250.0, 11, 5)!!
        assertTrue("got $t2", t2 > 400 && t2 < 800)
        // longer packets take longer
        assertTrue(LoRaAirtime.airtimeMs(100, 250.0, 11, 5)!! > t2)
        // invalid params -> null
        assertNull(LoRaAirtime.airtimeMs(20, 0.0, 7, 5))
    }
}
