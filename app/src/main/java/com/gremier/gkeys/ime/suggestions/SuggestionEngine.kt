package com.gremier.gkeys.ime.suggestions

import android.content.Context

data class SuggestionChip(
    val text: String,
    val isPrimary: Boolean = false,
    val isCorrection: Boolean = false,
    val isLiteralTyped: Boolean = false,
)

data class SuggestionStripModel(
    val left: SuggestionChip?,
    val center: SuggestionChip?,
)

object SuggestionEngine {

    fun build(
        context: Context,
        language: DictionaryManager.Language,
        prefix: String,
        userWords: Map<String, Int>,
    ): SuggestionStripModel {
        DictionaryManager.ensureLoaded(context, language)
        val normalizedPrefix = normalize(prefix, language)
        if (normalizedPrefix.isEmpty()) {
            return SuggestionStripModel(null, null)
        }

        val typedIsWord = DictionaryManager.isKnown(language, normalizedPrefix) ||
            userWords.containsKey(normalizedPrefix)
        val correction = if (!typedIsWord) {
            rankCorrection(language, normalizedPrefix, userWords)
        } else {
            null
        }

        val completions = rankCompletions(language, normalizedPrefix, userWords)
            .filter { it.word != normalizedPrefix }
            .distinctBy { it.word }

        if (correction != null && correction.word != normalizedPrefix) {
            val left = SuggestionChip(normalizedPrefix, isLiteralTyped = true)
            val center = SuggestionChip(
                correction.word,
                isPrimary = true,
                isCorrection = true,
            )
            return SuggestionStripModel(left, center)
        }

        val centerWord = when {
            completions.isNotEmpty() -> completions.first().word
            else -> normalizedPrefix
        }
        val center = SuggestionChip(centerWord, isPrimary = true)
        val left = completions.getOrNull(1)?.let { SuggestionChip(it.word) }
        return SuggestionStripModel(left, center)
    }

    fun buildPostAutocorrectUndo(original: String, corrected: String): SuggestionStripModel {
        return SuggestionStripModel(
            left = SuggestionChip(original, isLiteralTyped = true),
            center = SuggestionChip(corrected, isPrimary = true),
        )
    }

    fun autocorrectOnSpace(
        context: Context,
        language: DictionaryManager.Language,
        prefix: String,
        userWords: Map<String, Int>,
    ): String? {
        if (prefix.length < 2) return null
        DictionaryManager.ensureLoaded(context, language)
        val normalized = normalize(prefix, language)
        if (DictionaryManager.isKnown(language, normalized) || userWords.containsKey(normalized)) {
            return null
        }
        val correction = rankCorrection(language, normalized, userWords) ?: return null
        return if (shouldAutocorrect(normalized, correction)) correction.word else null
    }

    private data class Scored(val word: String, val score: Double, val isCorrection: Boolean)

    private fun rankCorrection(
        language: DictionaryManager.Language,
        typed: String,
        userWords: Map<String, Int>,
    ): Scored? {
        return DictionaryManager.correctionCandidates(language, typed)
            .mapNotNull { candidate ->
                val dist = editDistance(typed, candidate)
                val score = scoreWord(language, candidate, userWords, dist, typed)
                if (score < MIN_CORRECTION_SCORE) null
                else Scored(candidate, score, isCorrection = true)
            }
            .maxByOrNull { it.score }
    }

    private fun shouldAutocorrect(typed: String, correction: Scored): Boolean {
        val dist = editDistance(typed, correction.word)
        if (dist == 0) return false
        if (dist == 1 && correction.score >= MIN_AUTOCORRECT_SCORE) return true
        if (dist == 2 && typed.length >= 5 && correction.score >= 48.0) return true
        return correction.score >= HIGH_CONFIDENCE_AUTOCORRECT_SCORE
    }

    private fun rankCompletions(
        language: DictionaryManager.Language,
        prefix: String,
        userWords: Map<String, Int>,
    ): List<Scored> {
        val fromUser = userWords.entries
            .filter { it.key.startsWith(prefix) && it.key.length > prefix.length }
            .sortedByDescending { it.value }
            .map { it.key }

        val fromDict = DictionaryManager.prefixMatches(language, prefix, 24)

        return (fromUser + fromDict)
            .distinct()
            .map { word ->
                Scored(
                    word = word,
                    score = scoreWord(language, word, userWords, editDistance = 0),
                    isCorrection = false,
                )
            }
            .sortedByDescending { it.score }
            .take(12)
    }

    private fun scoreWord(
        language: DictionaryManager.Language,
        candidate: String,
        userWords: Map<String, Int>,
        editDistance: Int,
        typed: String? = null,
    ): Double {
        var score = 0.0
        score += DictionaryManager.frequencyScore(language, candidate) * 1.2
        score += (userWords[candidate] ?: 0) * 120.0
        score -= editDistance * 110.0

        if (typed != null && language == DictionaryManager.Language.EN) {
            score += KeyboardProximity.wordProximityScore(typed, candidate) * 35.0
            if (candidate.length >= 2 && typed.length >= 2 && candidate[0] == typed[0]) {
                score += 12.0
            }
            if (candidate.startsWith(typed.take(minOf(typed.length, 3)))) {
                score += 8.0
            }
        }

        return score
    }

    private fun normalize(word: String, language: DictionaryManager.Language): String = when (language) {
        DictionaryManager.Language.EN -> word.lowercase()
        DictionaryManager.Language.HE -> word
    }

    private const val MIN_CORRECTION_SCORE = 25.0
    private const val MIN_AUTOCORRECT_SCORE = 32.0
    private const val HIGH_CONFIDENCE_AUTOCORRECT_SCORE = 55.0

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }
}
