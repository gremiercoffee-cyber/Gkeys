package com.gremier.gkeys.ime.personalization

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TypingEventLogger(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "typing_summaries").apply { mkdirs() }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Synchronized
    fun logAcceptedSuggestion(word: String) = increment("acceptedSuggestions", normalize(word))

    @Synchronized
    fun logRejectedSuggestion(word: String) = increment("rejectedSuggestions", normalize(word))

    @Synchronized
    fun logCompletedWord(word: String, previousWord: String?) {
        val normalized = normalize(word)
        if (normalized.length < 2) return
        increment("customWords", normalized)
        val prev = normalize(previousWord.orEmpty())
        if (prev.length >= 2) {
            increment("nextWord:${prev}", normalized)
            increment("phrases", "$prev $normalized")
        }
        if (looksLikeProjectTerm(word)) increment("projectTerms", word.trim())
    }

    @Synchronized
    fun logAutocorrectUndo(original: String, corrected: String) {
        val key = "${normalize(original)}\u0001${normalize(corrected)}"
        increment("undoAutocorrect", key)
    }

    @Synchronized
    fun logRawTextSample(text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(160)
        if (clean.length < 4) return
        val json = readToday()
        val arr = json.optJSONArray("rawTextSamples") ?: JSONArray().also { json.put("rawTextSamples", it) }
        if (arr.length() < 20) arr.put(clean)
        writeToday(json)
    }

    fun buildSummary(allowRawSamples: Boolean): DailyTypingSummary {
        val json = readToday()
        val dayKey = today()
        return DailyTypingSummary(
            dayKey = dayKey,
            acceptedSuggestions = weightedList(json.optJSONObject("acceptedSuggestions")),
            rejectedSuggestions = weightedList(json.optJSONObject("rejectedSuggestions")),
            undoAutocorrectEvents = penalties(json.optJSONObject("undoAutocorrect")),
            customWords = weightedList(json.optJSONObject("customWords")),
            repeatedPhrases = weightedList(json.optJSONObject("phrases")),
            commonNextWordPatterns = nextWordPatterns(json),
            recentProjectTerms = weightedList(json.optJSONObject("projectTerms")),
            rawTextSamples = if (allowRawSamples) stringArray(json.optJSONArray("rawTextSamples")) else emptyList(),
        )
    }

    private fun increment(bucket: String, key: String) {
        if (key.isBlank()) return
        val json = readToday()
        val obj = json.optJSONObject(bucket) ?: JSONObject().also { json.put(bucket, it) }
        obj.put(key, obj.optInt(key, 0) + 1)
        writeToday(json)
    }

    private fun weightedList(obj: JSONObject?, limit: Int = 40): List<WeightedTerm> {
        if (obj == null) return emptyList()
        return obj.keys().asSequence()
            .map { it to obj.optInt(it, 0) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (text, count) -> WeightedTerm(text, (count / 10.0).coerceIn(0.1, 1.0), "seen $count times today") }
            .toList()
    }

    private fun penalties(obj: JSONObject?): List<CorrectionPenalty> {
        if (obj == null) return emptyList()
        return obj.keys().asSequence()
            .map { it to obj.optInt(it, 0) }
            .sortedByDescending { it.second }
            .take(40)
            .mapNotNull { (key, count) ->
                val parts = key.split('\u0001')
                if (parts.size != 2) null else CorrectionPenalty(
                    typed = parts[0],
                    correction = parts[1],
                    weight = (count / 5.0).coerceIn(0.2, 1.0),
                    reason = "user undid this autocorrect $count times",
                )
            }.toList()
    }

    private fun nextWordPatterns(json: JSONObject): List<NextWordBoost> {
        val result = mutableListOf<NextWordBoost>()
        json.keys().forEach { bucket ->
            if (!bucket.startsWith("nextWord:")) return@forEach
            val previous = bucket.removePrefix("nextWord:")
            val obj = json.optJSONObject(bucket) ?: return@forEach
            obj.keys().asSequence()
                .map { it to obj.optInt(it, 0) }
                .sortedByDescending { it.second }
                .take(6)
                .forEach { (next, count) ->
                    result += NextWordBoost(
                        previous = previous,
                        next = next,
                        weight = (count / 8.0).coerceIn(0.1, 1.0),
                        reason = "user often writes '$previous $next'",
                    )
                }
        }
        return result.sortedByDescending { it.weight }.take(80)
    }

    private fun stringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun readToday(): JSONObject {
        val file = File(dir, "${today()}.json")
        return runCatching { JSONObject(file.takeIf { it.exists() }?.readText() ?: "{}") }.getOrDefault(JSONObject())
    }

    private fun writeToday(json: JSONObject) {
        File(dir, "${today()}.json").writeText(json.toString())
    }

    private fun today(): String = dayFormat.format(Date())

    private fun normalize(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ").take(80)

    private fun looksLikeProjectTerm(word: String): Boolean =
        word.length >= 3 && word.any { it.isUpperCase() } && word.any { it.isLowerCase() }
}
