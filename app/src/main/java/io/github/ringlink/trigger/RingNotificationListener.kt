package io.github.ringlink.trigger

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.ringlink.ble.RingService
import io.github.ringlink.data.Settings

/**
 * Buzzes the ring when a notification arrives.
 *
 * This is the ordinary, user-granted notification-access API — no root and no Xposed. The system
 * binds it at foreground-service priority and re-binds it after boot, unlock, package changes and
 * process death, so delivery is not subject to Doze or background limits.
 */
class RingNotificationListener : NotificationListenerService() {

    private lateinit var settings: Settings
    private var lastBuzzAt = 0L

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!settings.buzzOnNotifications) return
        if (!shouldBuzz(sbn)) return

        // One buzz per burst: a chat app posting five messages should not rattle the finger.
        val now = SystemClock.elapsedRealtime()
        if (now - lastBuzzAt < COOLDOWN_MS) return
        lastBuzzAt = now

        RingService.start(this, RingService.ACTION_BUZZ)
    }

    private fun shouldBuzz(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false          // never react to ourselves
        if (sbn.packageName in settings.mutedPackages) return false

        val flags = sbn.notification.flags
        // FLAG_LOCAL_ONLY is the platform's explicit "do not bridge this to other devices".
        if (flags and Notification.FLAG_LOCAL_ONLY != 0) return false
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        // The bundle summary duplicates its children.
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return true
    }

    private companion object {
        const val COOLDOWN_MS = 4_000L
    }
}
