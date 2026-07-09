package org.thepacket.meshcore.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun decodeRepeaterStatusResponse() {
        // [code] reserved(1) pubKeyPrefix(6) then the raw 56-byte RepeaterStats struct.
        val frame = FrameWriter()
            .u8(Push.STATUS_RESPONSE)
            .u8(0)                                   // reserved
            .bytes(byteArrayOf(1, 2, 3, 4, 5, 6))    // pubKeyPrefix
            .u16(3700)                               // batt_milli_volts
            .u16(5)                                  // curr_tx_queue_len
            .u16(0xFF88)                             // noise_floor i16 = -120
            .u16(0xFF8D)                             // last_rssi i16 = -115
            .u32(1000).u32(900)                      // n_packets_recv / sent
            .u32(3600).u32(86400)                    // total_air_time / up_time
            .u32(11).u32(22).u32(33).u32(44)         // sent flood/direct, recv flood/direct
            .u16(7)                                  // err_events
            .u16(48)                                 // last_snr i16, *4 = 12 dB
            .u16(3).u16(4)                           // n_direct_dups / n_flood_dups
            .u32(7200)                               // total_rx_air_time_secs
            .u32(70000)                              // n_recv_errors (> u16 to prove width)
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.Status)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), (d as Incoming.Status).pubKeyPrefix)
        val s = d.stats
        assertEquals(3700, s.batteryMilliVolts)
        assertEquals(5, s.txQueueLen)
        assertEquals(-120, s.noiseFloor)
        assertEquals(-115, s.lastRssi)
        assertEquals(1000L, s.nPacketsRecv)
        assertEquals(86400L, s.uptimeSecs)
        assertEquals(44L, s.recvDirect)
        assertEquals(7, s.errEvents)
        assertEquals(12, s.lastSnrQ / 4)
        assertEquals(3, s.directDups)
        assertEquals(4, s.floodDups)
        assertEquals(7200L, s.airtimeRxSecs)
        assertEquals(70000L, s.recvErrors)
    }

    @Test fun requestTelemetryShape() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val frame = Requests.requestTelemetry(key)
        // [cmd, reserved(3), pubKey(32)] = 36 bytes; firmware reads the key at offset 4.
        assertEquals(36, frame.size)
        assertEquals(Cmd.SEND_TELEMETRY_REQ.toByte(), frame[0])
        assertArrayEquals(byteArrayOf(0, 0, 0), frame.copyOfRange(1, 4))
        assertArrayEquals(key, frame.copyOfRange(4, 36))
    }

    @Test fun nodeDiscoverReqShape() {
        // Blind request: no pubkey. [cmd, NODE_DISCOVER_REQ|prefixOnly, filter, tag(u32)].
        val filter = (1 shl ContactType.REPEATER)
        val frame = Requests.nodeDiscoverReq(filter, tag = 0x11223344, prefixOnly = true)
        assertEquals(Cmd.SEND_CONTROL_DATA.toByte(), frame[0])
        assertEquals((CtlType.NODE_DISCOVER_REQ or 1).toByte(), frame[1])
        assertEquals(filter.toByte(), frame[2])
        assertArrayEquals(byteArrayOf(0x44, 0x33, 0x22, 0x11), frame.copyOfRange(3, 7)) // tag LE
        assertEquals(7, frame.size)
    }

    @Test fun decodeNodeDiscoverResponse() {
        val pubkey = ByteArray(8) { (0xA0 + it).toByte() }
        val frame = FrameWriter()
            .u8(Push.CONTROL_DATA)
            .u8(40)                                   // our SNR of the reply (*4 = 10 dB)
            .u8(-95)                                  // RSSI
            .u8(0)                                    // path_len (zero-hop)
            .u8(CtlType.NODE_DISCOVER_RESP or ContactType.REPEATER) // type + node type
            .u8(28)                                   // their SNR of our request (*4 = 7 dB)
            .u32(0x11223344)                          // echoed tag
            .bytes(pubkey)
            .build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.NodeDiscovered)
        val n = (d as Incoming.NodeDiscovered).node
        assertEquals(ContactType.REPEATER, n.type)
        assertEquals(0x11223344L, n.tag)
        assertEquals(10.0, n.snrDb, 1e-9)
        assertEquals(7, n.inSnrQ / 4)
        assertArrayEquals(pubkey, n.pubKey)
    }

    @Test fun repeaterLoginUsesFullKey() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val frame = Requests.sendLogin(key, "secret")
        assertEquals(Cmd.SEND_LOGIN.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 33)) // full 32-byte key, not a prefix
        assertEquals("secret", String(frame.copyOfRange(33, frame.size)))
    }

    @Test fun repeaterStatusAndLogoutUseFullKey() {
        val key = ByteArray(32) { it.toByte() }
        assertArrayEquals(key, Requests.sendStatusRequest(key).copyOfRange(1, 33))
        assertEquals(33, Requests.sendStatusRequest(key).size)
        assertArrayEquals(key, Requests.logout(key).copyOfRange(1, 33))
        assertEquals(33, Requests.logout(key).size)
    }

    @Test fun decodeLoginSuccessAndFail() {
        val prefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 1, 2, 3, 4)
        val ok = FrameDecoder.decode(FrameWriter().u8(Push.LOGIN_SUCCESS).u8(1).bytes(prefix).build())
        assertTrue(ok is Incoming.LoginSuccess)
        assertTrue((ok as Incoming.LoginSuccess).isAdmin)
        assertArrayEquals(prefix, ok.pubKeyPrefix)

        val fail = FrameDecoder.decode(FrameWriter().u8(Push.LOGIN_FAIL).u8(0).bytes(prefix).build())
        assertTrue(fail is Incoming.LoginFail)
        assertArrayEquals(prefix, (fail as Incoming.LoginFail).pubKeyPrefix)
    }

    @Test fun repeaterCommandIsCliTextMessage() {
        val prefix = ByteArray(6) { (it + 1).toByte() }
        val frame = Requests.sendRepeaterCommand(prefix, "reboot", 1000)
        assertEquals(Cmd.SEND_TXT_MSG.toByte(), frame[0])
        assertEquals(TxtType.CLI_DATA.toByte(), frame[1]) // CLI command type
    }

    @Test fun requestNeighboursShape() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val frame = Requests.requestNeighbours(key, nonce = 0xAABBCCDD, count = 255, prefixLength = 6, orderBy = 2)
        assertEquals(Cmd.SEND_BINARY_REQ.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 33))
        assertEquals(BinReqType.GET_NEIGHBOURS.toByte(), frame[33])
        assertEquals(0.toByte(), frame[34])           // version
        assertEquals(255.toByte(), frame[35])         // count
        assertArrayEquals(byteArrayOf(0, 0), frame.copyOfRange(36, 38)) // offset
        assertEquals(2.toByte(), frame[38])           // orderBy
        assertEquals(6.toByte(), frame[39])           // prefixLength
    }

    @Test fun decodeBinaryResponseAndNeighbours() {
        // BINARY_RESPONSE: [code, reserved, tag(4), data...]; data = neighbours payload.
        val data = FrameWriter()
            .u16(3)        // total neighbours
            .u16(2)        // results in this batch
            .bytes(byteArrayOf(0xAA.toByte(), 1, 2, 3, 4, 5)).i32(30).u8(40)   // SNR 40/4 = 10 dB
            .bytes(byteArrayOf(0xBB.toByte(), 6, 7, 8, 9, 10)).i32(120).u8(-20)
            .build()
        val frame = FrameWriter().u8(Push.BINARY_RESPONSE).u8(0).u32(0x11223344).bytes(data).build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.BinaryResponse)
        assertEquals(0x11223344L, (d as Incoming.BinaryResponse).tag)

        val (total, list) = Neighbours.decode(d.data, prefixLen = 6)
        assertEquals(3, total)
        assertEquals(2, list.size)
        assertEquals(30, list[0].secsAgo)
        assertEquals(10.0, list[0].snrDb, 1e-9)
        assertEquals(120, list[1].secsAgo)
    }

    @Test fun requestMmaShape() {
        val key = ByteArray(32) { it.toByte() }
        val frame = Requests.requestMma(key, startSecsAgo = 3600, endSecsAgo = 0)
        assertEquals(Cmd.SEND_BINARY_REQ.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 33))
        assertEquals(BinReqType.GET_MMA.toByte(), frame[33])
        assertArrayEquals(byteArrayOf(0x10, 0x0E, 0, 0), frame.copyOfRange(34, 38)) // 3600 LE
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), frame.copyOfRange(38, 42))       // end = 0
    }

    @Test fun decodeMmaTriples() {
        // data = [now(4), (channel, type, min, max, avg per LPP type)]; temperature is i16 x10.
        val data = FrameWriter()
            .u32(1000)                 // now
            .u8(1).u8(Lpp.TEMPERATURE) // channel 1, temperature
            // min/max/avg as big-endian i16 ×10 (LPP is big-endian): 20.0 / 30.0 / 25.0
            .bytes(byteArrayOf(0x00, 0xC8.toByte(), 0x01, 0x2C, 0x00, 0xFA.toByte()))
            .build()
        val readings = Mma.decode(data)
        assertEquals(1, readings.size)
        val r = readings[0]
        assertEquals(Lpp.TEMPERATURE, r.type)
        assertEquals(20.0, r.min, 1e-9)
        assertEquals(30.0, r.max, 1e-9)
        assertEquals(25.0, r.avg, 1e-9)
    }

    @Test fun requestAclShape() {
        val key = ByteArray(32) { it.toByte() }
        val frame = Requests.requestAcl(key)
        assertEquals(Cmd.SEND_BINARY_REQ.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 33))
        assertEquals(BinReqType.GET_ACL.toByte(), frame[33])
        assertEquals(36, frame.size) // cmd + key(32) + type + 2 reserved
    }

    @Test fun decodeAclEntries() {
        val a = byteArrayOf(0xAA.toByte(), 1, 2, 3, 4, 5)
        val b = byteArrayOf(0xBB.toByte(), 6, 7, 8, 9, 10)
        val data = FrameWriter()
            .bytes(a).u8(AclRole.ADMIN)
            .bytes(ByteArray(6)).u8(0) // empty slot — skipped
            .bytes(b).u8(AclRole.READ_ONLY)
            .build()
        val acl = Acl.decode(data)
        assertEquals(2, acl.size)
        assertArrayEquals(a, acl[0].pubKeyPrefix)
        assertEquals("Admin", acl[0].roleName)
        assertEquals("Read-only", acl[1].roleName)
    }

    @Test fun keepAliveShape() {
        val key = ByteArray(32) { it.toByte() }
        val frame = Requests.keepAlive(key)
        assertEquals(Cmd.SEND_BINARY_REQ.toByte(), frame[0])
        assertArrayEquals(key, frame.copyOfRange(1, 33))
        assertEquals(BinReqType.KEEP_ALIVE.toByte(), frame[33])
        assertEquals(34, frame.size)
    }

    @Test fun ownerInfoRequestAndDecode() {
        val key = ByteArray(32) { it.toByte() }
        val frame = Requests.requestOwnerInfo(key)
        assertEquals(Cmd.SEND_BINARY_REQ.toByte(), frame[0])
        assertEquals(BinReqType.GET_OWNER_INFO.toByte(), frame[33])

        val o = OwnerInfo.decode("v1.16.0\nRepeater North\nalice@example".toByteArray())
        assertEquals("v1.16.0", o.firmwareVersion)
        assertEquals("Repeater North", o.nodeName)
        assertEquals("alice@example", o.owner)
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

    @Test fun decodeCustomVars() {
        // Firmware joins pairs with ',' and separates name/value with the first ':'.
        val frame = FrameWriter().u8(Resp.CUSTOM_VARS).str("gps:1,gps_interval:900,name:Node A").build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.CustomVars)
        val vars = (d as Incoming.CustomVars).vars
        assertEquals(3, vars.size)
        assertEquals(CustomVar("gps", "1"), vars[0])
        assertEquals(CustomVar("gps_interval", "900"), vars[1])
        assertEquals(CustomVar("name", "Node A"), vars[2])

        // Empty payload -> no vars (device reports none).
        val empty = FrameDecoder.decode(FrameWriter().u8(Resp.CUSTOM_VARS).build())
        assertTrue(empty is Incoming.CustomVars)
        assertTrue((empty as Incoming.CustomVars).vars.isEmpty())
    }

    @Test fun setCustomVarShape() {
        // Frame: [cmd] + raw "key:value" UTF-8, no terminator (firmware splits on first ':').
        assertArrayEquals(
            byteArrayOf(Cmd.SET_CUSTOM_VAR.toByte()) + "gps:1".toByteArray(),
            Requests.setCustomVar("gps", "1"),
        )
    }

    @Test fun sendRawDataShape() {
        // Frame: [cmd, path_len(1), path, payload]. Direct with a 2-hop path.
        val path = byteArrayOf(0x11, 0x22)
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(
            byteArrayOf(Cmd.SEND_RAW_DATA.toByte(), 2, 0x11, 0x22, 1, 2, 3, 4, 5),
            Requests.sendRawData(path, payload),
        )
        // Zero-hop: empty path -> path_len 0.
        assertArrayEquals(
            byteArrayOf(Cmd.SEND_RAW_DATA.toByte(), 0, 9, 9, 9, 9),
            Requests.sendRawData(ByteArray(0), byteArrayOf(9, 9, 9, 9)),
        )
    }

    @Test fun decodeRawData() {
        // PUSH_CODE_RAW_DATA: [snr(i8 x4)][rssi(i8)][reserved(1)][payload...]
        val frame = FrameWriter().u8(Push.RAW_DATA).u8(20).u8(0xC4).u8(0xFF).bytes("beef".toByteArray()).build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.RawData)
        val f = (d as Incoming.RawData).frame
        assertEquals(20, f.snrQ)
        assertEquals(5.0, f.snrDb, 1e-9)
        assertEquals(-60, f.rssi) // 0xC4 as signed int8
        assertEquals("beef", String(f.payload))
    }

    @Test fun getAdvertPathShape() {
        // Frame: [cmd, reserved(0), pubKey(32)].
        val key = ByteArray(32) { (it + 1).toByte() }
        val f = Requests.getAdvertPath(key)
        assertEquals(34, f.size)
        val r = FrameReader(f)
        assertEquals(Cmd.GET_ADVERT_PATH, r.u8())
        assertEquals(0, r.u8())
        assertArrayEquals(key, r.bytes(32))
    }

    @Test fun decodeAdvertPath() {
        // [recvTimestamp(u32)][path_len(1)][path]. path_len=3 -> 3 single-byte hops.
        val frame = FrameWriter().u8(Resp.ADVERT_PATH)
            .u32(1_700_000_000L).u8(3).bytes(byteArrayOf(0x0A, 0x0B, 0x0C)).build()
        val d = FrameDecoder.decode(frame)
        assertTrue(d is Incoming.AdvertPath)
        val info = (d as Incoming.AdvertPath).info
        assertEquals(1_700_000_000L, info.recvTimestamp)
        assertEquals(3, info.hopCount)
        assertEquals(1, info.hashSize)
        assertEquals(listOf(0x0A, 0x0B, 0x0C), info.singleByteHops)

        // 2-byte hop hashes: path_len = (1<<6) | 2 = 66 -> 2 hops × 2 bytes = 4 bytes.
        val f2 = FrameWriter().u8(Resp.ADVERT_PATH).u32(0).u8(66)
            .bytes(byteArrayOf(1, 2, 3, 4)).build()
        val d2 = (FrameDecoder.decode(f2) as Incoming.AdvertPath).info
        assertEquals(2, d2.hopCount)
        assertEquals(2, d2.hashSize)
        assertNull(d2.singleByteHops)
    }

    @Test fun privateKeyExportImportShapes() {
        assertArrayEquals(byteArrayOf(Cmd.EXPORT_PRIVATE_KEY.toByte()), Requests.exportPrivateKey())

        val id = ByteArray(64) { it.toByte() }
        val f = Requests.importPrivateKey(id)
        assertEquals(65, f.size)
        assertEquals(Cmd.IMPORT_PRIVATE_KEY, f[0].toInt() and 0xFF)
        assertArrayEquals(id, f.copyOfRange(1, 65))
    }

    @Test fun decodePrivateKey() {
        val id = ByteArray(64) { (it + 1).toByte() }
        val d = FrameDecoder.decode(FrameWriter().u8(Resp.PRIVATE_KEY).bytes(id).build())
        assertTrue(d is Incoming.PrivateKey)
        assertArrayEquals(id, (d as Incoming.PrivateKey).identity)

        // A firmware without key export replies DISABLED.
        assertTrue(FrameDecoder.decode(byteArrayOf(Resp.DISABLED.toByte())) is Incoming.Disabled)
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

    @Test fun removeShareResetContactCarryFullKey() {
        val key = ByteArray(32) { it.toByte() }
        for ((frame, cmd) in listOf(
            Requests.removeContact(key) to Cmd.REMOVE_CONTACT,
            Requests.shareContact(key) to Cmd.SHARE_CONTACT,
            Requests.resetPath(key) to Cmd.RESET_PATH,
            Requests.getContactByKey(key) to Cmd.GET_CONTACT_BY_KEY,
        )) {
            assertEquals(1 + 32, frame.size)
            assertEquals(cmd, frame[0].toInt())
            assertArrayEquals(key, frame.copyOfRange(1, 33))
        }
    }

    @Test fun exportContactSelfVsByKey() {
        assertArrayEquals(byteArrayOf(Cmd.EXPORT_CONTACT.toByte()), Requests.exportContact(null))
        val key = ByteArray(32) { (it + 1).toByte() }
        val byKey = Requests.exportContact(key)
        assertEquals(1 + 32, byKey.size)
        assertArrayEquals(key, byKey.copyOfRange(1, 33))
    }

    @Test fun setChannelLayout() {
        val secret = ByteArray(16) { (0xA0 + it).toByte() }
        val f = Requests.setChannel(3, "longwave", secret)
        assertEquals(1 + 1 + 32 + 16, f.size)
        assertEquals(Cmd.SET_CHANNEL, f[0].toInt())
        assertEquals(3, f[1].toInt())
        // name is a NUL-terminated 32-byte field
        assertArrayEquals("longwave".toByteArray(), f.copyOfRange(2, 2 + 8))
        assertEquals(0, f[2 + 8].toInt())
        assertArrayEquals(secret, f.copyOfRange(2 + 32, 2 + 32 + 16))
    }

    @Test fun decodeExportedContactCard() {
        val card = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val frame = byteArrayOf(Resp.EXPORT_CONTACT.toByte()) + card
        val decoded = FrameDecoder.decode(frame)
        assertTrue(decoded is Incoming.ExportedContact)
        assertArrayEquals(card, (decoded as Incoming.ExportedContact).card)
    }
}
