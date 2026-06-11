package com.gremier.gkeys.ime.suggestions

import android.content.Context
import kotlin.math.abs

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
        if (normalizedPrefix.isEmpty()) return SuggestionStripModel(null, null)

        val en = language == DictionaryManager.Language.EN

        // Standalone "i" -> "I" (it's a valid word, so this runs before the word check).
        if (en && normalizedPrefix == "i") return correctionModel("i", "I")

        val typedIsWord = isRealWord(language, normalizedPrefix, userWords)
        if (!typedIsWord) {
            if (en) {
                contractionFor(normalizedPrefix)?.let { return correctionModel(normalizedPrefix, it) }
                repeatedCharFix(language, normalizedPrefix, userWords)?.let {
                    return correctionModel(normalizedPrefix, it)
                }
            }
            val correction = rankCorrection(language, normalizedPrefix, userWords)
            if (correction != null && correction.word != normalizedPrefix) {
                return correctionModel(normalizedPrefix, correction.word)
            }
            // Compound split is suggestion-only (not auto-applied on space) to stay safe.
            if (en) {
                compoundSplit(normalizedPrefix)?.let { return correctionModel(normalizedPrefix, it) }
            }
        }

        val completions = rankCompletions(language, normalizedPrefix, userWords)
            .filter { it.word != normalizedPrefix }
            .distinctBy { it.word }

        val centerWord = completions.getOrNull(0)?.word ?: normalizedPrefix
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
        DictionaryManager.ensureLoaded(context, language)
        val normalized = normalize(prefix, language)
        val en = language == DictionaryManager.Language.EN

        if (en && normalized == "i") return "I"
        if (prefix.length < 2) return null
        if (isRealWord(language, normalized, userWords)) return null

        if (en) {
            contractionFor(normalized)?.let { return it }
            repeatedCharFix(language, normalized, userWords)?.let { return it }
        }
        val correction = rankCorrection(language, normalized, userWords)
        if (correction != null && correction.word != normalized && shouldAutocorrect(normalized, correction)) {
            return correction.word
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Special corrections (contractions, repeats, "i", compound split)
    // ---------------------------------------------------------------------

    private fun correctionModel(typed: String, fix: String) = SuggestionStripModel(
        left = SuggestionChip(typed, isLiteralTyped = true),
        center = SuggestionChip(fix, isPrimary = true, isCorrection = true),
    )

    /** Curated, unambiguous contractions — every source here is NOT a normal English word. */
    private val CONTRACTIONS: Map<String, String> = mapOf(
        "im" to "I'm", "ive" to "I've", "ill" to "I'll", "id" to "I'd",
        "dont" to "don't", "cant" to "can't", "wont" to "won't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "doesnt" to "doesn't", "didnt" to "didn't",
        "wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
        "youre" to "you're", "youve" to "you've", "youll" to "you'll", "youd" to "you'd",
        "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll", "theyd" to "they'd",
        "were" to "we're", "weve" to "we've", "wed" to "we'd",
        "hes" to "he's", "shes" to "she's", "its" to "it's",
        "thats" to "that's", "whats" to "what's", "whos" to "who's", "wheres" to "where's",
        "theres" to "there's", "heres" to "here's", "lets" to "let's",
        "yall" to "y'all", "oclock" to "o'clock",
    )

    private fun contractionFor(word: String): String? = CONTRACTIONS[word]

    /** "sooo" -> "so", "heyyy" -> "hey": collapse 3+ repeats, then check the dictionary. */
    private fun repeatedCharFix(
        language: DictionaryManager.Language,
        word: String,
        userWords: Map<String, Int>,
    ): String? {
        if (!hasLongRun(word)) return null
        val twoMax = collapseRuns(word, 2)
        if (twoMax != word && isRealWord(language, twoMax, userWords)) return twoMax
        val oneMax = collapseRuns(word, 1)
        if (oneMax != word && isRealWord(language, oneMax, userWords)) return oneMax
        return null
    }

    /** "thisis" -> "this is": both halves must be known words. */
    private fun compoundSplit(word: String): String? {
        if (word.length < 4) return null
        var best: Pair<String, String>? = null
        var bestScore = -1.0
        for (i in 2..word.length - 2) {
            val a = word.substring(0, i)
            val b = word.substring(i)
            if (DictionaryManager.isKnown(DictionaryManager.Language.EN, a) &&
                DictionaryManager.isKnown(DictionaryManager.Language.EN, b)
            ) {
                val s = DictionaryManager.frequencyScore(DictionaryManager.Language.EN, a).toDouble() +
                    DictionaryManager.frequencyScore(DictionaryManager.Language.EN, b).toDouble()
                if (s > bestScore) {
                    bestScore = s
                    best = a to b
                }
            }
        }
        return best?.let { "${it.first} ${it.second}" }
    }

    private fun hasLongRun(s: String): Boolean {
        var run = 1
        for (i in 1 until s.length) {
            if (s[i] == s[i - 1]) {
                run++
                if (run >= 3) return true
            } else {
                run = 1
            }
        }
        return false
    }

    private fun collapseRuns(s: String, maxRun: Int): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder()
        var runChar = s[0]
        var runLen = 0
        for (c in s) {
            if (c == runChar) {
                runLen++
                if (runLen <= maxRun) sb.append(c)
            } else {
                runChar = c
                runLen = 1
                sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // Ranking
    // ---------------------------------------------------------------------

    private data class Scored(val word: String, val score: Double)

    private fun isRealWord(
        language: DictionaryManager.Language,
        word: String,
        userWords: Map<String, Int>,
    ): Boolean =
        DictionaryManager.isKnown(language, word) || userWords.containsKey(word)


    // ---------------------------------------------------------------------
    // Gboard-style keyboard slip candidate generation
    // ---------------------------------------------------------------------

    private fun keyboardSlipCandidates(
        language: DictionaryManager.Language,
        typed: String,
    ): Sequence<String> {
        if (language != DictionaryManager.Language.EN || typed.length < 2) {
            return emptySequence()
        }

        val results = HashSet<String>()

        fun addVariant(chars: CharArray) {
            val word = String(chars)
            if (word != typed && DictionaryManager.isKnown(language, word)) {
                results.add(word)
            }
        }

        val chars = typed.toCharArray()

        // Replace each typed key with nearby keys.
        for (i in chars.indices) {
            val original = chars[i]
            for (neighbor in KeyboardProximity.neighborKeys(original)) {
                val copy = chars.copyOf()
                copy[i] = neighbor
                addVariant(copy)
            }
        }

        // Also allow one missing / extra nearby-key mistake.
        for (i in 0..chars.size) {
            for (key in "abcdefghijklmnopqrstuvwxyz") {
                val copy = chars.toMutableList()
                copy.add(i, key)
                addVariant(copy.toCharArray())
            }
        }

        return results.asSequence()
    }

    private fun keyboardMistakeScore(
        typed: String,
        candidate: String
    ): Double {
        var score = 0.0
        val len = minOf(typed.length, candidate.length)

        for (i in 0 until len) {
            if (typed[i] != candidate[i] &&
                candidate[i] in KeyboardProximity.neighborKeys(typed[i])
            ) {
                score += 25
            }
        }

        return score
    }

    private fun rankCorrection(
        language: DictionaryManager.Language,
        typed: String,
        userWords: Map<String, Int>,
    ): Scored? {
        return (
            DictionaryManager.correctionCandidates(language, typed)
                .asSequence()
                .plus(keyboardSlipCandidates(language, typed))
        )
            .filter { it != typed }
            .mapNotNull { candidate ->
                val dist = weightedDistance(typed, candidate, language)
                if (dist > MAX_EDIT_DISTANCE) return@mapNotNull null
                val score = scoreCorrection(language, typed, candidate, userWords, dist) +
                    keyboardMistakeScore(typed, candidate)
                if (score < MIN_CORRECTION_SCORE) null else Scored(candidate, score)
            }
            .maxByOrNull { it.score }
    }

    private fun shouldAutocorrect(typed: String, correction: Scored): Boolean {
        val dist = weightedDistance(typed, correction.word, DictionaryManager.Language.EN)
        if (dist <= 0.0) return false
        if (dist <= 1.0 && correction.score >= MIN_AUTOCORRECT_SCORE) return true
        if (dist <= 2.0 && typed.length >= 5 && correction.score >= MIN_AUTOCORRECT_SCORE + 12) return true
        return correction.score >= HIGH_CONFIDENCE_AUTOCORRECT_SCORE
    }

    private fun rankCompletions(
        language: DictionaryManager.Language,
        prefix: String,
        userWords: Map<String, Int>,
    ): List<Scored> {
        val userMatches = userWords.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) && it.key.length > prefix.length }
            .sortedByDescending { it.value }
            .map { Scored(it.key, 0.0) }
            .toList()

        val userSet = userMatches.mapTo(HashSet()) { it.word }

        val dictMatches = DictionaryManager.prefixMatches(language, prefix, 32)
            .asSequence()
            .filter { it != prefix && it !in userSet }
            .map { Scored(it, DictionaryManager.frequencyScore(language, it).toDouble()) }
            .sortedByDescending { it.score }
            .toList()

        return (userMatches + dictMatches).take(8)
    }

    private fun scoreCorrection(
        language: DictionaryManager.Language,
        typed: String,
        candidate: String,
        userWords: Map<String, Int>,
        weightedDist: Double,
    ): Double {
        var score = 0.0
        score += DictionaryManager.frequencyScore(language, candidate) * 1.2
        score += (userWords[candidate] ?: 0) * 200.0
        score -= weightedDist * 95.0

        if (language == DictionaryManager.Language.EN && candidate.isNotEmpty() && typed.isNotEmpty()) {
            if (candidate[0] == typed[0]) score += 18.0
            if (candidate.length == typed.length) score += 10.0
            score -= abs(candidate.length - typed.length) * 8.0
        }
        return score
    }

    private fun normalize(word: String, language: DictionaryManager.Language): String = when (language) {
        DictionaryManager.Language.EN -> word.lowercase()
        DictionaryManager.Language.HE -> word
    }

    private const val MAX_EDIT_DISTANCE = 2.5
    private const val MIN_CORRECTION_SCORE = 22.0
    private const val MIN_AUTOCORRECT_SCORE = 30.0
    private const val HIGH_CONFIDENCE_AUTOCORRECT_SCORE = 52.0

    private fun weightedDistance(
        a: String,
        b: String,
        language: DictionaryManager.Language,
    ): Double {
        if (a == b) return 0.0
        if (a.isEmpty()) return b.length.toDouble()
        if (b.isEmpty()) return a.length.toDouble()

        val proximityAware = language == DictionaryManager.Language.EN
        val n = a.length
        val m = b.length
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 0..n) d[i][0] = i.toDouble()
        for (j in 0..m) d[0][j] = j.toDouble()

        for (i in 1..n) {
            for (j in 1..m) {
                val subCost = when {
                    a[i - 1] == b[j - 1] -> 0.0
                    proximityAware && b[j - 1] in KeyboardProximity.neighborKeys(a[i - 1]) -> 0.5
                    else -> 1.0
                }
                var best = minOf(
                    d[i - 1][j] + 1.0,
                    d[i][j - 1] + 1.0,
                    d[i - 1][j - 1] + subCost,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, d[i - 2][j - 2] + 1.0)
                }
                d[i][j] = best
            }
        }
        return d[n][m]
    }
}
