package com.gremier.gkeys.ime.personalization

import org.json.JSONArray
import org.json.JSONObject

data class DailyTypingSummary(
    val dayKey: String,
    val acceptedSuggestions: List<WeightedTerm>,
    val rejectedSuggestions: List<WeightedTerm>,
    val undoAutocorrectEvents: List<CorrectionPenalty>,
    val customWords: List<WeightedTerm>,
    val repeatedPhrases: List<WeightedTerm>,
    val commonNextWordPatterns: List<NextWordBoost>,
    val recentProjectTerms: List<WeightedTerm>,
    val rawTextSamples: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dayKey", dayKey)
        put("acceptedSuggestions", weightedArray(acceptedSuggestions))
        put("rejectedSuggestions", weightedArray(rejectedSuggestions))
        put("undoAutocorrectEvents", JSONArray().also { arr ->
            undoAutocorrectEvents.forEach {
                arr.put(JSONObject().apply {
                    put("typed", it.typed)
                    put("correction", it.correction)
                    put("weight", it.weight)
                    put("reason", it.reason)
                })
            }
        })
        put("customWords", weightedArray(customWords))
        put("repeatedPhrases", weightedArray(repeatedPhrases))
        put("commonNextWordPatterns", JSONArray().also { arr ->
            commonNextWordPatterns.forEach {
                arr.put(JSONObject().apply {
                    put("previous", it.previous)
                    put("next", it.next)
                    put("weight", it.weight)
                    put("reason", it.reason)
                })
            }
        })
        put("recentProjectTerms", weightedArray(recentProjectTerms))
        put("rawTextSamples", JSONArray(rawTextSamples))
    }

    companion object {
        private fun weightedArray(items: List<WeightedTerm>): JSONArray = JSONArray().also { arr ->
            items.forEach {
                arr.put(JSONObject().apply {
                    put("text", it.text)
                    put("weight", it.weight)
                    put("reason", it.reason)
                })
            }
        }
    }
}
