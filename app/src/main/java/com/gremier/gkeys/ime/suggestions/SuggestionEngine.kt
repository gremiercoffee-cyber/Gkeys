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
    val right: SuggestionChip?,
)

object SuggestionEngine {

    fun build(
        context: Context,
        language: DictionaryManager.Language,
        prefix: String,
        userWords: Map<String, Int>,
        previousWords: List<String> = emptyList(),
        nextWord: String? = null,
    ): SuggestionStripModel {
        DictionaryManager.ensureLoaded(context, language)
        val normalizedPrefix = normalize(prefix, language)
        if (normalizedPrefix.isEmpty()) return SuggestionStripModel(null, null, null)
        val literalTyped = prefix

        val en = language == DictionaryManager.Language.EN

        // Standalone "i" -> "I" (it's a valid word, so this runs before the word check).
        if (en && normalizedPrefix == "i") return correctionModel(literalTyped, "I")

        val typedIsWord = isRealWord(language, normalizedPrefix, userWords)
        if (!typedIsWord) {
            if (en) {
                contractionFor(normalizedPrefix)?.let { return correctionModel(literalTyped, it) }
                repeatedCharFix(language, normalizedPrefix, userWords)?.let {
                    return correctionModel(literalTyped, it)
                }
            }
            val corrections = rankCorrections(language, normalizedPrefix, userWords, previousWords, nextWord)
            if (corrections.isNotEmpty()) {
                return correctionChoicesModel(literalTyped, corrections.map { it.word })
            }
            // Compound split is suggestion-only (not auto-applied on space) to stay safe.
            if (en) {
                compoundSplit(normalizedPrefix)?.let { return correctionModel(literalTyped, it) }
            }
        }

        val completions = rankCompletions(language, normalizedPrefix, userWords)
            .filter { it.word != normalizedPrefix }
            .distinctBy { it.word }

        val centerWord = completions.getOrNull(0)?.word ?: literalTyped
        val center = SuggestionChip(centerWord, isPrimary = true)
        val left = SuggestionChip(literalTyped, isLiteralTyped = true)
        val right = completions.getOrNull(1)?.let { SuggestionChip(it.word) }
        return SuggestionStripModel(left, center, right)
    }

    fun buildPostAutocorrectUndo(original: String, corrected: String): SuggestionStripModel {
        return SuggestionStripModel(
            left = SuggestionChip(original, isLiteralTyped = true),
            center = SuggestionChip(corrected, isPrimary = true),
            right = null,
        )
    }

    fun autocorrectOnSpace(
        context: Context,
        language: DictionaryManager.Language,
        prefix: String,
        userWords: Map<String, Int>,
        previousWords: List<String> = emptyList(),
        nextWord: String? = null,
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
        val correction = rankCorrections(language, normalized, userWords, previousWords, nextWord).firstOrNull()
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
        right = null,
    )

    private fun correctionChoicesModel(typed: String, words: List<String>): SuggestionStripModel {
        val typedNormalized = typed.lowercase()
        val unique = words.filter { it.lowercase() != typedNormalized }.distinct().take(2)
        return SuggestionStripModel(
            left = SuggestionChip(typed, isLiteralTyped = true),
            center = unique.getOrNull(0)?.let { SuggestionChip(it, isPrimary = true, isCorrection = true) },
            right = unique.getOrNull(1)?.let { SuggestionChip(it, isCorrection = true) },
        )
    }

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

    private fun rankCorrections(
        language: DictionaryManager.Language,
        typed: String,
        userWords: Map<String, Int>,
        previousWords: List<String> = emptyList(),
        nextWord: String? = null,
        limit: Int = 3,
    ): List<Scored> {
        val maxDistance = maxCorrectionDistance(typed, previousWords, nextWord)
        val rerankCandidates = (
            DictionaryManager.correctionCandidates(language, typed)
                .asSequence()
                .plus(keyboardSlipCandidates(language, typed))
                .plus(proximityCorrectionCandidates(language, typed, previousWords, nextWord))
                .plus(contextSeedCandidates(language, previousWords, nextWord))
        )
            .distinct()
            .filter { it != typed }
            .mapNotNull { candidate ->
                val dist = weightedDistance(typed, candidate, language)
                if (dist > maxDistance) return@mapNotNull null
                ContextualCandidateReranker.Candidate(
                    word = candidate,
                    swipeOrTouchScore = correctionTouchScore(typed, candidate, dist, maxDistance),
                    baseFrequencyScore = (DictionaryManager.frequencyScore(language, candidate) / 100.0)
                        .coerceIn(0.0, 1.0),
                    personalPreferenceScore = ((userWords[candidate] ?: 0).coerceAtMost(50) / 50.0)
                        .coerceIn(0.0, 1.0),
                )
            }
            .toList()
        val ranked = ContextualCandidateReranker.rerank(
            rerankCandidates,
            ContextualCandidateReranker.Context(previousWords = previousWords, nextWord = nextWord),
        )
        return ranked
            .asSequence()
            .map { Scored(it.word, it.finalScore * 100.0) }
            .filter { it.score >= MIN_CORRECTION_SCORE }
            .take(limit)
            .toList()
    }

