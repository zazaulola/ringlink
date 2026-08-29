package io.github.ringlink.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.ringlink.data.Settings

/**
 * Reconnects to the ring after a reboot.
 *
 * `connectedDevice` is not on the list of foreground-service types that Android 15 blocks from
 * BOOT_COMPLETED, so starting the service here is allowed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings(context).rings.isEmpty()) return
        RingService.start(context)
    }
}
