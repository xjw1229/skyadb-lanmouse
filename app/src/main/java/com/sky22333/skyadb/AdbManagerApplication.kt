package com.sky22333.skyadb

import android.app.Application
import com.sky22333.skyadb.adb.AdbIdentityManager
import timber.log.Timber

class AdbManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        AdbIdentityManager.initialize(this)
        AppServices.initialize(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
