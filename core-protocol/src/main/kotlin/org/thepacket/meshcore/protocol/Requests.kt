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
     * with RESP_CODE_SELF_INFO. Layout: [cmd, appVer, reserved(6), appName...].
     * `appVer` selects the protocol generation (>=3 enables the *_V3 message
     * receive frames). TODO: confirm reserved-field width against the firmware
     * handshake handler before relying on appName parsing on the device side.
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

    /** Auto-add config: [AutoAdd] flag bitmask + max hops (0 = unlimited). */
    fun setAutoAddConfig(flags: Int, maxHops: Int): ByteArray =
        FrameWriter().u8(Cmd.SET_AUTOADD_CONFIG).u8(flags).u8(maxHops).build()

    fun getAutoAddConfig(): ByteArray = FrameWriter().u8(Cmd.GET_AUTOADD_CONFIG).build()

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

    /** Remote login to a repeater/room. Layout: [cmd, pubKeyPrefix(6), password...]. */
    fun sendLogin(pubKeyPrefix: ByteArray, password: String): ByteArray =
        FrameWriter()
            .u8(Cmd.SEND_LOGIN)
            .bytes(pubKeyPrefix.copyOf(6))
            .str(password)
            .build()

    fun sendStatusRequest(pubKeyPrefix: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.SEND_STATUS_REQ).bytes(pubKeyPrefix.copyOf(6)).build()

    fun logout(pubKeyPrefix: ByteArray): ByteArray =
        FrameWriter().u8(Cmd.LOGOUT).bytes(pubKeyPrefix.copyOf(6)).build()

    /**
     * Trace a path through specific hops. Layout: [cmd, tag(u32), auth(u32), flags(1), path...].
     * `path` is the ordered list of 1-byte hop hashes (each a node's public-key prefix); the
     * firmware requires at least one path byte. `tag` correlates the PUSH_CODE_TRACE_DATA reply.
     */
    fun sendTracePath(tag: Long, path: ByteArray, auth: Long = 0, flags: Int = 0): ByteArray =
        FrameWriter().u8(Cmd.SEND_TRACE_PATH).u32(tag).u32(auth).u8(flags).bytes(path).build()

    fun reboot(): ByteArray = FrameWriter().u8(Cmd.REBOOT).build()

    /** Erase all device settings and reboot. Payload is the literal "reset" guard. */
    fun factoryReset(): ByteArray = FrameWriter().u8(Cmd.FACTORY_RESET).str("reset").build()

    /** Request a stats blob (reply RESP_CODE_STATS). See [StatsType]. */
    fun getStats(type: Int): ByteArray = FrameWriter().u8(Cmd.GET_STATS).u8(type).build()

    fun setRadioTxPower(dbm: Int): ByteArray =
        FrameWriter().u8(Cmd.SET_RADIO_TX_POWER).u8(dbm).build()
}
