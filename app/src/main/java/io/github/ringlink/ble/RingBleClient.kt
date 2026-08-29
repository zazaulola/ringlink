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
import io.github.ringlink.L
import io.github.ringlink.protocol.RingTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    /** Set when the user asked to disconnect, so a drop is not auto-reconnected. */
    @Volatile private var closing = false

    /**
     * True while a patient background connect is pending.
     *
     * The ring advertises only intermittently, so a direct connect that happens to miss its window
     * fails after 30 s. Rather than hammering it, we then arm an opportunistic connect and wait.
     * Callers must not tear that down to start yet another direct attempt.
     */
    @Volatile var backgroundConnectArmed: Boolean = false
        private set

    var onConnectionChange: ((Boolean) -> Unit)? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    backgroundConnectArmed = false
                    onConnectionChange?.invoke(true)
                    if (!connected.isCompleted) connected.complete(true)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    onConnectionChange?.invoke(false)
                    if (!connected.isCompleted) connected.complete(false)
                    if (!servicesReady.isCompleted) servicesReady.complete(false)
                    // Re-arm as a background connect: g.connect() on an existing GATT puts the
                    // address on the controller allow-list, so the link returns by itself when the
                    // ring next advertises. Skip it if we closed the link deliberately.
                    if (!closing) runCatching { g.connect() }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val started = status == BluetoothGatt.GATT_SUCCESS && subscribe(g)
            L.i("services discovered status=$status subscribe-started=$started")
            // Do NOT report ready here: enabling notifications is itself a GATT write, and until
            // the ring acknowledges it no notifications arrive — so an immediate command would sit
            // unanswered and time out. Completion happens in onDescriptorWrite.
            if (!started && !servicesReady.isCompleted) servicesReady.complete(false)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD) return
            val ok = status == BluetoothGatt.GATT_SUCCESS
            L.i("notifications enabled=$ok")
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
        // A patient background connect is already waiting for the ring to advertise. Starting
        // another direct attempt would tear it down — which is how the ring ended up unreachable
        // indefinitely: every retry cancelled the one mechanism that could still succeed.
        if (backgroundConnectArmed) return false
        disconnect()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        val adapter: BluetoothAdapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false

        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()
        closing = false
        // A DIRECT connect (autoConnect = false) for the first attempt: it is fast and bounded.
        // autoConnect = true would be opportunistic — it waits for the ring to advertise, which can
        // take minutes. Background persistence is instead arranged on disconnect, by re-arming the
        // same GATT object, which gives allow-list behaviour without the slow first connect.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        val up = withTimeoutOrNull(timeoutMs) { connected.await() } ?: false
        if (!up) {
            armBackgroundConnect(device)
            return false
        }
        return withTimeoutOrNull(timeoutMs) { servicesReady.await() } ?: false
    }

    /**
     * Ask the controller to connect whenever the ring next shows up.
     *
     * autoConnect puts the address on the allow-list with no timeout: slow to land, but it does not
     * need to coincide with an advertising window the way a direct connect does.
     */
    private fun armBackgroundConnect(device: BluetoothDevice) {
        runCatching { gatt?.close() }
        closing = false
        backgroundConnectArmed = true
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        L.i("direct connect missed the ring's advertising window; waiting in the background")
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
        writeOnce(bytes)
    }

    /**
     * Write once and report whether the ring actually acknowledged it.
     *
     * Android permits a single outstanding GATT operation per connection, so a write issued while
     * another is in flight is refused outright — the return value is the only way to know that
     * happened.
     */
    suspend fun writeOnce(bytes: ByteArray): Boolean = writeLock.withLock {
        val g = gatt ?: return@withLock false
        val ch = g.getService(SERVICE)?.getCharacteristic(WRITE_CHAR) ?: return@withLock false
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
        val ok = started && (withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } ?: false)
        pendingWrite = null
        ok
    }

    /**
     * Keep trying until the ring takes the command.
     *
     * Used for one-shot commands like a buzz, which have no stream to recover them: the sync engine
     * re-reads a page it failed to ack, but a dropped buzz is simply a buzz the user never feels.
     */
    suspend fun writeReliably(bytes: ByteArray, attempts: Int = 8): Boolean {
        repeat(attempts) { attempt ->
            if (writeOnce(bytes)) return true
            delay(RETRY_BASE_MS + attempt * RETRY_STEP_MS)
        }
        return false
    }

    fun disconnect() {
        closing = true
        backgroundConnectArmed = false
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
        private const val RETRY_BASE_MS = 120L
        private const val RETRY_STEP_MS = 60L

        /** Ring advertising names look like "RingConn Gen3-F749". */
        fun looksLikeRing(name: String?): Boolean =
            name != null && (name.startsWith("RingConn") || name.startsWith("Ring "))
    }
}
