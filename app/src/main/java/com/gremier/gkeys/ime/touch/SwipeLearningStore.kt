package com.gremier.gkeys.ime.touch

import android.content.Context
import org.json.JSONObject

class SwipeLearningStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var root = runCatching {
        JSONObject(prefs.getString(KEY_DATA, "{}").orEmpty().ifBlank { "{}" })
    }.getOrElse { JSONObject() }

    @Synchronized
    fun score(pathKey: String, word: String): Int {
        val stats = statsFor(pathKey, word.lowercase(), create = false) ?: return 0
        val accepted = stats.optInt(FIELD_ACCEPTED, 0)
        val corrected = stats.optInt(FIELD_CORRECTED, 0)
        val rejected = stats.optInt(FIELD_REJECTED, 0)
        return accepted * 120 + corrected * 260 - rejected * 220
    }

    @Synchronized
    fun recordAccepted(pathKey: String, word: String) {
        bump(pathKey, word, FIELD_ACCEPTED, 1)
    }

    @Synchronized
    fun recordRejected(pathKey: String, word: String) {
        bump(pathKey, word, FIELD_REJECTED, 1)
    }

    @Synchronized
    fun recordCorrection(pathKey: String, wrongWord: String, correctWord: String) {
        if (correctWord.length < 2) return
        bump(pathKey, wrongWord, FIELD_REJECTED, 1)
        bump(pathKey, correctWord, FIELD_CORRECTED, 1)
        bump(pathKey, correctWord, FIELD_ACCEPTED, 2)
    }

    private fun bump(pathKey: String, word: String, field: String, amount: Int) {
        if (pathKey.isBlank() || word.isBlank()) return
        val stats = statsFor(pathKey, word.lowercase(), create = true) ?: return
        stats.put(field, stats.optInt(field, 0) + amount)
        save()
    }

    private fun statsFor(pathKey: String, word: String, create: Boolean): JSONObject? {
        val pathStats = root.optJSONObject(pathKey) ?: if (create) {
            JSONObject().also { root.put(pathKey, it) }
        } else {
            return null
        }
        return pathStats.optJSONObject(word) ?: if (create) {
            JSONObject().also { pathStats.put(word, it) }
        } else {
            null
        }
    }

    private fun save() {
        prefs.edit().putString(KEY_DATA, root.toString()).apply()
    }

    private companion object {
        private const val PREFS = "swipe_learning"
        private const val KEY_DATA = "data"
        private const val FIELD_ACCEPTED = "a"
        private const val FIELD_REJECTED = "r"
        private const val FIELD_CORRECTED = "c"
    }
}
