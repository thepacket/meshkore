package org.thepacket.meshcore.protocol

/**
 * MeshCore companion serial/BLE frame codes.
 *
 * A frame is a raw byte sequence whose first byte is one of these codes; the
 * remainder is a code-specific payload. There is no length prefix or delimiter —
 * the BLE transport (Nordic UART Service) delivers one frame per write/notify.
 * All multi-byte integers are little-endian. Max frame size is 176 bytes.
 *
 * Values mirror the companion firmware (examples/companion_radio/MyMesh.cpp) of the
 * MeshCore project. Kept as plain Int constants rather than an enum so unknown
 * incoming codes can be carried through without throwing.
 */
object Cmd {
    const val APP_START = 1
    const val SEND_TXT_MSG = 2
    const val SEND_CHANNEL_TXT_MSG = 3
    const val GET_CONTACTS = 4
    const val GET_DEVICE_TIME = 5
    const val SET_DEVICE_TIME = 6
    const val SEND_SELF_ADVERT = 7
    const val SET_ADVERT_NAME = 8
    const val ADD_UPDATE_CONTACT = 9
    const val SYNC_NEXT_MESSAGE = 10
    const val SET_RADIO_PARAMS = 11
    const val SET_RADIO_TX_POWER = 12
    const val RESET_PATH = 13
    const val SET_ADVERT_LATLON = 14
    const val REMOVE_CONTACT = 15
    const val SHARE_CONTACT = 16
    const val EXPORT_CONTACT = 17
    const val IMPORT_CONTACT = 18
    const val REBOOT = 19
    const val GET_BATT_AND_STORAGE = 20
    const val SET_TUNING_PARAMS = 21
    const val DEVICE_QUERY = 22
    const val EXPORT_PRIVATE_KEY = 23
    const val IMPORT_PRIVATE_KEY = 24
    const val SEND_RAW_DATA = 25
    const val SEND_LOGIN = 26
    const val SEND_STATUS_REQ = 27
    const val HAS_CONNECTION = 28
    const val LOGOUT = 29
    const val GET_CONTACT_BY_KEY = 30
    const val GET_CHANNEL = 31
    const val SET_CHANNEL = 32
    const val SIGN_START = 33
    const val SIGN_DATA = 34
    const val SIGN_FINISH = 35
    const val SEND_TRACE_PATH = 36
    const val SET_DEVICE_PIN = 37
    const val SET_OTHER_PARAMS = 38
    const val SEND_TELEMETRY_REQ = 39
    const val GET_CUSTOM_VARS = 40
    const val SET_CUSTOM_VAR = 41
    const val GET_ADVERT_PATH = 42
    const val GET_TUNING_PARAMS = 43
    const val SEND_BINARY_REQ = 50
    const val FACTORY_RESET = 51
    const val SEND_PATH_DISCOVERY_REQ = 52
    const val SET_FLOOD_SCOPE_KEY = 54
    const val SEND_CONTROL_DATA = 55
    const val GET_STATS = 56
    const val SEND_ANON_REQ = 57
    const val SET_AUTOADD_CONFIG = 58
    const val GET_AUTOADD_CONFIG = 59
    const val GET_ALLOWED_REPEAT_FREQ = 60
    const val SET_PATH_HASH_MODE = 61
    const val SEND_CHANNEL_DATA = 62
    const val SET_DEFAULT_FLOOD_SCOPE = 63
    const val GET_DEFAULT_FLOOD_SCOPE = 64
    const val SEND_RAW_PACKET = 65
}

