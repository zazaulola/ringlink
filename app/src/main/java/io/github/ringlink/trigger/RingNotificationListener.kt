package io.github.ringlink.trigger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.ringlink.L
import io.github.ringlink.ble.RingService
import io.github.ringlink.data.Settings

/**
 * Buzzes the ring when a notification arrives.
 *
 * This is the ordinary, user-granted notification-access API — no root and no Xposed. The system
 * binds it at foreground-service priority and re-binds after boot, unlock and package changes, so
 * delivery is not subject to Doze or background limits.
 *
 * Filtering here is deliberately permissive. Suppressing a notification the user wanted to feel is
 * invisible — it looks like the ring is broken — whereas one buzz too many is merely mild. So the
 * rules below drop only things that are definitely not alerts.
 */
class RingNotificationListener : NotificationListenerService() {

    private lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onListenerConnected() {
        L.i("notification listener connected")
    }

    override fun onListenerDisconnected() {
        // Worth shouting about: while unbound, no notification will ever buzz the ring.
        L.w("notification listener disconnected — asking the system to rebind")
        runCatching { requestRebind(RingService.listenerComponent(this)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!settings.buzzOnNotifications) return
        if (!shouldBuzz(sbn)) return

        // The de-duplication key: a chat app's group, else the package. Cooldown is applied in the
        // service, which is the only place that learns whether the ring actually took the buzz —
        // debouncing here would let a buzz that never arrived silence the next real one.
        val key = sbn.notification.group ?: sbn.packageName
        runCatching { RingService.buzzFor(this, key) }
            .onFailure { L.e("could not request buzz", it) }
    }

    private fun shouldBuzz(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.packageName in settings.mutedPackages) return false

        val n = sbn.notification

        // Calls and alarms first: they are the most important things to feel, and they are exactly
        // the ones posted as ongoing foreground-service notifications, so the flag rules below
        // would otherwise throw them away. WhatsApp/Telegram/Signal calls all land here.
        if (n.category == Notification.CATEGORY_CALL ||
            n.category == Notification.CATEGORY_ALARM ||
            n.fullScreenIntent != null
        ) return true

        // "Ongoing" means undismissable, not uninteresting — but these categories really are
        // background noise: media transport controls, sync progress, VPN and battery status.
        when (n.category) {
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS,
            -> return false
        }

        // A bundle summary duplicates its children, which arrive as their own notifications.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false

        // Deliberately NOT filtered: FLAG_LOCAL_ONLY. It asks companion *screens* not to mirror the
        // notification's content — every app with a Wear OS counterpart sets it. Treating it as
        // "do not alert" silently muted whole apps, which is precisely the reported symptom.
        return true
    }
}
