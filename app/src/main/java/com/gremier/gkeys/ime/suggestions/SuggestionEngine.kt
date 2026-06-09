package com.gremier.gkeys.ime.suggestions

import android.content.Context

data class SuggestionChip(
    val text: String,
    val isPrimary: Boolean = false,
    val isCorrection: Boolean = false,
)

data class SuggestionStripModel(
    val left: SuggestionChip?,
    val center: SuggestionChip?,
    val right: SuggestionChip?,
)

object SuggestionEngine {

    fun build(
        context: Context,
        prefix: String,
        lastWord: String,
        personalVocab: Map<String, Int>,
    ): SuggestionStripModel {
        WordLexicon.ensureLoaded(context)
        val p = prefix.lowercase()
        return if (p.isEmpty()) {
            buildNextWord(lastWord, personalVocab)
        } else {
            buildCompletions(context, p, lastWord, personalVocab)
        }
    }

    fun autocorrectOnSpace(
        context: Context,
        prefix: String,
        previousWord: String,
        personalVocab: Map<String, Int>,
    ): String? {
        if (prefix.length < 2) return null
        WordLexicon.ensureLoaded(context)
        return rankCorrection(prefix, previousWord, personalVocab)?.word
    }

    private data class Scored(val word: String, val score: Double, val isCorrection: Boolean)

    private fun buildCompletions(
        context: Context,
        prefix: String,
        lastWord: String,
        personalVocab: Map<String, Int>,
    ): SuggestionStripModel {
        val typedIsWord = WordLexicon.isKnown(prefix)
        val correction = if (!typedIsWord) rankCorrection(prefix, lastWord, personalVocab) else null

        val completions = rankCompletions(prefix, lastWord, personalVocab)
            .filter { it.word != prefix }
            .distinctBy { it.word }

        val centerPick = when {
            correction != null && correction.score > (completions.firstOrNull()?.score ?: 0.0) + 5.0 ->
                correction
            completions.isNotEmpty() -> completions.first()
            correction != null -> correction
            prefix.length >= 1 -> Scored(prefix, 0.0, false)
            else -> return SuggestionStripModel(null, null, null)
        }

        val center = SuggestionChip(
            centerPick.word,
            isPrimary = true,
            isCorrection = centerPick.isCorrection && centerPick.word != prefix
        )

        val alts = (completions + listOfNotNull(correction))
            .filter { it.word != centerPick.word }
            .distinctBy { it.word }
            .sortedByDescending { it.score }

        val left = alts.getOrNull(0)?.let { SuggestionChip(it.word) }
        val right = alts.getOrNull(1)?.let { SuggestionChip(it.word) }
        return SuggestionStripModel(left, center, right)
    }

    private fun buildNextWord(lastWord: String, personalVocab: Map<String, Int>): SuggestionStripModel {
        val fromContext = WordBigramModel.nextWordCandidates(lastWord)
        val fromPersonal = personalVocab.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { it.length >= 2 }
        val merged = (fromContext + fromPersonal)
            .distinct()
            .take(20)

        if (merged.isEmpty()) return SuggestionStripModel(null, null, null)

        val ranked = merged.map { word ->
            Scored(
                word = word,
                score = scoreWord(word, lastWord, personalVocab, editDistance = 0),
                isCorrection = false
            )
        }.sortedByDescending { it.score }

        val center = SuggestionChip(ranked.first().word, isPrimary = true)
        val left = ranked.getOrNull(1)?.let { SuggestionChip(it.word) }
        val right = ranked.getOrNull(2)?.let { SuggestionChip(it.word) }
        return SuggestionStripModel(left, center, right)
    }

    private fun rankCorrection(
        typed: String,
        previousWord: String,
        personalVocab: Map<String, Int>,
    ): Scored? {
        val lower = typed.lowercase()
        if (WordLexicon.isKnown(lower)) return null

        return WordLexicon.correctionCandidates(lower)
            .mapNotNull { candidate ->
                val dist = editDistance(lower, candidate)
                val score = scoreWord(candidate, previousWord, personalVocab, dist, lower)
                if (score < MIN_CORRECTION_SCORE) null
                else Scored(candidate, score, isCorrection = true)
            }
            .maxByOrNull { it.score }
    }

    private fun rankCompletions(
        prefix: String,
        lastWord: String,
        personalVocab: Map<String, Int>,
    ): List<Scored> {
        val fromLexicon = WordLexicon.completions(prefix, 24)
        val fromPersonal = personalVocab.entries
            .filter { it.key.startsWith(prefix) && it.key.length > prefix.length }
            .sortedByDescending { it.value }
            .map { it.key }

        return (fromPersonal + fromLexicon)
            .distinct()
            .map { word ->
                Scored(
                    word = word,
                    score = scoreWord(word, lastWord, personalVocab, editDistance = 0),
                    isCorrection = false
                )
            }
            .sortedByDescending { it.score }
            .take(12)
    }

    private fun scoreWord(
        candidate: String,
        previousWord: String,
        personalVocab: Map<String, Int>,
        editDistance: Int,
        typed: String? = null,
    ): Double {
        var score = 0.0
        score += WordLexicon.frequencyScore(candidate) * 1.2
        score += (personalVocab[candidate] ?: 0) * 12.0
        score += WordBigramModel.contextScore(previousWord, candidate) * 90.0
        score -= editDistance * 110.0

        if (typed != null) {
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

    private const val MIN_CORRECTION_SCORE = 25.0

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
