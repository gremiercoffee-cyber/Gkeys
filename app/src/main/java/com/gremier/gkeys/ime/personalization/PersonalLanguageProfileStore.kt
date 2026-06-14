package com.gremier.gkeys.ime.personalization

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PersonalLanguageProfileStore {
    private const val FILE_NAME = "personal_language_profile.json"
    private const val HISTORY_FILE_NAME = "personal_language_profile_history.json"
    private const val MAX_HISTORY = 10

    fun load(context: Context): PersonalLanguageProfile {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return PersonalLanguageProfile.empty()
        return runCatching {
            PersonalLanguageProfile.fromJson(JSONObject(file.readText()))
        }.getOrNull() ?: PersonalLanguageProfile.empty()
    }

    fun saveVersioned(context: Context, profile: PersonalLanguageProfile) {
        val current = load(context)
        appendHistory(context, current)
        File(context.filesDir, FILE_NAME).writeText(profile.toJson().toString(2))
    }

    fun latestDebugSummary(context: Context): String {
        val profile = load(context)
        return buildString {
            append("Profile v${profile.profileVersion}")
            if (profile.updatedAtMillis > 0) append(" updated ${java.util.Date(profile.updatedAtMillis)}")
            append("\nVocabulary boosts: ${profile.customVocabulary.take(5).joinToString { it.text }}")
            append("\nPhrase boosts: ${profile.phraseBoosts.take(5).joinToString { it.text }}")
            append("\nNever autocorrect: ${profile.neverAutocorrect.take(5).joinToString()}")
        }
    }

    private fun appendHistory(context: Context, profile: PersonalLanguageProfile) {
        val historyFile = File(context.filesDir, HISTORY_FILE_NAME)
        val history = runCatching {
            JSONArray(historyFile.takeIf { it.exists() }?.readText() ?: "[]")
        }.getOrElse { JSONArray() }
        history.put(profile.toJson())
        while (history.length() > MAX_HISTORY) {
            history.remove(0)
        }
        historyFile.writeText(history.toString(2))
    }
}
