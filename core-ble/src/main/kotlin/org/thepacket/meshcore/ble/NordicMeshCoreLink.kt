package org.thepacket.meshcore.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import org.thepacket.meshcore.protocol.FrameDecoder
import org.thepacket.meshcore.protocol.Incoming
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Nordic-BLE-Library implementation of [MeshCoreLink] over the companion's NUS.
 *
 * Each NUS notification is exactly one frame, so we decode per-notification with
 * [FrameDecoder] and emit on [incoming]. Writes go to RX as WRITE_TYPE_NO_RESPONSE
 * (the firmware reads one whole write as one frame).
 *
 * Note: this targets the classic [BleManager] callback pattern (Nordic ble 2.7.x).
 * If you bump the library major version, revisit getGattCallback()/initialize().
 */
class NordicMeshCoreLink(context: Context) : MeshCoreLink {

    private val manager = Manager(context.applicationContext)

    private val _state = MutableStateFlow(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    /** Connect to a device discovered by scanning (see [companionScanFilter]). */
    suspend fun connect(device: BluetoothDevice): Unit = suspendCancellableCoroutine { cont ->
        _state.value = LinkState.Connecting
        manager.connect(device)
            .retry(3, 200)
            .useAutoConnect(false)
            .timeout(15_000)
            .done {
                _state.value = LinkState.Connected
                if (cont.isActive) cont.resume(Unit)
            }
            .fail { _, status ->
                _state.value = LinkState.Failed
                if (cont.isActive) cont.resumeWithException(BleException("connect failed: status=$status"))
            }
            .enqueue()
    }

    /** No-device overload of the interface — requires a prior [connect] with a device. */
    override suspend fun connect() =
        throw UnsupportedOperationException("Call connect(BluetoothDevice) with a scanned device")

    override suspend fun disconnect() {
        _state.value = LinkState.Disconnecting
        manager.disconnect().enqueue()
        _state.value = LinkState.Disconnected
    }

    override suspend fun send(frame: ByteArray) = manager.sendFrame(frame)

    private inner class Manager(ctx: Context) : BleManager(ctx) {
        private var rx: BluetoothGattCharacteristic? = null
        private var tx: BluetoothGattCharacteristic? = null

        override fun getGattCallback(): BleManagerGattCallback = GattCallback()

        suspend fun sendFrame(frame: ByteArray): Unit = suspendCancellableCoroutine { cont ->
            val target = rx
            if (target == null) {
                cont.resumeWithException(BleException("not connected (no RX characteristic)"))
                return@suspendCancellableCoroutine
            }
            writeCharacteristic(target, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                .split() // chunk across MTU; the firmware reassembles
                .done { if (cont.isActive) cont.resume(Unit) }
                .fail { _, status -> if (cont.isActive) cont.resumeWithException(BleException("write failed: $status")) }
                .enqueue()
        }

        private inner class GattCallback : BleManagerGattCallback() {
            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                val service = gatt.getService(Nus.SERVICE) ?: return false
                rx = service.getCharacteristic(Nus.RX)
                tx = service.getCharacteristic(Nus.TX)
                return rx != null && tx != null
            }

            override fun initialize() {
                requestMtu(247).enqueue() // larger MTU = fewer fragments for big frames
                setNotificationCallback(tx).with { _, data: Data ->
                    val bytes = data.value ?: return@with
                    _incoming.tryEmit(FrameDecoder.decode(bytes))
                }
                enableNotifications(tx).enqueue()
            }

            override fun onServicesInvalidated() {
                rx = null
                tx = null
                _state.value = LinkState.Disconnected
            }
        }
    }
}

class BleException(message: String) : Exception(message)
