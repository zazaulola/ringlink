package io.github.ringlink.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import io.github.ringlink.L
import io.github.ringlink.ui.MainActivity

/**
 * Raises an alarm that a silenced phone still delivers.
 *
 * Ordinary notifications are the wrong tool here: they respect the ringer, and the situation this
 * exists for is one where the person may not be in a state to notice a quiet phone — or to judge
 * that anything is wrong at all.
 *
 * Three independent channels, because any one of them can fail:
 *  - **Alarm audio.** Routed with `USAGE_ALARM`, which is carried on the alarm stream rather than
 *    the ringer, so it sounds through silent and vibrate-only exactly as a wake-up alarm does.
 *  - **Vibration**, likewise tagged as an alarm so the ringer mode does not suppress it.
 *  - **A full-screen notification** in the alarm category, which surfaces over the lock screen.
 *
 * The ring is buzzed separately by the caller — it is on the finger, which beats a phone in another
 * room.
 */
class AlarmNotifier(private val context: Context) {

    fun raise(title: String, body: String) {
        ensureChannel()
        notifyFullScreen(title, body)
        playAlarmTone()
        vibrateAsAlarm()
        L.i("alarm raised: $title")
    }

    fun cancel() {
        manager().cancel(ALARM_ID)
    }

    private fun manager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Health alarm",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts that must reach you even when the phone is silenced"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                attributes,
            )
            enableVibration(true)
            // Only takes effect if the user has granted Do Not Disturb access; harmless otherwise.
            setBypassDnd(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        manager().createNotificationChannel(channel)
    }

    private fun notifyFullScreen(title: String, body: String) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(open)
            // Surfaces over the lock screen rather than waiting to be noticed in the shade.
            .setFullScreenIntent(open, true)
            .build()
        manager().notify(ALARM_ID, notification)
    }

    /** Played explicitly as well as via the channel: channel sound is fixed at creation time. */
    private fun playAlarmTone() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
        }.onFailure { L.e("could not play alarm tone", it) }
    }

    private fun vibrateAsAlarm() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 600, 300, 600, 300, 600)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
        }.onFailure { L.e("could not vibrate", it) }
    }

    companion object {
        const val CHANNEL_ID = "health_alarm"
        const val ALARM_ID = 2
    }
}
