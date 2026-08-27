package io.github.ringlink

import android.app.Application
import io.github.ringlink.ble.SyncWorker

class RingLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
    }
}
