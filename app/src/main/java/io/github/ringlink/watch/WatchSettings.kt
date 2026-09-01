package io.github.ringlink.watch

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

/**
 * Settings for a temporary period of heightened watchfulness — after a tick bite, for instance,
 * where the thing being watched for can itself take away the ability to notice it.
 */
class WatchSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ringlink_watch", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_ENABLED, v) }

    /** Epoch millis of the exposure, so the app can show how far into the incubation window it is. */
    var exposureAt: Long
        get() = prefs.getLong(KEY_EXPOSURE, 0)
        set(v) = prefs.edit { putLong(KEY_EXPOSURE, v) }

    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL, 4)
        set(v) = prefs.edit { putInt(KEY_INTERVAL, v.coerceIn(1, 12)) }

    /** How long an unanswered check-in waits before it escalates. */
    var graceMinutes: Int
        get() = prefs.getInt(KEY_GRACE, 15)
        set(v) = prefs.edit { putInt(KEY_GRACE, v.coerceIn(2, 60)) }

    var lastCheckInAt: Long
        get() = prefs.getLong(KEY_LAST_OK, 0)
        set(v) = prefs.edit { putLong(KEY_LAST_OK, v) }

    var pendingSince: Long
        get() = prefs.getLong(KEY_PENDING, 0)
        set(v) = prefs.edit { putLong(KEY_PENDING, v) }

    /** Quiet hours are deliberately absent: the risk this watches for does not keep office hours. */
    val daysSinceExposure: Long?
        get() = if (exposureAt == 0L) null
        else TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - exposureAt)

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_EXPOSURE = "exposure_at"
        const val KEY_INTERVAL = "interval_hours"
        const val KEY_GRACE = "grace_minutes"
        const val KEY_LAST_OK = "last_checkin"
        const val KEY_PENDING = "pending_since"
    }
}
