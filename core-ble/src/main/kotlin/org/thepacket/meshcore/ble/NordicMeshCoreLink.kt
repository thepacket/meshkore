package org.thepacket.meshcore.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
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

    private val appContext = context.applicationContext
    private val manager = Manager(appContext)

    private val _state = MutableStateFlow(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    /** Connect to a device discovered by scanning (see [companionScanFilter]). */
    suspend fun connect(device: BluetoothDevice) {
        // MeshCore companions use MITM passkey pairing. Bonding must complete BEFORE
        // the GATT link is opened: bonding mid-connection makes the nRF52 drop the
        // link to re-encrypt it, which makes the GATT connect time out (status=-5).
        // So we bond at the HCI level first (this shows the system PIN dialog without
        // a GATT connection), then connect to the now-bonded device.
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _state.value = LinkState.Bonding
            bond(device)
            // After a fresh bond the nRF52 finishes re-encrypting the link; connecting
            // immediately tends to fail with status=133 (GATT_ERROR). Let it settle.
            delay(600)
        }
        gattConnect(device)
    }

    /** Create a bond (system PIN prompt) and suspend until it succeeds or fails. */
    private suspend fun bond(device: BluetoothDevice): Unit = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> {
                        runCatching { appContext.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(Unit)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        runCatching { appContext.unregisterReceiver(this) }
                        _state.value = LinkState.Failed
                        if (cont.isActive) cont.resumeWithException(BleException("bonding failed / cancelled"))
                    }
                    // BOND_BONDING: pairing in progress (PIN dialog), keep waiting.
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        // ACTION_BOND_STATE_CHANGED is broadcast by the system (another process), so the
        // receiver must be EXPORTED to receive it on API 33+; NOT_EXPORTED would silently
        // drop it. It is a protected broadcast (only the system can send it), so this is safe.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }
        if (!device.createBond()) {
            runCatching { appContext.unregisterReceiver(receiver) }
            _state.value = LinkState.Failed
            if (cont.isActive) cont.resumeWithException(BleException("createBond() rejected"))
        }
    }

    private suspend fun gattConnect(device: BluetoothDevice): Unit = suspendCancellableCoroutine { cont ->
        _state.value = LinkState.Connecting
        // status=133 (GATT_ERROR) is Android's generic, usually-transient connect failure;
        // Nordic closes and reopens the GATT client between attempts, so a few retries with
        // a longer back-off clears most of them.
        manager.connect(device)
            .retry(4, 600)
            .useAutoConnect(false)
            .timeout(20_000)
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

    // BleManagerGattCallback is deprecated in newer Nordic releases but is the stable,
    // documented pattern for ble 2.7.x (pinned in the version catalog).
    @Suppress("DEPRECATION")
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
                // Bonding is handled before the GATT connect (see connect()), so the
                // link is already encrypted by the time we reach the NUS characteristics.
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
