package io.github.ringlink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.github.ringlink.L
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A ring seen advertising nearby but not yet paired with this phone. */
data class DiscoveredRing(val address: String, val name: String, val rssi: Int)

/**
 * Finds rings that are not yet paired.
 *
 * Only needed to adopt a *new* ring: once bonded, a ring is reachable without scanning, because
 * Bluetooth bonds live in the system stack and are shared by every app. That is why the app asks for
 * scan permission only when the user goes looking for another ring.
 */
@SuppressLint("MissingPermission")
class RingScanner(private val context: Context) {

    fun scan(): Flow<List<DiscoveredRing>> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            L.w("cannot scan: Bluetooth unavailable")
            close()
            return@callbackFlow
        }

        val found = LinkedHashMap<String, DiscoveredRing>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: result.scanRecord?.deviceName ?: return
                if (!RingBleClient.looksLikeRing(name)) return
                val ring = DiscoveredRing(result.device.address, name, result.rssi)
                if (found.put(ring.address, ring) == null) L.i("found ring $name (${ring.address})")
                trySend(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                L.e("scan failed: $errorCode")
                close()
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(emptyList(), settings, callback) }
            .onFailure { L.e("could not start scan", it); close() }

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
