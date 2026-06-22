package org.thepacket.meshcore.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A companion seen while scanning. */
data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String?,
    val rssi: Int,
)

/**
 * Scans for MeshCore companions advertising the Nordic UART Service.
 *
 * Caller must hold BLUETOOTH_SCAN (API 31+) or ACCESS_FINE_LOCATION (API 26–30)
 * before collecting; the flow throws SecurityException otherwise. Scanning stops
 * automatically when the flow is cancelled.
 */
class CompanionScanner(context: Context) {
    private val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        .adapter?.bluetoothLeScanner

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScannedDevice> = callbackFlow {
        val s = scanner ?: run {
            close(BleException("Bluetooth unavailable or disabled"))
            return@callbackFlow
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Nus.SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(ScannedDevice(result.device, result.scanRecord?.deviceName ?: result.device.name, result.rssi))
            }
            override fun onScanFailed(errorCode: Int) {
                close(BleException("scan failed: $errorCode"))
            }
        }
        s.startScan(listOf(filter), settings, cb)
        awaitClose { s.stopScan(cb) }
    }
}
