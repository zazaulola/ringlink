package io.github.ringlink

import android.app.Application
import io.github.ringlink.backup.AutoBackup
import io.github.ringlink.backup.BackupJobs
import io.github.ringlink.ble.SyncWorker
import io.github.ringlink.data.Settings
import io.github.ringlink.data.WearRules
import io.github.ringlink.weather.WeatherReceiver
import io.github.ringlink.weather.WeatherStore

class RingLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
        AutoBackup.schedule(this)
        BackupJobs.onAppStart(this)
        // The setting can come back with a restored backup; the receiver follows it.
        WeatherReceiver.sync(this)
        val settings = Settings(this)
        WeatherStore.enabled = settings.weatherEnabled
        // History is judged by one set of rules, so a change to them reaches all of it.
        if (settings.wearRulesVersion != WearRules.VERSION) {
            settings.invalidateStepQuarters()
            settings.wearRulesVersion = WearRules.VERSION
        }
    }
}
