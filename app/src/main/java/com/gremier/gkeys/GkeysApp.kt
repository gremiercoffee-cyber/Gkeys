package com.gremier.gkeys

import android.app.Application
import com.gremier.gkeys.diag.CrashLogger

class GkeysApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
