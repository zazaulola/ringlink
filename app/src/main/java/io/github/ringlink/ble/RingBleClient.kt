package io.github.ringlink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import io.github.ringlink.protocol.RingTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The Bluetooth half of the client: one GATT connection to the ring, exposed to the protocol layer
 * as a [RingTransport].
 *
 * Android allows a single outstanding GATT operation per connection, so writes are serialised
 * through a mutex and each one waits for its completion callback before the next begins.
 */
@SuppressLint("MissingPermission")
class RingBleClient(private val context: Context) : RingTransport {

    override val incoming = Channel<ByteArray>(capacity = 256)

    private var gatt: BluetoothGatt? = null
    private val writeLock = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var connected = CompletableDeferred<Boolean>()
    private var servicesReady = CompletableDeferred<Boolean>()

    @Volatile var isConnected: Boolean = false
        private set

    var onConnectionChange: ((Boolean) -> Unit)? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    onConnectionChange?.invoke(true)
                    if (!connected.isCompleted) connected.complete(true)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    onConnectionChange?.invoke(false)
                    if (!connected.isCompleted) connected.complete(false)
                    if (!servicesReady.isCompleted) servicesReady.complete(false)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ok = status == BluetoothGatt.GATT_SUCCESS && subscribe(g)
            if (!servicesReady.isCompleted) servicesReady.complete(ok)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ delivers the value as a parameter; older devices read it off the characteristic.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (ch.uuid == NOTIFY_CHAR) incoming.trySend(value)
        }

        @Deprecated("Pre-33 callback", ReplaceWith(""))
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == NOTIFY_CHAR) ch.value?.let { incoming.trySend(it.copyOf()) }
        }
    }

    /** Connect to an already-bonded ring. Bonds are shared per device, not per app. */
    suspend fun connect(address: String, timeoutMs: Long = 30_000): Boolean {
        disconnect()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        val adapter: BluetoothAdapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false

        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()
        // autoConnect keeps the address on the controller allow-list, so the link comes back by
        // itself after the ring drifts out of range instead of timing out permanently.
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)

        val up = withTimeoutOrNull(timeoutMs) { connected.await() } ?: false
        if (!up) return false
        return withTimeoutOrNull(timeoutMs) { servicesReady.await() } ?: false
    }

    private fun subscribe(g: BluetoothGatt): Boolean {
        val service = g.getService(SERVICE) ?: return false
        val notify = service.getCharacteristic(NOTIFY_CHAR) ?: return false
        if (!g.setCharacteristicNotification(notify, true)) return false
        val cccd = notify.getDescriptor(CCCD) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            val g = gatt ?: return
            val ch = g.getService(SERVICE)?.getCharacteristic(WRITE_CHAR) ?: return
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(
                    ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = bytes
                    g.writeCharacteristic(ch)
                }
            }
            if (started) withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() }
            pendingWrite = null
        }
    }

    fun disconnect() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        isConnected = false
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("8327ad99-2d87-4a22-a8ce-6dd7971c0437")
        val WRITE_CHAR: UUID = UUID.fromString("8327ad98-2d87-4a22-a8ce-6dd7971c0437")
        val NOTIFY_CHAR: UUID = UUID.fromString("8327ad97-2d87-4a22-a8ce-6dd7971c0437")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val WRITE_TIMEOUT_MS = 5_000L

        /** Ring advertising names look like "RingConn Gen3-F749". */
        fun looksLikeRing(name: String?): Boolean =
            name != null && (name.startsWith("RingConn") || name.startsWith("Ring "))
    }
}
