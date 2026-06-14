package com.gremier.gkeys.ime.personalization

import org.json.JSONArray
import org.json.JSONObject

data class WeightedTerm(
    val text: String,
    val weight: Double,
    val reason: String = "",
)

data class NextWordBoost(
    val previous: String,
    val next: String,
    val weight: Double,
    val reason: String = "",
)

data class CorrectionPenalty(
    val typed: String,
    val correction: String,
    val weight: Double,
    val reason: String = "",
)

data class PersonalLanguageProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val profileVersion: Int = 0,
    val updatedAtMillis: Long = 0L,
    val customVocabulary: List<WeightedTerm> = emptyList(),
    val neverAutocorrect: List<String> = emptyList(),
    val phraseBoosts: List<WeightedTerm> = emptyList(),
    val nextWordPredictions: List<NextWordBoost> = emptyList(),
    val stylePreferences: List<String> = emptyList(),
    val punctuationHabits: List<WeightedTerm> = emptyList(),
    val correctionPenalties: List<CorrectionPenalty> = emptyList(),
    val recentTopicBoosts: List<WeightedTerm> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("profileVersion", profileVersion)
        put("updatedAtMillis", updatedAtMillis)
        put("customVocabulary", weightedArray(customVocabulary))
        put("neverAutocorrect", JSONArray(neverAutocorrect))
        put("phraseBoosts", weightedArray(phraseBoosts))
        put("nextWordPredictions", JSONArray().also { arr ->
            nextWordPredictions.forEach {
                arr.put(JSONObject().apply {
                    put("previous", it.previous)
                    put("next", it.next)
                    put("weight", it.weight)
                    put("reason", it.reason)
                })
            }
        })
        put("stylePreferences", JSONArray(stylePreferences))
        put("punctuationHabits", weightedArray(punctuationHabits))
        put("correctionPenalties", JSONArray().also { arr ->
            correctionPenalties.forEach {
                arr.put(JSONObject().apply {
                    put("typed", it.typed)
                    put("correction", it.correction)
                    put("weight", it.weight)
                    put("reason", it.reason)
                })
            }
        })
        put("recentTopicBoosts", weightedArray(recentTopicBoosts))
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_TERMS = 200
        const val MAX_NEXT_WORDS = 200
        const val MAX_PENALTIES = 200

        fun empty(now: Long = System.currentTimeMillis()) = PersonalLanguageProfile(updatedAtMillis = now)

        fun fromJson(json: JSONObject): PersonalLanguageProfile? {
            val schema = json.optInt("schemaVersion", SCHEMA_VERSION)
            if (schema != SCHEMA_VERSION) return null
            return PersonalLanguageProfile(
                schemaVersion = schema,
                profileVersion = json.optInt("profileVersion", 0).coerceAtLeast(0),
                updatedAtMillis = json.optLong("updatedAtMillis", 0L).coerceAtLeast(0L),
                customVocabulary = weightedList(json.optJSONArray("customVocabulary"), MAX_TERMS),
                neverAutocorrect = stringList(json.optJSONArray("neverAutocorrect"), MAX_TERMS),
                phraseBoosts = weightedList(json.optJSONArray("phraseBoosts"), MAX_TERMS),
                nextWordPredictions = nextWordList(json.optJSONArray("nextWordPredictions")),
                stylePreferences = stringList(json.optJSONArray("stylePreferences"), 50),
                punctuationHabits = weightedList(json.optJSONArray("punctuationHabits"), 50),
                correctionPenalties = penaltyList(json.optJSONArray("correctionPenalties")),
                recentTopicBoosts = weightedList(json.optJSONArray("recentTopicBoosts"), MAX_TERMS),
            )
        }

        private fun weightedArray(items: List<WeightedTerm>): JSONArray = JSONArray().also { arr ->
            items.forEach {
                arr.put(JSONObject().apply {
                    put("text", it.text)
                    put("weight", it.weight)
                    put("reason", it.reason)
                })
            }
        }

        private fun weightedList(arr: JSONArray?, limit: Int): List<WeightedTerm> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = cleanText(obj.optString("text", ""), maxLen = 80) ?: return@mapNotNull null
                WeightedTerm(
                    text = text,
                    weight = obj.optDouble("weight", 0.0).coerceIn(-1.0, 1.0),
                    reason = cleanText(obj.optString("reason", ""), maxLen = 160).orEmpty(),
                )
            }.take(limit)
        }

        private fun nextWordList(arr: JSONArray?): List<NextWordBoost> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val previous = cleanText(obj.optString("previous", ""), maxLen = 40)?.lowercase()
                    ?: return@mapNotNull null
                val next = cleanText(obj.optString("next", ""), maxLen = 40)?.lowercase()
                    ?: return@mapNotNull null
                NextWordBoost(
                    previous = previous,
                    next = next,
                    weight = obj.optDouble("weight", 0.0).coerceIn(-1.0, 1.0),
                    reason = cleanText(obj.optString("reason", ""), maxLen = 160).orEmpty(),
                )
            }.take(MAX_NEXT_WORDS)
        }

        private fun penaltyList(arr: JSONArray?): List<CorrectionPenalty> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val typed = cleanText(obj.optString("typed", ""), maxLen = 40)?.lowercase()
                    ?: return@mapNotNull null
                val correction = cleanText(obj.optString("correction", ""), maxLen = 40)?.lowercase()
                    ?: return@mapNotNull null
                CorrectionPenalty(
                    typed = typed,
                    correction = correction,
                    weight = obj.optDouble("weight", 0.0).coerceIn(0.0, 1.0),
                    reason = cleanText(obj.optString("reason", ""), maxLen = 160).orEmpty(),
                )
            }.take(MAX_PENALTIES)
        }

        private fun stringList(arr: JSONArray?, limit: Int): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                cleanText(arr.optString(i, ""), maxLen = 100)
            }.take(limit)
        }

        private fun cleanText(value: String, maxLen: Int): String? {
            val cleaned = value.trim().replace(Regex("\\s+"), " ").take(maxLen)
            if (cleaned.isBlank()) return null
            if (cleaned.any { it.code < 32 }) return null
            return cleaned
        }
    }
}
