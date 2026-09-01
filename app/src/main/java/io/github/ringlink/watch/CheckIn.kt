package io.github.ringlink.watch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.ringlink.L
import io.github.ringlink.ble.RingService
import java.util.concurrent.TimeUnit

/**
 * A periodic "are you alright?" whose *unanswered* state is the signal.
 *
 * Sensor thresholds are the obvious design and the wrong one here: the ring cannot reliably see a
 * fever, and the illness being watched for can cloud judgement — so a person may be unwell precisely
 * when they are least able to notice it or act on a reading. Silence, by contrast, is unambiguous
 * and needs no calibration.
 *
 * This is a prompt, not a guarantee. It cannot summon help by itself, and a phone left in another
 * room defeats it — which is why the ring is buzzed too.
 */
object CheckIn {

    fun schedule(context: Context) {
        val settings = WatchSettings(context)
        if (!settings.enabled) return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(settings.intervalHours.toLong())
        val intent = pending(context, ACTION_PROMPT)
        runCatching {
            // Exact and allowed to fire in Doze: a check-in that slides by hours is not a check-in.
            if (Build.VERSION.SDK_INT >= 23) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }.onFailure { L.e("could not schedule check-in", it) }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            manager.cancel(pending(context, ACTION_PROMPT))
            manager.cancel(pending(context, ACTION_ESCALATE))
        }
        AlarmNotifier(context).cancel()
    }

    /** Records that the user answered, clears any alarm and queues the next prompt. */
    fun acknowledge(context: Context) {
        val settings = WatchSettings(context)
        settings.lastCheckInAt = System.currentTimeMillis()
        settings.pendingSince = 0
        AlarmNotifier(context).cancel()
        cancelEscalation(context)
        schedule(context)
        L.i("check-in acknowledged")
    }

    internal fun prompt(context: Context) {
        val settings = WatchSettings(context)
        if (!settings.enabled) return
        settings.pendingSince = System.currentTimeMillis()

        val days = settings.daysSinceExposure
        AlarmNotifier(context).raise(
            title = "Check in",
            body = buildString {
                append("Open RingLink and confirm you are alright.")
                if (days != null) append(" Day $days since the bite.")
            },
        )
        // The ring is on a finger; a phone may be in another room.
        RingService.start(context, RingService.ACTION_BUZZ)

        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(settings.graceMinutes.toLong())
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, ACTION_ESCALATE))
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, at, pending(context, ACTION_ESCALATE))
            }
        }
    }

    internal fun escalate(context: Context) {
        val settings = WatchSettings(context)
        if (!settings.enabled || settings.pendingSince == 0L) return
        val waited = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - settings.pendingSince)
        AlarmNotifier(context).raise(
            title = "No answer for $waited minutes",
            body = "The last check-in went unanswered. If you are reading this and feel unwell — " +
                "confused, feverish, a stiff neck — call for help now.",
        )
        RingService.start(context, RingService.ACTION_BUZZ)
        L.w("check-in unanswered for $waited min")
        // Keep asking rather than giving up quietly.
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(settings.graceMinutes.toLong())
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, ACTION_ESCALATE))
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, at, pending(context, ACTION_ESCALATE))
            }
        }
    }

    private fun cancelEscalation(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { manager.cancel(pending(context, ACTION_ESCALATE)) }
    }

    private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, CheckInReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    const val ACTION_PROMPT = "io.github.ringlink.CHECK_IN"
    const val ACTION_ESCALATE = "io.github.ringlink.CHECK_IN_ESCALATE"
}

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CheckIn.ACTION_PROMPT -> CheckIn.prompt(context)
            CheckIn.ACTION_ESCALATE -> CheckIn.escalate(context)
        }
    }
}
