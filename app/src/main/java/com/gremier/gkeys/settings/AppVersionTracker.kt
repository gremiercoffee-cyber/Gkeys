package com.gremier.gkeys.settings

import android.content.Context
import android.os.Build

/** Tracks app version so the IME can detect APK updates without reinstall. */
object AppVersionTracker {

    private const val PREFS = "gkeys_runtime"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"

    fun currentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    /** @return true if this launch detected a newer APK than last time. */
    fun noteCurrentVersion(context: Context): Boolean {
        return try {
            val current = currentVersionCode(context)
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getInt(KEY_LAST_VERSION_CODE, -1)
            if (previous != current) {
                prefs.edit().putInt(KEY_LAST_VERSION_CODE, current).apply()
            }
            previous != -1 && previous != current
        } catch (_: Exception) {
            false
        }
    }
}
