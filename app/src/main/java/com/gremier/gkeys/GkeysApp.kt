package com.gremier.gkeys

import android.app.Application
import com.gremier.gkeys.diag.CrashLogger
import com.gremier.gkeys.ime.personalization.DailyProfileRefinementWorker

class GkeysApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        DailyProfileRefinementWorker.schedule(this)
    }
}
