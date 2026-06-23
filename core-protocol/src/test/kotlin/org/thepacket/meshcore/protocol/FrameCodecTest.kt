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

    @Test fun decodeSelfInfo() {
        // Build a RESP_CODE_SELF_INFO frame exactly as the firmware writer does.
        val pub = ByteArray(32) { (it + 1).toByte() }
        val telem = (2 shl 4) or (1 shl 2) or 3 // env=2, loc=1, base=3
        val frame = FrameWriter()
            .u8(Resp.SELF_INFO)
            .u8(ContactType.CHAT)   // adv type
            .u8(22)                 // tx power
            .u8(22)                 // max tx power
            .bytes(pub)
            .i32(45_000_000)        // adv lat
            .i32(-73_000_000)       // adv lon
            .u8(0).u8(0).u8(telem).u8(1) // multiAcks, advertLocPolicy, telemetryModes, manualAdd
            .u32(910_525)           // freq kHz  -> 910.525 MHz
            .u32(250_000)           // bw Hz      -> 250 kHz
            .u8(10)                 // sf
            .u8(5)                  // cr
            .str("LostPacket4")
            .build()

        val decoded = FrameDecoder.decode(frame)
        assertTrue(decoded is Incoming.Self)
        val s = (decoded as Incoming.Self).info
        assertEquals("LostPacket4", s.name)
        assertEquals(22, s.txPower)
        assertEquals(910.525, s.freqMhz, 1e-9)
        assertEquals(250.0, s.bwKhz, 1e-9)
        assertEquals(10, s.radioSf)
        assertEquals(5, s.radioCr)
        assertEquals(1, s.manualAddContacts)
        assertEquals(3, s.telemetryModeBase)
        assertEquals(1, s.telemetryModeLoc)
        assertEquals(2, s.telemetryModeEnv)
    }

    @Test fun decodeSentAndConfirm() {
        // RESP_CODE_SENT is 10 bytes: flood(1) expectedAck(u32) estTimeout(u32)
        val sent = FrameDecoder.decode(FrameWriter().u8(Resp.SENT).u8(1).u32(0xDEADBEEFL).u32(8000).build())
        assertTrue(sent is Incoming.Sent)
        val res = (sent as Incoming.Sent).result
        assertEquals(0xDEADBEEFL, res.expectedAck)
        assertEquals(true, res.sentFlood)
        assertEquals(8000L, res.estTimeoutMs)

        val conf = FrameDecoder.decode(FrameWriter().u8(Push.SEND_CONFIRMED).u32(0xDEADBEEFL).u32(1234).build())
        assertTrue(conf is Incoming.SendConfirmed)
        assertEquals(0xDEADBEEFL, (conf as Incoming.SendConfirmed).ackId)
        assertEquals(1234L, conf.roundTripMs)
    }

    @Test fun decodeContactMessageV3() {
        // V3 contact message: code, snr(i8), res1, res2, prefix(6), pathLen, txtType, ts(u32), text
        val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 1, 2, 3, 4)
        val frame = FrameWriter()
            .u8(Resp.CONTACT_MSG_RECV_V3)
            .u8(40)          // snr*4 = 10 dB
            .u8(0).u8(0)     // reserved
            .bytes(prefix)
            .u8(0xFF)        // pathLen 0xFF = direct
            .u8(TxtType.PLAIN)
            .u32(1_700_000_000L)
            .str("hello there")
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.Message)
        val m = (d as Incoming.Message).message
        assertEquals("hello there", m.text)
        assertEquals(false, m.isChannel)
        assertArrayEquals(prefix, m.pubKeyPrefix)
        assertEquals(true, m.deliveredDirect)
        assertEquals(10.0, m.snrDb, 1e-9)
    }

    @Test fun decodeChannelMessageV3() {
        val frame = FrameWriter()
            .u8(Resp.CHANNEL_MSG_RECV_V3)
            .u8(0).u8(0).u8(0) // snr + reserved
            .u8(2)             // channel idx
            .u8(7)             // pathLen (flood, 7 hops)
            .u8(TxtType.PLAIN)
            .u32(1_700_000_000L)
            .str("hi channel")
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.Message)
        val m = (d as Incoming.Message).message
        assertEquals("hi channel", m.text)
        assertTrue(m.isChannel)
        assertEquals(2, m.channelIdx)
        assertEquals(false, m.deliveredDirect)
    }

    @Test fun decodeContactsStartAndEnd() {
        val start = FrameDecoder.decode(FrameWriter().u8(Resp.CONTACTS_START).u32(42).build())
        assertTrue(start is Incoming.ContactsStart)
        assertEquals(42L, (start as Incoming.ContactsStart).total)

        val end = FrameDecoder.decode(FrameWriter().u8(Resp.END_OF_CONTACTS).u32(1_700_000_500L).build())
        assertTrue(end is Incoming.ContactsEnd)
        assertEquals(1_700_000_500L, (end as Incoming.ContactsEnd).mostRecentLastMod)
    }

    @Test fun decodeChannelInfo() {
        val secret = ByteArray(16) { it.toByte() }
        val frame = FrameWriter()
            .u8(Resp.CHANNEL_INFO)
            .u8(1)
            .bytes("test-lp".toByteArray().copyOf(32)) // 32-byte NUL-padded name
            .bytes(secret)
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.ChannelInfo)
        val ci = d as Incoming.ChannelInfo
        assertEquals(1, ci.index)
        assertEquals("test-lp", ci.name)
        assertArrayEquals(secret, ci.secret)
    }

    @Test fun getChannelRequestShape() {
        val f = Requests.getChannel(2)
        assertArrayEquals(byteArrayOf(Cmd.GET_CHANNEL.toByte(), 2), f)
    }

    @Test fun channelSendIncludesTxtTypeByte() {
        val f = Requests.sendChannelTextMessage(channelIdx = 1, text = "yo", timestamp = 0x01020304)
        val r = FrameReader(f)
        assertEquals(Cmd.SEND_CHANNEL_TXT_MSG, r.u8())
        assertEquals(TxtType.PLAIN, r.u8())  // the byte that was missing
        assertEquals(1, r.u8())              // channel idx
        assertEquals(0x01020304L, r.u32())
        assertEquals("yo", r.restAsString())
    }

    @Test fun decodeNewAdvertAsContact() {
        val pub = ByteArray(32) { (it + 3).toByte() }
        val frame = FrameWriter()
            .u8(Push.NEW_ADVERT)
            .bytes(pub)
            .u8(ContactType.CHAT)
            .u8(0).u8(0)
            .bytes(ByteArray(64))
            .bytes("NewNode".toByteArray().copyOf(32))
            .u32(1_700_000_000L).i32(0).i32(0).u32(1_700_000_000L)
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.NewAdvert)
        assertEquals("NewNode", (d as Incoming.NewAdvert).contact.name)
    }

    @Test fun decodeAdvertPushCarries32BytePubkey() {
        val pub = ByteArray(32) { (it * 7).toByte() }
        val decoded = FrameDecoder.decode(FrameWriter().u8(Push.ADVERT).bytes(pub).build())
        assertTrue(decoded is Incoming.AdvertHeard)
        assertArrayEquals(pub, (decoded as Incoming.AdvertHeard).publicKey)
    }

    @Test fun decodeRxLogDerivesTypeFromHeader() {
        // header byte: route=DIRECT(2), payloadType=ADVERT(4) -> (4<<2)|2 = 0x12
        val header = (PayloadType.ADVERT shl 2) or RouteType.DIRECT
        val raw = byteArrayOf(header.toByte(), 0xDE.toByte(), 0xAD.toByte())
        val frame = FrameWriter().u8(Push.LOG_RX_DATA).u8(40).u8(-110).bytes(raw).build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.RxPacket)
        val log = (d as Incoming.RxPacket).log
        assertEquals(10.0, log.snrDb, 1e-9)
        assertEquals(-110, log.rssi)
        assertEquals(PayloadType.ADVERT, log.payloadType)
        assertEquals("ADVERT", log.typeName)
        assertEquals("direct", log.routeName)
        assertEquals(3, log.length)
    }

    @Test fun decodeRadioStatsIncludingNegativeNoise() {
        val frame = FrameWriter()
            .u8(Resp.STATS)
            .u8(StatsType.RADIO)
            .u16(0xFF88)      // -120 as int16 little-endian
            .u8(-115)      // last rssi
            .u8(48)           // last snr *4 = 12 dB
            .u32(3600)        // tx airtime
            .u32(7200)        // rx airtime
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.RadioStatsResp)
        val s = (d as Incoming.RadioStatsResp).stats
        assertEquals(-120, s.noiseFloor)
        assertEquals(-115, s.lastRssi)
        assertEquals(12.0, s.lastSnrDb, 1e-9)
        assertEquals(3600L, s.txAirtimeSecs)
    }

    @Test fun decodePacketStats() {
        val frame = FrameWriter()
            .u8(Resp.STATS).u8(StatsType.PACKETS)
            .u32(100).u32(50).u32(10).u32(40).u32(60).u32(40).u32(2)
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.PacketStatsResp)
        val s = (d as Incoming.PacketStatsResp).stats
        assertEquals(100L, s.recv)
        assertEquals(2L, s.recvErrors)
    }

    @Test fun getStatsRequestShape() {
        assertArrayEquals(byteArrayOf(Cmd.GET_STATS.toByte(), StatsType.RADIO.toByte()),
            Requests.getStats(StatsType.RADIO))
    }

    @Test fun inspectTxtMsgSrcDestPath() {
        // header: route=FLOOD(1), type=TXT_MSG(2) -> (2<<2)|1 = 0x09
        val header = (PayloadType.TXT_MSG shl 2) or RouteType.FLOOD
        val pathLenByte = (0 shl 6) or 2 // hashSize 1, 2 hops
        val raw = FrameWriter()
            .u8(header)
            .u8(pathLenByte).u8(0xA1).u8(0xB2)          // path: 2 hop hashes
            .u8(0xDD).u8(0xCC).u8(0x12).u8(0x34)         // dest, src, MAC(2)
            .bytes(ByteArray(8))                          // ciphertext
            .build()
        val p = PacketInspector.parse(raw)
        assertEquals(PayloadType.TXT_MSG, p.payloadType)
        assertEquals("flood", p.routeName)
        assertEquals(0xDD, p.destHash)
        assertEquals(0xCC, p.srcHash)
        assertEquals(listOf(0xA1, 0xB2), p.pathHashes)
    }

    @Test fun inspectAdvertExtractsKeyNameLatLon() {
        val pub = ByteArray(32) { (it + 5).toByte() }
        val sig = ByteArray(64)
        val flags = 0x10 or 0x80 or ContactType.REPEATER // latlon + name + type
        val appData = FrameWriter().u8(flags).i32(45_000_000).i32(-75_000_000).str("WAKE_R").build()
        val header = (PayloadType.ADVERT shl 2) or RouteType.FLOOD
        val raw = FrameWriter()
            .u8(header).u8(0) // path_len = 0
            .bytes(pub).u32(1_700_000_000L).bytes(sig).bytes(appData)
            .build()
        val p = PacketInspector.parse(raw)
        assertEquals(PayloadType.ADVERT, p.payloadType)
        assertArrayEquals(pub, p.advertPubKey)
        assertEquals("WAKE_R", p.advertName)
        assertEquals(45_000_000, p.advertLat)
        assertEquals(-75_000_000, p.advertLon)
        assertEquals(1_700_000_000L, p.advertTimestamp)
    }

    @Test fun inspectTruncatedPacketDoesNotCrash() {
        val p = PacketInspector.parse(byteArrayOf(0x09, 0x00, 0x01)) // header + path_len + partial
        assertEquals(PayloadType.TXT_MSG, p.payloadType)
        // not enough payload for dest/src — left null, no throw
        assertEquals(null, p.destHash)
    }

    @Test fun setRadioParamsShape() {
        // 915 MHz -> 915000 kHz, 250 kHz -> 250000 Hz, sf10 cr5 repeat on
        val f = Requests.setRadioParams(915_000, 250_000, 10, 5, repeat = true)
        val r = FrameReader(f)
        assertEquals(Cmd.SET_RADIO_PARAMS, r.u8())
        assertEquals(915_000L, r.u32())
        assertEquals(250_000L, r.u32())
        assertEquals(10, r.u8())
        assertEquals(5, r.u8())
        assertEquals(1, r.u8())
    }

    @Test fun setOtherParamsPacksTelemetry() {
        val f = Requests.setOtherParams(
            manualAddContacts = true, telemetryBase = 2, telemetryLoc = 1, telemetryEnv = 2,
            advertLocPolicy = AdvertLoc.SHARE, multiAcks = 3,
        )
        val r = FrameReader(f)
        assertEquals(Cmd.SET_OTHER_PARAMS, r.u8())
        assertEquals(1, r.u8())                       // manual add
        val telem = r.u8()
        assertEquals(2, telem and 0x03)               // base
        assertEquals(1, (telem shr 2) and 0x03)       // loc
        assertEquals(2, (telem shr 4) and 0x03)       // env
        assertEquals(AdvertLoc.SHARE, r.u8())
        assertEquals(3, r.u8())
    }

    @Test fun decodeTuningAndAutoAdd() {
        val t = FrameDecoder.decode(FrameWriter().u8(Resp.TUNING_PARAMS).u32(1500).u32(2000).build())
        assertTrue(t is Incoming.Tuning)
        assertEquals(1.5, (t as Incoming.Tuning).params.rxDelayBase, 1e-9)
        assertEquals(2.0, t.params.airtimeFactor, 1e-9)

        val a = FrameDecoder.decode(FrameWriter().u8(Resp.AUTOADD_CONFIG).u8(AutoAdd.CHAT or AutoAdd.REPEATER).u8(5).build())
        assertTrue(a is Incoming.AutoAdd)
        assertEquals(AutoAdd.CHAT or AutoAdd.REPEATER, (a as Incoming.AutoAdd).config.flags)
        assertEquals(5, a.config.maxHops)
    }

    @Test fun setPathHashModeShape() {
        assertArrayEquals(byteArrayOf(Cmd.SET_PATH_HASH_MODE.toByte(), 0, 2), Requests.setPathHashMode(2))
    }

    @Test fun setAdvertLatLonRoundTrips() {
        val f = Requests.setAdvertLatLon(45_000_000, -75_000_000)
        val r = FrameReader(f)
        assertEquals(Cmd.SET_ADVERT_LATLON, r.u8())
        assertEquals(45_000_000, r.i32())
        assertEquals(-75_000_000, r.i32())
    }

    @Test fun decodeDeviceInfo() {
        val frame = FrameWriter()
            .u8(Resp.DEVICE_INFO)
            .u8(10)                                   // ver code
            .u8(175)                                  // maxContacts/2 -> 350
            .u8(40)                                   // max channels
            .u32(0)                                   // ble pin
            .bytes("Jun 22 2026".toByteArray().copyOf(12))
            .bytes("LilyGo".toByteArray().copyOf(40))
            .bytes("v1.2.3".toByteArray().copyOf(20))
            .u8(1)                                    // client repeat
            .u8(2)                                    // path hash mode
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.Device)
        val info = (d as Incoming.Device).info
        assertEquals(350, info.maxContacts)
        assertEquals(40, info.maxChannels)
        assertEquals("LilyGo", info.manufacturer)
        assertEquals("v1.2.3", info.firmwareVersion)
        assertEquals(true, info.clientRepeat)
        assertEquals(2, info.pathHashMode)
    }

    @Test fun decodeSelfTelemetryLpp() {
        // ch1 voltage 4.09V (409/100,t116); ch2 temp 24.3C (243/10,t103),
        // humidity 52% (104/2,t104), pressure 1009.3hPa (10093/10,t115).
        val frame = FrameWriter()
            .u8(Push.TELEMETRY_RESPONSE).u8(0).bytes(ByteArray(6))
            .u8(1).u8(Lpp.VOLTAGE).u8(0x01).u8(0x99)
            .u8(2).u8(Lpp.TEMPERATURE).u8(0x00).u8(0xF3)
            .u8(2).u8(Lpp.RELATIVE_HUMIDITY).u8(104)
            .u8(2).u8(Lpp.BAROMETRIC_PRESSURE).u8(0x27).u8(0x6D)
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.Telemetry)
        val rs = (d as Incoming.Telemetry).readings
        assertEquals(4, rs.size)
        assertEquals(1, rs[0].channel); assertEquals(4.09, rs[0].values[0], 1e-9)
        assertEquals(2, rs[1].channel); assertEquals(24.3, rs[1].values[0], 1e-9)
        assertEquals(52.0, rs[2].values[0], 1e-9)
        assertEquals(1009.3, rs[3].values[0], 1e-6)
    }

    @Test fun sendTracePathShape() {
        val path = byteArrayOf(0xA1.toByte(), 0xB2.toByte())
        val f = Requests.sendTracePath(tag = 0x01020304, path = path)
        val r = FrameReader(f)
        assertEquals(Cmd.SEND_TRACE_PATH, r.u8())
        assertEquals(0x01020304L, r.u32()) // tag
        assertEquals(0L, r.u32())          // auth
        assertEquals(0, r.u8())            // flags
        assertArrayEquals(path, r.bytes(2))
    }

    @Test fun decodeTraceData() {
        // pathLen=2, flags=0 (one snr/hop): hops AA(snr 8dB=32), BB(snr 6dB=24); final 10dB=40
        val frame = FrameWriter()
            .u8(Push.TRACE_DATA).u8(0)        // code, reserved
            .u8(2).u8(0)                       // pathLen, flags
            .u32(0xDEADBEEFL).u32(0)           // tag, auth
            .u8(0xAA).u8(0xBB)                 // path hashes
            .u8(32).u8(24)                     // path snrs (*4)
            .u8(40)                            // final snr (*4)
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.Trace)
        val t = (d as Incoming.Trace).result
        assertEquals(0xDEADBEEFL, t.tag)
        assertEquals(2, t.hops.size)
        assertEquals(0xAA, t.hops[0].hashByte); assertEquals(8.0, t.hops[0].snrDb, 1e-9)
        assertEquals(0xBB, t.hops[1].hashByte); assertEquals(6.0, t.hops[1].snrDb, 1e-9)
        assertEquals(10.0, t.finalSnrDb, 1e-9)
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
