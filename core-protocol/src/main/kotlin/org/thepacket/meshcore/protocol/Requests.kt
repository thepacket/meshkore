package org.thepacket.meshcore.protocol

/**
 * Builders for client → firmware request frames. Each returns the raw bytes to
 * write to the NUS RX characteristic.
 *
 * Frames whose exact payload layout still needs confirming against the firmware
 * (examples/companion_radio/MyMesh.cpp) are marked TODO rather than guessed, so we
 * never ship a fabricated wire format. The ones below are the P0/P1 set we have
 * confirmed offsets for.
 */
object Requests {

    /**
     * Handshake. Must be the first frame after connecting. The firmware replies
     * with RESP_CODE_SELF_INFO. Layout: [cmd, reserved(7), appName...].
     *
     * Verified against MyMesh.cpp `CMD_APP_START` handler: it requires `len >= 8`
     * and reads `app_name` at offset 8, treating bytes 1..7 as reserved. Our
     * `appVer(1) + reserved(6)` exactly fills those 7 bytes, so the name lands at
     * offset 8. Note: APP_START does NOT read the version byte — the protocol
     * generation (>=3 enables the *_V3 receive frames) is selected by
     * [deviceQuery]'s version byte (firmware `app_target_ver`), not here. The
     * `appVer` argument is kept only to occupy reserved byte 1.
     */
    fun appStart(appVer: Int = 3, appName: String = "meshcore-android"): ByteArray =
        FrameWriter()
            .u8(Cmd.APP_START)
            .u8(appVer)
            .bytes(ByteArray(6))
            .str(appName)
            .build()

    /** Query device info. Requires the app-protocol version byte (firmware needs len >= 2). */
    fun deviceQuery(appVer: Int = 3): ByteArray = FrameWriter().u8(Cmd.DEVICE_QUERY).u8(appVer).build()

    fun getDeviceTime(): ByteArray = FrameWriter().u8(Cmd.GET_DEVICE_TIME).build()

    fun setDeviceTime(epochSecs: Long): ByteArray =
        FrameWriter().u8(Cmd.SET_DEVICE_TIME).u32(epochSecs).build()

    fun getBattAndStorage(): ByteArray = FrameWriter().u8(Cmd.GET_BATT_AND_STORAGE).build()

    /** Incremental sync: pass the last `lastMod` seen to fetch only newer contacts (0 = all). */
    fun getContacts(since: Long = 0): ByteArray =
        FrameWriter().u8(Cmd.GET_CONTACTS).u32(since).build()

    fun syncNextMessage(): ByteArray = FrameWriter().u8(Cmd.SYNC_NEXT_MESSAGE).build()

    /** Fetch one channel slot's info (reply RESP_CODE_CHANNEL_INFO, or ERR if the slot is empty). */
    fun getChannel(index: Int): ByteArray =
        FrameWriter().u8(Cmd.GET_CHANNEL).u8(index).build()

    fun sendSelfAdvert(flood: Boolean): ByteArray =
        FrameWriter().u8(Cmd.SEND_SELF_ADVERT).u8(if (flood) 1 else 0).build()

    fun setAdvertName(name: String): ByteArray =
        FrameWriter().u8(Cmd.SET_ADVERT_NAME).str(name).build()

    /** Add or update a contact in the device's store (same layout as RESP_CODE_CONTACT). */
    fun addUpdateContact(c: Contact): ByteArray = FrameWriter()
        .u8(Cmd.ADD_UPDATE_CONTACT)
        .bytes(c.publicKey.copyOf(32))
        .u8(c.type)
        .u8(c.flags)
        .u8(c.outPathLen)
        .bytes(c.outPath.copyOf(64))
        .bytes(name32(c.name))
        .u32(c.lastAdvert)
        .i32(c.gpsLat)
        .i32(c.gpsLon)
        .u32(c.lastMod)
        .build()

    /** 32-byte NUL-terminated name field (truncated to 31 chars + NUL). */
    private fun name32(s: String): ByteArray {
        val out = ByteArray(32)
        val b = s.toByteArray(Charsets.UTF_8)
        System.arraycopy(b, 0, out, 0, minOf(b.size, 31))
        return out
    }

