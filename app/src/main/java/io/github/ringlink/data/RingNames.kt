package io.github.ringlink.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Names for rings, including ones no longer configured.
 *
 * History outlives the ring list: a ring removed with Forget keeps all of its readings, and those
 * still need to say which ring they came from. So a name is looked up in the configured rings
 * first, then in the system's Bluetooth bonds — which survive Forget — and only then made up from
 * the address.
 */
class RingNames(private val context: Context, private val settings: Settings) {

    private val cache = ConcurrentHashMap<String, Ring>()

    fun ring(address: String): Ring = cache.getOrPut(address) {
        settings.rings.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: bondedName(address)?.let { Ring(address, it) }
            ?: Ring(address, "Ring ${address.replace(":", "").takeLast(4)}")
    }

    @SuppressLint("MissingPermission")
    private fun bondedName(address: String): String? = runCatching {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter.getRemoteDevice(address.uppercase()).name?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
