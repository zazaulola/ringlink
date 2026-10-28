package io.github.ringlink.weather

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.ringlink.L
import io.github.ringlink.data.RingDatabase
import io.github.ringlink.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Takes the weather a weather app shares, so RingLink itself never goes online.
 *
 * Breezy Weather (Settings → External modules → Data sharing → Send Gadgetbridge data) sends the
 * broadcast Gadgetbridge defined to the apps the user ticks, and this receiver is what puts RingLink
 * on that list; QuickWeather sends it to every app that can take it. The receiver is disabled
 * until weather is switched on in RingLink, so until then the app does not even appear there.
 */
class WeatherReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val settings = Settings(context)
        if (!settings.weatherEnabled) return
        val now = System.currentTimeMillis()
        val report = WeatherParser.parse(
            json = runCatching { intent.getStringExtra(EXTRA_JSON) }.getOrNull(),
            gz = runCatching { intent.getByteArrayExtra(EXTRA_GZ) }.getOrNull(),
            now = now,
        )
        if (report == null) {
            L.w("weather: report ignored, nothing usable in it")
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                WeatherStore(RingDatabase.get(context).dao()).save(report, now)
                settings.weatherLocation = report.location
                settings.weatherReceivedAt = now
                L.i("weather: ${report.hours.size} hours for ${report.location ?: "unnamed place"}")
            } catch (e: Exception) {
                L.e("weather: could not store the report", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER"
        const val EXTRA_JSON = "WeatherJson"
        const val EXTRA_GZ = "WeatherGz"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Enable the receiver exactly when weather is on, so weather apps list RingLink only then. */
        fun sync(context: Context) {
            val on = Settings(context).weatherEnabled
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, WeatherReceiver::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