/** Synchronous reply codes (firmware → client, in response to a [Cmd]). */
object Resp {
    const val OK = 0
    const val ERR = 1
    const val CONTACTS_START = 2
    const val CONTACT = 3
    const val END_OF_CONTACTS = 4
    const val SELF_INFO = 5
    const val SENT = 6
    const val CONTACT_MSG_RECV = 7
    const val CHANNEL_MSG_RECV = 8
    const val CURR_TIME = 9
    const val NO_MORE_MESSAGES = 10
    const val EXPORT_CONTACT = 11
    const val BATT_AND_STORAGE = 12
    const val DEVICE_INFO = 13
    const val PRIVATE_KEY = 14
    const val DISABLED = 15
    const val CONTACT_MSG_RECV_V3 = 16
    const val CHANNEL_MSG_RECV_V3 = 17
    const val CHANNEL_INFO = 18
    const val SIGN_START = 19
    const val SIGNATURE = 20
    const val CUSTOM_VARS = 21
    const val ADVERT_PATH = 22
    const val TUNING_PARAMS = 23
    const val STATS = 24
    const val AUTOADD_CONFIG = 25
    const val ALLOWED_REPEAT_FREQ = 26
    const val CHANNEL_DATA_RECV = 27
    const val DEFAULT_FLOOD_SCOPE = 28
}

/** Unsolicited push codes (firmware → client). All have the high bit set. */
object Push {
    const val ADVERT = 0x80
    const val PATH_UPDATED = 0x81
    const val SEND_CONFIRMED = 0x82
    const val MSG_WAITING = 0x83
    const val RAW_DATA = 0x84
    const val LOGIN_SUCCESS = 0x85
    const val LOGIN_FAIL = 0x86
    const val STATUS_RESPONSE = 0x87
    const val LOG_RX_DATA = 0x88
    const val TRACE_DATA = 0x89
    const val NEW_ADVERT = 0x8A
    const val TELEMETRY_RESPONSE = 0x8B
    const val BINARY_RESPONSE = 0x8C
    const val PATH_DISCOVERY_RESPONSE = 0x8D
    const val CONTROL_DATA = 0x8E
    const val CONTACT_DELETED = 0x8F
    const val CONTACTS_FULL = 0x90
}

/** Text message types (TxtDataHelpers.h). */
object TxtType {
    const val PLAIN = 0
    const val CLI_DATA = 1
    const val SIGNED_PLAIN = 2
}

/** Sub-types for CMD_GET_STATS / RESP_CODE_STATS. */
object StatsType {
    const val CORE = 0
    const val RADIO = 1
    const val PACKETS = 2
}

/**
 * Packet payload types (Packet.h). The first byte of a raw packet is the header:
 * route = h & 0x03, payloadType = (h >> 2) & 0x0F, version = (h >> 6) & 0x03.
 */
object PayloadType {
    const val REQ = 0x00
    const val RESPONSE = 0x01
    const val TXT_MSG = 0x02
    const val ACK = 0x03
    const val ADVERT = 0x04
    const val GRP_TXT = 0x05
    const val GRP_DATA = 0x06
    const val ANON_REQ = 0x07
    const val PATH = 0x08
    const val TRACE = 0x09
    const val MULTIPART = 0x0A
    const val CONTROL = 0x0B
    const val RAW_CUSTOM = 0x0F

    fun name(type: Int): String = when (type) {
        REQ -> "REQ"; RESPONSE -> "RESP"; TXT_MSG -> "TXT"; ACK -> "ACK"; ADVERT -> "ADVERT"
        GRP_TXT -> "GRP_TXT"; GRP_DATA -> "GRP_DATA"; ANON_REQ -> "ANON_REQ"; PATH -> "PATH"
        TRACE -> "TRACE"; MULTIPART -> "MULTIPART"; CONTROL -> "CONTROL"; RAW_CUSTOM -> "RAW"
        else -> "0x%02X".format(type)
    }
}

/** Packet route types (low 2 bits of the header). */
object RouteType {
    const val TRANSPORT_FLOOD = 0
    const val FLOOD = 1
    const val DIRECT = 2
    const val TRANSPORT_DIRECT = 3

    fun name(route: Int): String = when (route) {
        FLOOD, TRANSPORT_FLOOD -> "flood"
        DIRECT, TRANSPORT_DIRECT -> "direct"
        else -> "?"
    }
}

/** A code is a push (rather than a synchronous reply) iff its high bit is set. */
fun isPush(code: Int): Boolean = (code and 0x80) != 0
