package org.thepacket.meshcore.ble

import java.util.UUID

/**
 * The MeshCore companion exposes a Nordic UART Service (NUS). Frames are written
 * to [RX] and received as notifications on [TX]. One frame per write/notify; no
 * length prefix (see core-protocol). Verified against the firmware:
 * src/helpers/esp32/SerialBLEInterface.cpp and nrf52/SerialBLEInterface.cpp.
 */
object Nus {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write (client → device)
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify (device → client)
}
