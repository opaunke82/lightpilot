package io.github.opaunke82.lightpilot

import android.app.Application
import io.github.opaunke82.lightpilot.log.EventLog
import timber.log.Timber

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Timber.plant(EventLog.Tree())
        Timber.i("LightPilot ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) started")
    }
}
