package com.gremier.gkeys.ime.touch

import android.content.Context

object SwipeDebugStore {
    private const val PREFS = "swipe_debug"
    private const val KEY_LAST_LIVE = "last_live"
    private const val KEY_LAST_TEST = "last_test"

    fun saveLastLive(context: Context, diagnostics: SwipeDiagnostics) {
        prefs(context).edit().putString(KEY_LAST_LIVE, diagnostics.summary()).apply()
    }

    fun loadLastLive(context: Context): String =
        prefs(context).getString(KEY_LAST_LIVE, "No live swipe diagnostics yet").orEmpty()

    fun saveLastTestReport(context: Context, report: String) {
        prefs(context).edit().putString(KEY_LAST_TEST, report).apply()
    }

    fun loadLastTestReport(context: Context): String =
        prefs(context).getString(KEY_LAST_TEST, "No swipe test run yet").orEmpty()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