    private fun proximityCorrectionCandidates(
        language: DictionaryManager.Language,
        typed: String,
        previousWords: List<String>,
        nextWord: String?,
    ): Sequence<String> {
        if (language != DictionaryManager.Language.EN || typed.length < 3) return emptySequence()
        val hasContext = previousWords.isNotEmpty() || !nextWord.isNullOrBlank()
        val poolLimit = if (hasContext) 24_000 else 8_000
        val maxDistance = maxCorrectionDistance(typed, previousWords, nextWord)
        return DictionaryManager.topWords(language, poolLimit)
            .asSequence()
            .filter { candidate ->
                abs(candidate.length - typed.length) <= 2 &&
                    weightedDistance(typed, candidate, language) <= maxDistance
            }
    }

    private fun contextSeedCandidates(
        language: DictionaryManager.Language,
        previousWords: List<String>,
        nextWord: String?,
    ): Sequence<String> {
        if (language != DictionaryManager.Language.EN) return emptySequence()
        val previous = previousWords.lastOrNull().orEmpty().lowercase()
        val next = nextWord.orEmpty().lowercase()
        val words = LinkedHashSet<String>()
        when {
            previous in setOf("it", "this", "that", "he", "she", "what", "where") -> words.add("is")
            previous in setOf("for", "with", "let") -> words.add("us")
            previous == "thank" -> words.add("god")
            previous in setOf("feel", "is", "was", "am", "are", "a") -> words.add("good")
            previous in setOf("going", "want", "have", "need") -> words.add("to")
            previous in setOf("in", "on", "at", "for", "with", "to") -> words.add("the")
        }
        when {
            next in setOf("not", "clearly", "really", "very", "wrong", "right", "working") -> {
                words.add("is")
                words.add("are")
            }
            next == "flavor" -> words.add("its")
            next == "house" -> words.add("their")
            next == "going" || next.endsWith("ing") -> words.add("they're")
            next in setOf("much", "many") -> words.add("too")
            next in setOf("idea", "job") -> words.add("good")
            next in setOf("store", "phone", "name", "time", "way", "thing", "message", "keyboard") -> {
                words.add("the")
                words.add("my")
                words.add("your")
            }
        }
        return words.asSequence()
    }

    private fun maxCorrectionDistance(
        typed: String,
        previousWords: List<String>,
        nextWord: String?,
    ): Double {
        val hasContext = previousWords.isNotEmpty() || !nextWord.isNullOrBlank()
        return when {
            hasContext && typed.length <= 4 -> 2.8
            hasContext && typed.length <= 7 -> 3.4
            hasContext -> 4.0
            typed.length <= 4 -> 2.0
            typed.length <= 7 -> 3.0
            else -> MAX_EDIT_DISTANCE
        }
    }

    private fun correctionTouchScore(
        typed: String,
        candidate: String,
        weightedDist: Double,
        maxDistance: Double,
    ): Double {
        val distanceScore = 1.0 - (weightedDist / maxDistance.coerceAtLeast(1.0))
        val keyboardSlip = (keyboardMistakeScore(typed, candidate) / 125.0).coerceIn(0.0, 0.3)
        val sameFirst = if (typed.firstOrNull() == candidate.firstOrNull()) 0.08 else 0.0
        val sameLength = if (typed.length == candidate.length) 0.08 else 0.0
        val lengthPenalty = abs(typed.length - candidate.length) * 0.04
        return (distanceScore + keyboardSlip + sameFirst + sameLength - lengthPenalty).coerceIn(0.0, 1.0)
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


    /** Uses recent typed words to bias corrections/predictions. This learns from context without AI. */
    private fun contextScore(
        previousWords: List<String>,
        candidate: String,
    ): Double {
        if (previousWords.isEmpty()) return 0.0

        val previous = previousWords.last().lowercase()
        return when {
            previous == "it" && candidate == "is" -> 520.0
            previous == "this" && candidate == "is" -> 480.0
            previous == "that" && candidate == "is" -> 480.0
            previous == "he" && candidate == "is" -> 430.0
            previous == "she" && candidate == "is" -> 430.0
            previous == "what" && candidate == "is" -> 380.0
            previous == "where" && candidate == "is" -> 360.0
            previous == "for" && candidate == "us" -> 460.0
            previous == "with" && candidate == "us" -> 420.0
            previous == "let" && candidate == "us" -> 380.0
            previous == "thank" && candidate == "you" -> 500.0
            previous == "want" && candidate == "to" -> 400.0
            previous == "going" && candidate == "to" -> 400.0
            previous == "have" && candidate == "to" -> 360.0
            previous == "in" && candidate == "the" -> 380.0
            previous == "on" && candidate == "the" -> 360.0
            previous == "at" && candidate == "the" -> 350.0
            previous == "for" && candidate == "the" -> 320.0
            previous == "see" && candidate == "you" -> 350.0
            previous == "good" && candidate == "morning" -> 350.0
            else -> 0.0
        }
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
