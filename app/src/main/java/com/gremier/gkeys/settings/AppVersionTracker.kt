package com.gremier.gkeys.settings

import android.content.Context

/** Tracks app version so the IME can detect APK updates without reinstall. */
object AppVersionTracker {

    private const val PREFS = "gkeys_runtime"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"

    fun currentVersionCode(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    /** @return true if this launch detected a newer APK than last time. */
    fun noteCurrentVersion(context: Context): Boolean {
        val current = currentVersionCode(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getInt(KEY_LAST_VERSION_CODE, -1)
        if (previous != current) {
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, current).apply()
        }
        return previous != -1 && previous != current
    }
}