    /** Remove a contact from the device store. Frame: [cmd, pubKey(32)]. */
    fun removeContact(pubKey: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.REMOVE_CONTACT).bytes(pubKey.copyOf(32)).build()

    /** Re-advertise a contact zero-hop so direct neighbours can discover it. Frame: [cmd, pubKey(32)]. */
    fun shareContact(pubKey: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.SHARE_CONTACT).bytes(pubKey.copyOf(32)).build()

    /** Forget a contact's learned return path (next message re-floods). Frame: [cmd, pubKey(32)]. */
    fun resetPath(pubKey: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.RESET_PATH).bytes(pubKey.copyOf(32)).build()

    /** Fetch one contact by full public key (reply RESP_CODE_CONTACT, or ERR if unknown). */
    fun getContactByKey(pubKey: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.GET_CONTACT_BY_KEY).bytes(pubKey.copyOf(32)).build()

    /**
     * Export a shareable contact "card" (reply RESP_CODE_EXPORT_CONTACT carrying the raw
     * advert packet). Pass null to export THIS node's own card.
     */
    fun exportContact(pubKey: ByteArray? = null): ByteArray {
        val w = FrameWriter().u8(Cmd.EXPORT_CONTACT)
        if (pubKey != null) w.bytes(pubKey.copyOf(32))
        return w.build()
    }

    /** Import a contact from an exported "card" (the raw advert-packet bytes from [exportContact]). */
    fun importContact(card: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.IMPORT_CONTACT).bytes(card).build()

    /**
     * Create or replace a channel slot. Frame: [cmd, index(1), name(32), secret(16)].
     * `secret` is the 128-bit channel key (16 bytes; shorter input is zero-padded).
     */
    fun setChannel(index: Int, name: String, secret: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.SET_CHANNEL).u8(index).bytes(name32(name)).bytes(secret.copyOf(16)).build()

    /** Set this node's advertised GPS position. lat/lon in 1e-6 degrees (±90 / ±180). */
    fun setAdvertLatLon(latE6: Int, lonE6: Int): ByteArray =
        FrameWriter().u8(Cmd.SET_ADVERT_LATLON).i32(latE6).i32(lonE6).build()

