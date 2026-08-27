package io.github.ringlink.data

import android.content.Context
import androidx.core.content.edit

/** Plain app settings. Nothing here is secret; the ring address is a local BLE MAC. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("ringlink", Context.MODE_PRIVATE)

    var ringAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(v) = prefs.edit { putString(KEY_ADDRESS, v) }

    var ringName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(v) = prefs.edit { putString(KEY_NAME, v) }

    var epochAnchor: Long
        get() = prefs.getLong(KEY_EPOCH, io.github.ringlink.protocol.RingClock.DEFAULT_EPOCH)
        set(v) = prefs.edit { putLong(KEY_EPOCH, v) }

    var epochCalibrated: Boolean
        get() = prefs.getBoolean(KEY_EPOCH_DONE, false)
        set(v) = prefs.edit { putBoolean(KEY_EPOCH_DONE, v) }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0)
        set(v) = prefs.edit { putLong(KEY_LAST_SYNC, v) }

    var buzzOnNotifications: Boolean
        get() = prefs.getBoolean(KEY_BUZZ_NOTIF, true)
        set(v) = prefs.edit { putBoolean(KEY_BUZZ_NOTIF, v) }

    var buzzOnCalls: Boolean
        get() = prefs.getBoolean(KEY_BUZZ_CALLS, true)
        set(v) = prefs.edit { putBoolean(KEY_BUZZ_CALLS, v) }

    var exportToHealthConnect: Boolean
        get() = prefs.getBoolean(KEY_EXPORT_HC, true)
        set(v) = prefs.edit { putBoolean(KEY_EXPORT_HC, v) }

    /** Package names that should never buzz the ring. */
    var mutedPackages: Set<String>
        get() = prefs.getStringSet(KEY_MUTED, emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet(KEY_MUTED, v) }

    /**
     * Auto-sync is suppressed inside this window. Acknowledging pages destroys them on the ring, and
     * draining mid-night shreds the backlog the ring is still building; one drain afterwards is both
     * safer and more complete.
     */
    var quietFromHour: Int
        get() = prefs.getInt(KEY_QUIET_FROM, 23)
        set(v) = prefs.edit { putInt(KEY_QUIET_FROM, v) }

    var quietToHour: Int
        get() = prefs.getInt(KEY_QUIET_TO, 9)
        set(v) = prefs.edit { putInt(KEY_QUIET_TO, v) }

    fun isQuietHour(hour: Int): Boolean {
        val from = quietFromHour
        val to = quietToHour
        return if (from <= to) hour in from until to else hour >= from || hour < to
    }

    private companion object {
        const val KEY_ADDRESS = "ring_address"
        const val KEY_NAME = "ring_name"
        const val KEY_EPOCH = "epoch_anchor"
        const val KEY_EPOCH_DONE = "epoch_calibrated"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_BUZZ_NOTIF = "buzz_notifications"
        const val KEY_BUZZ_CALLS = "buzz_calls"
        const val KEY_EXPORT_HC = "export_health_connect"
        const val KEY_MUTED = "muted_packages"
        const val KEY_QUIET_FROM = "quiet_from"
        const val KEY_QUIET_TO = "quiet_to"
    }
}
