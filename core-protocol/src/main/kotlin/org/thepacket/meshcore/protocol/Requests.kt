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

    fun deviceQuery(): ByteArray = FrameWriter().u8(Cmd.DEVICE_QUERY).build()

    fun getDeviceTime(): ByteArray = FrameWriter().u8(Cmd.GET_DEVICE_TIME).build()

    fun setDeviceTime(epochSecs: Long): ByteArray =
        FrameWriter().u8(Cmd.SET_DEVICE_TIME).u32(epochSecs).build()

    fun getBattAndStorage(): ByteArray = FrameWriter().u8(Cmd.GET_BATT_AND_STORAGE).build()

    /** Incremental sync: pass the last `lastMod` seen to fetch only newer contacts (0 = all). */
    fun getContacts(since: Long = 0): ByteArray =
        FrameWriter().u8(Cmd.GET_CONTACTS).u32(since).build()

    fun syncNextMessage(): ByteArray = FrameWriter().u8(Cmd.SYNC_NEXT_MESSAGE).build()

    fun sendSelfAdvert(flood: Boolean): ByteArray =
        FrameWriter().u8(Cmd.SEND_SELF_ADVERT).u8(if (flood) 1 else 0).build()

    fun setAdvertName(name: String): ByteArray =
        FrameWriter().u8(Cmd.SET_ADVERT_NAME).str(name).build()

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

    /** Trace-route request. `tag` is a random u32 used to correlate the result. */
    fun sendTracePath(pubKeyPrefix: ByteArray, tag: Long): ByteArray =
        FrameWriter().u8(Cmd.SEND_TRACE_PATH).u32(tag).bytes(pubKeyPrefix.copyOf(6)).build()

    fun reboot(): ByteArray = FrameWriter().u8(Cmd.REBOOT).build()

    /**
     * Set LoRa radio parameters. Layout (confirm units against MyMesh.cpp):
     * [cmd, freq(u32 kHz), bw(u32 kHz), sf(u8), cr(u8)].
     */
    fun setRadioParams(freqKhz: Long, bwKhz: Long, sf: Int, cr: Int): ByteArray =
        FrameWriter()
            .u8(Cmd.SET_RADIO_PARAMS)
            .u32(freqKhz)
            .u32(bwKhz)
            .u8(sf)
            .u8(cr)
            .build()

    fun setRadioTxPower(dbm: Int): ByteArray =
        FrameWriter().u8(Cmd.SET_RADIO_TX_POWER).u8(dbm).build()
}