    /**
     * Set LoRa radio params. Firmware units: frequency in kHz (MHz×1000), bandwidth in Hz
     * (kHz×1000), sf 5–12, cr 5–8, repeat = client-repeat on/off.
     */
    fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int, repeat: Boolean): ByteArray =
        FrameWriter()
            .u8(Cmd.SET_RADIO_PARAMS)
            .u32(freqKhz)
            .u32(bwHz)
            .u8(sf)
            .u8(cr)
            .u8(if (repeat) 1 else 0)
            .build()

    /**
     * Misc params (CMD_SET_OTHER_PARAMS). telemetry byte = (env<<4)|(loc<<2)|base.
     * All four bytes are sent so nothing is left at a stale default.
     */
    fun setOtherParams(
        manualAddContacts: Boolean,
        telemetryBase: Int,
        telemetryLoc: Int,
        telemetryEnv: Int,
        advertLocPolicy: Int,
        multiAcks: Int,
    ): ByteArray = FrameWriter()
        .u8(Cmd.SET_OTHER_PARAMS)
        .u8(if (manualAddContacts) 1 else 0)
        .u8(((telemetryEnv and 3) shl 4) or ((telemetryLoc and 3) shl 2) or (telemetryBase and 3))
        .u8(advertLocPolicy)
        .u8(multiAcks)
        .build()

    /** Tuning params: rx-delay base and airtime factor (sent as value×1000). */
    fun setTuningParams(rxDelayBase: Double, airtimeFactor: Double): ByteArray =
        FrameWriter()
            .u8(Cmd.SET_TUNING_PARAMS)
            .u32((rxDelayBase * 1000).toLong())
            .u32((airtimeFactor * 1000).toLong())
            .build()

    fun getTuningParams(): ByteArray = FrameWriter().u8(Cmd.GET_TUNING_PARAMS).build()

    /** Request this node's own telemetry (a 4-byte frame triggers the self reply). */
    fun selfTelemetry(): ByteArray = FrameWriter().u8(Cmd.SEND_TELEMETRY_REQ).u8(0).u8(0).u8(0).build()

    /**
     * Request a remote contact's telemetry. Layout: [cmd, reserved(3), pubKey(32)].
     * Verified against MyMesh.cpp CMD_SEND_TELEMETRY_REQ: the remote branch (len >=
     * 4 + PUB_KEY_SIZE) looks the contact up by FULL 32-byte public key at offset 4,
     * then sends a REQ_TYPE_GET_TELEMETRY_DATA. The device first replies RESP_CODE_SENT
     * (tag/timeout); the readings arrive later as a TELEMETRY_RESPONSE push carrying the
     * contact's 6-byte key prefix.
     */
    fun requestTelemetry(pubKey: ByteArray): ByteArray {
        require(pubKey.size >= 32) { "pubKey must be the full 32 bytes" }
        return FrameWriter()
            .u8(Cmd.SEND_TELEMETRY_REQ)
            .bytes(ByteArray(3))
            .bytes(pubKey.copyOf(32))
            .build()
    }

    /**
     * CMD_SEND_PATH_DISCOVERY_REQ — ask the device to discover the route to a contact.
     * Layout: [cmd, reserved=0, dest pub_key(32)]. The result arrives as
     * PUSH_CODE_PATH_DISCOVERY_RESPONSE. Verified against MyMesh.cpp `handleCmdFrame`.
     */
    fun sendPathDiscoveryReq(pubKey: ByteArray): ByteArray {
        require(pubKey.size >= 32) { "pubKey must be the full 32 bytes" }
        return FrameWriter()
            .u8(Cmd.SEND_PATH_DISCOVERY_REQ)
            .u8(0) // reserved
            .bytes(pubKey.copyOf(32))
            .build()
    }

    /** Auto-add config: [AutoAdd] flag bitmask + max hops (0 = unlimited). */
    fun setAutoAddConfig(flags: Int, maxHops: Int): ByteArray =
        FrameWriter().u8(Cmd.SET_AUTOADD_CONFIG).u8(flags).u8(maxHops).build()

    fun getAutoAddConfig(): ByteArray = FrameWriter().u8(Cmd.GET_AUTOADD_CONFIG).build()

    /** Query the frequencies (kHz ranges) on which this firmware permits client-repeat. */
    fun getAllowedRepeatFreq(): ByteArray = FrameWriter().u8(Cmd.GET_ALLOWED_REPEAT_FREQ).build()

    /** Path-hash mode (0–2). Frame: [cmd, reserved(0), mode]. */
    fun setPathHashMode(mode: Int): ByteArray =
        FrameWriter().u8(Cmd.SET_PATH_HASH_MODE).u8(0).u8(mode).build()

    /**
     * Direct text message to a contact.
     * Layout: [cmd, txtType, attempt, timestamp(u32), pubKeyPrefix(6), text...].
     * `pubKeyPrefix` is the first 6 bytes of the recipient's public key.
     */
    fun sendTextMessage(
        pubKeyPrefix: ByteArray,
        text: String,
        timestamp: Long,
        txtType: Int = 0,
        attempt: Int = 0,
    ): ByteArray {
        require(pubKeyPrefix.size >= 6) { "pubKeyPrefix must be >= 6 bytes" }
        return FrameWriter()
            .u8(Cmd.SEND_TXT_MSG)
            .u8(txtType)
            .u8(attempt)
            .u32(timestamp)
            .bytes(pubKeyPrefix.copyOf(6))
            .str(text)
            .build()
    }

    /** Channel (group) text message. Layout: [cmd, txtType, channelIdx, timestamp(u32), text...]. */
    fun sendChannelTextMessage(channelIdx: Int, text: String, timestamp: Long): ByteArray =
        FrameWriter()
            .u8(Cmd.SEND_CHANNEL_TXT_MSG)
            .u8(TxtType.PLAIN)
            .u8(channelIdx)
            .u32(timestamp)
            .str(text)
            .build()

    /**
     * Remote login to a repeater/room. Layout: [cmd, pubKey(32), password...].
     * Verified against MyMesh.cpp CMD_SEND_LOGIN: it requires len >= 1 + PUB_KEY_SIZE and looks
     * the contact up by the FULL 32-byte key (not a 6-byte prefix). Reply is a LOGIN_SUCCESS or
     * LOGIN_FAIL push.
     */
    fun sendLogin(pubKey: ByteArray, password: String): ByteArray =
        FrameWriter()
            .u8(Cmd.SEND_LOGIN)
            .bytes(pubKey.copyOf(32))
            .str(password)
            .build()

    /** Request a repeater/room's status. Layout: [cmd, pubKey(32)]. Full key, like [sendLogin]. */
    fun sendStatusRequest(pubKey: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.SEND_STATUS_REQ).bytes(pubKey.copyOf(32)).build()

    /** End a repeater/room session. Layout: [cmd, pubKey(32)]. Full key, like [sendLogin]. */
    fun logout(pubKey: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.LOGOUT).bytes(pubKey.copyOf(32)).build()

    /**
     * Send a CLI/admin command to a logged-in repeater/room. This is a text message with
     * txtType = CLI_DATA addressed by 6-byte prefix; the reply comes back as a CLI_DATA message.
     */
    fun sendRepeaterCommand(pubKeyPrefix: ByteArray, command: String, timestamp: Long): ByteArray =
        sendTextMessage(pubKeyPrefix, command, timestamp, txtType = TxtType.CLI_DATA)

    /**
     * Trace a path through specific hops. Layout: [cmd, tag(u32), auth(u32), flags(1), path...].
     * `path` is the ordered list of 1-byte hop hashes (each a node's public-key prefix); the
     * firmware requires at least one path byte. `tag` correlates the PUSH_CODE_TRACE_DATA reply.
     */
    fun sendTracePath(tag: Long, path: ByteArray, auth: Long = 0, flags: Int = 0): ByteArray =
        FrameWriter().u8(Cmd.SEND_TRACE_PATH).u32(tag).u32(auth).u8(flags).bytes(path).build()

    /**
     * Blind, zero-hop node-discovery request. Unlike telemetry/status requests it carries NO
     * recipient key — the firmware broadcasts it zero-hop and every direct neighbour whose type
     * matches replies with a NODE_DISCOVER_RESP (arrives as PUSH_CODE_CONTROL_DATA). Verified
     * against MyMesh.cpp `CMD_SEND_CONTROL_DATA` + simple_repeater `onControlDataRecv`.
     *
     * Layout: [cmd, NODE_DISCOVER_REQ|prefixOnly, typeFilter, tag(u32)[, since(u32)]].
     * `typeFilter` is a bitmask of (1 shl ContactType.*); `tag` correlates the replies.
     */
    fun nodeDiscoverReq(typeFilter: Int, tag: Long, prefixOnly: Boolean = true): ByteArray =
        FrameWriter()
            .u8(Cmd.SEND_CONTROL_DATA)
            .u8(CtlType.NODE_DISCOVER_REQ or (if (prefixOnly) 1 else 0))
            .u8(typeFilter)
            .u32(tag)
            .build()

    /**
     * Ask a repeater for its neighbour table (nodes it has heard, zero-hop). A binary request to
     * the FULL 32-byte key; the reply arrives as a BINARY_RESPONSE carrying, per neighbour, a
     * `prefixLength`-byte key prefix + secs-ago + SNR. Verified against simple_repeater
     * REQ_TYPE_GET_NEIGHBOURS. Layout:
     *   [cmd, pubKey(32), GET_NEIGHBOURS, version(0), count, offset(u16), orderBy, prefixLength, nonce(u32)]
     * orderBy: 0 newest→oldest, 1 oldest→newest, 2 strongest→weakest, 3 weakest→strongest.
     */
    fun requestNeighbours(
        pubKey: ByteArray,
        nonce: Long,
        count: Int = 255,
        prefixLength: Int = 6,
        orderBy: Int = 2,
    ): ByteArray {
        require(pubKey.size >= 32) { "pubKey must be the full 32 bytes" }
        return FrameWriter()
            .u8(Cmd.SEND_BINARY_REQ)
            .bytes(pubKey.copyOf(32))
            .u8(BinReqType.GET_NEIGHBOURS)
            .u8(0) // request version
            .u8(count)
            .u16(0) // offset
            .u8(orderBy)
            .u8(prefixLength)
            .u32(nonce)
            .build()
    }

    /**
     * Request telemetry min/max/avg over a time window from a logged-in node (read-only ACL).
     * Layout: [cmd, pubKey(32), GET_MMA, startSecsAgo(u32), endSecsAgo(u32), 0, 0]. Verified
     * against simple_sensor REQ_TYPE_GET_AVG_MIN_MAX. Reply is a BINARY_RESPONSE.
     */
    fun requestMma(pubKey: ByteArray, startSecsAgo: Long, endSecsAgo: Long): ByteArray {
        require(pubKey.size >= 32) { "pubKey must be the full 32 bytes" }
        return FrameWriter()
            .u8(Cmd.SEND_BINARY_REQ)
            .bytes(pubKey.copyOf(32))
            .u8(BinReqType.GET_MMA)
            .u32(startSecsAgo)
            .u32(endSecsAgo)
            .u8(0) // reserved (res1)
            .u8(0) // reserved (res2)
            .build()
    }

    /**
     * Request a repeater/room's access-control list (admin only). Layout:
     * [cmd, pubKey(32), GET_ACL, 0, 0]. Reply is a BINARY_RESPONSE of (prefix(6), permissions(1)).
     */
    fun requestAcl(pubKey: ByteArray): ByteArray {
        require(pubKey.size >= 32) { "pubKey must be the full 32 bytes" }
        return FrameWriter()
            .u8(Cmd.SEND_BINARY_REQ)
            .bytes(pubKey.copyOf(32))
            .u8(BinReqType.GET_ACL)
            .u8(0).u8(0) // reserved
            .build()
    }

    /**
     * Keep a repeater/room session alive (REQ_TYPE_KEEP_ALIVE). Any request resets the node's
     * session timer; this is the lightweight, no-reply one. Layout: [cmd, pubKey(32), KEEP_ALIVE].
     */
    fun keepAlive(pubKey: ByteArray): ByteArray {
        require(pubKey.size >= 32) { "pubKey must be the full 32 bytes" }
        return FrameWriter().u8(Cmd.SEND_BINARY_REQ).bytes(pubKey.copyOf(32)).u8(BinReqType.KEEP_ALIVE).build()
    }

    /** Request a node's owner info (firmware version, node name, owner). Layout: [cmd, pubKey(32), GET_OWNER_INFO]. */
    fun requestOwnerInfo(pubKey: ByteArray): ByteArray {
        require(pubKey.size >= 32) { "pubKey must be the full 32 bytes" }
        return FrameWriter().u8(Cmd.SEND_BINARY_REQ).bytes(pubKey.copyOf(32)).u8(BinReqType.GET_OWNER_INFO).build()
    }

    fun reboot(): ByteArray = FrameWriter().u8(Cmd.REBOOT).build()

    /** Erase all device settings and reboot. Payload is the literal "reset" guard. */
    fun factoryReset(): ByteArray = FrameWriter().u8(Cmd.FACTORY_RESET).str("reset").build()

    /** Request a stats blob (reply RESP_CODE_STATS). See [StatsType]. */
    fun getStats(type: Int): ByteArray = FrameWriter().u8(Cmd.GET_STATS).u8(type).build()

    fun setRadioTxPower(dbm: Int): ByteArray =
        FrameWriter().u8(Cmd.SET_RADIO_TX_POWER).u8(dbm).build()
}
