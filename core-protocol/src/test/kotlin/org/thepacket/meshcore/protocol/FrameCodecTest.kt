package org.thepacket.meshcore.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    @Test fun littleEndianRoundTrip() {
        val bytes = FrameWriter().u8(0x12).u16(0x3456).u32(0x89ABCDEFL).i32(-1).build()
        val r = FrameReader(bytes)
        assertEquals(0x12, r.u8())
        assertEquals(0x3456, r.u16())
        assertEquals(0x89ABCDEFL, r.u32())
        assertEquals(-1, r.i32())
        assertEquals(0, r.remaining)
    }

    @Test fun u16IsLittleEndianOnTheWire() {
        val bytes = FrameWriter().u16(0x3456).build()
        assertArrayEquals(byteArrayOf(0x56, 0x34), bytes)
    }

    @Test fun appStartFrameShape() {
        val f = Requests.appStart(appVer = 3, appName = "x")
        assertEquals(Cmd.APP_START, f[0].toInt() and 0xFF)
        assertEquals(3, f[1].toInt() and 0xFF)
        // 6 reserved bytes then the name
        assertEquals('x'.code.toByte(), f[8])
        assertEquals(9, f.size)
    }

    @Test fun sendTextMessageLayout() {
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) // only first 6 used
        val f = Requests.sendTextMessage(prefix, "hi", timestamp = 0x01020304, txtType = 0, attempt = 0)
        val r = FrameReader(f)
        assertEquals(Cmd.SEND_TXT_MSG, r.u8())
        assertEquals(0, r.u8())            // txtType
        assertEquals(0, r.u8())            // attempt
        assertEquals(0x01020304L, r.u32()) // timestamp (LE)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), r.bytes(6))
        assertEquals("hi", r.restAsString())
    }

    @Test fun decodeContact() {
        // Build a RESP_CODE_CONTACT frame the way the firmware would, then decode it.
        val pub = ByteArray(32) { it.toByte() }
        val outPath = ByteArray(64)
        val frame = FrameWriter()
            .u8(Resp.CONTACT)
            .bytes(pub)
            .u8(ContactType.REPEATER)
            .u8(0)
            .u8(2)
            .bytes(outPath)
            .bytes("Repeater-1".toByteArray().copyOf(32)) // 32-byte cstr field
            .u32(1_700_000_000L)
            .i32(45_000_000)   // 45.0 deg
            .i32(-73_000_000)  // -73.0 deg
            .u32(1_700_000_500L)
            .build()

        val decoded = FrameDecoder.decode(frame)
        assertTrue(decoded is Incoming.ContactEntry)
        val c = (decoded as Incoming.ContactEntry).contact
        assertEquals("Repeater-1", c.name)
        assertTrue(c.isRepeater)
        assertEquals(2, c.outPathLen)
        assertArrayEquals(pub, c.publicKey)
        assertEquals(45.0, c.latDegrees!!, 1e-9)
        assertEquals(-73.0, c.lonDegrees!!, 1e-9)
    }

    @Test fun decodeSentAndConfirm() {
        val sent = FrameDecoder.decode(FrameWriter().u8(Resp.SENT).u8(0).u32(0xDEADBEEFL).build())
        assertTrue(sent is Incoming.Sent)
        assertEquals(0xDEADBEEFL, (sent as Incoming.Sent).result.expectedAck)
        assertEquals(false, sent.result.sentFlood)

        val conf = FrameDecoder.decode(FrameWriter().u8(Push.SEND_CONFIRMED).u32(0xDEADBEEFL).u32(1234).build())
        assertTrue(conf is Incoming.SendConfirmed)
        assertEquals(0xDEADBEEFL, (conf as Incoming.SendConfirmed).ackId)
        assertEquals(1234L, conf.roundTripMs)
    }

    @Test fun decodeAdvertPushCarries32BytePubkey() {
        val pub = ByteArray(32) { (it * 7).toByte() }
        val decoded = FrameDecoder.decode(FrameWriter().u8(Push.ADVERT).bytes(pub).build())
        assertTrue(decoded is Incoming.AdvertHeard)
        assertArrayEquals(pub, (decoded as Incoming.AdvertHeard).publicKey)
    }

    @Test fun unknownCodeBecomesRawNotCrash() {
        val decoded = FrameDecoder.decode(byteArrayOf(0x7F, 1, 2, 3))
        assertTrue(decoded is Incoming.Raw)
        assertEquals(0x7F, (decoded as Incoming.Raw).code)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.payload)
    }

    @Test fun truncatedFrameIsRawNotException() {
        // SELF_INFO code but no payload — must not throw.
        val decoded = FrameDecoder.decode(byteArrayOf(Resp.SELF_INFO.toByte()))
        assertTrue(decoded is Incoming.Raw)
    }

    @Test fun pushBitDetection() {
        assertTrue(isPush(Push.ADVERT))
        assertTrue(isPush(Push.TRACE_DATA))
        assertEquals(false, isPush(Resp.OK))
        assertEquals(false, isPush(Resp.CONTACT))
    }

    @Test fun hexRoundTrip() {
        val b = byteArrayOf(0x00, 0x0a, 0xff.toByte(), 0x10)
        assertEquals("000aff10", b.toHex())
        assertArrayEquals(b, "000aff10".hexToBytes())
    }
}
