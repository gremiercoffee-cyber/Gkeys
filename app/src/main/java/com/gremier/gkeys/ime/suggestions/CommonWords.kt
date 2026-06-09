package com.gremier.gkeys.ime.suggestions

/**
 * High-frequency English words for completion and autocorrect (on-device, no network).
 */
object CommonWords {

    val WORDS: Set<String> = (
        "a about after again all also am an and any are as at back be because been before " +
        "being best better between both but by can come could day did do does doing done don't " +
        "down each even every first for from get go going good got had has have having he her " +
        "here him his how i if in into is it its just know like little look make me more most " +
        "much must my need new no not now of off on one only or other our out over people " +
        "please put really right said same say see she should so some something still such take " +
        "than that the their them then there these they thing think this those through time to " +
        "too two up us use very want was way we well were what when where which while who why " +
        "will with work would write year yes yet you your yours " +
        "hello thanks thank okay ok great sorry please maybe today tomorrow tonight " +
        "love happy home phone email text message"
        ).trim().split(Regex("\\s+")).toSet()

    /** Likely next words after common predecessors (Gboard-style phrase hints). */
    private val NEXT_AFTER: Map<String, List<String>> = mapOf(
        "i" to listOf("am", "have", "will", "can", "think", "was", "don't", "need", "want", "love"),
        "you" to listOf("are", "can", "have", "will", "should", "know", "need", "want", "too"),
        "the" to listOf("best", "same", "first", "only", "most", "next", "way", "other", "same"),
        "to" to listOf("be", "the", "get", "see", "make", "go", "do", "have", "know"),
        "and" to listOf("the", "I", "then", "also", "you", "it", "we", "they"),
        "is" to listOf("the", "a", "not", "it", "this", "that", "there", "very"),
        "it" to listOf("is", "was", "will", "would", "could", "should", "looks", "seems"),
        "we" to listOf("are", "have", "will", "can", "should", "need", "want", "were"),
        "for" to listOf("the", "a", "you", "me", "us", "this", "that", "now"),
        "that" to listOf("is", "was", "would", "could", "should", "the", "you", "I"),
        "this" to listOf("is", "was", "will", "would", "could", "should", "time"),
        "are" to listOf("you", "we", "they", "the", "not", "there", "going"),
        "was" to listOf("a", "the", "not", "very", "really", "just", "so"),
        "with" to listOf("the", "you", "me", "a", "my", "your", "this", "that"),
        "have" to listOf("a", "the", "to", "you", "been", "any", "no", "to"),
        "will" to listOf("be", "have", "get", "see", "go", "not", "you", "I"),
        "can" to listOf("you", "I", "we", "be", "get", "see", "do", "not"),
        "thank" to listOf("you", "thanks", "god", "goodness"),
        "how" to listOf("are", "is", "do", "can", "about", "much", "many"),
        "what" to listOf("is", "are", "do", "did", "was", "about", "time"),
    )

    private val NEXT_WORD_STARTERS = listOf(
        "I", "the", "you", "and", "to", "a", "is", "it", "in", "for",
        "that", "this", "on", "with", "are", "be", "at", "or", "have", "not"
    )

    fun isKnown(word: String): Boolean = WORDS.contains(word.lowercase())

    fun nextWordCandidates(previousWord: String, personalVocab: Map<String, Int>): List<String> {
        val prev = previousWord.lowercase()
        val fromMap = NEXT_AFTER[prev].orEmpty()
        val fromPersonal = personalVocab.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .filter { it.length >= 2 }
            .take(12)
        val starters = NEXT_WORD_STARTERS.map { it.lowercase() }
        return (fromMap + fromPersonal + starters)
            .distinct()
            .filter { it.isNotBlank() }
            .take(20)
    }

    fun completions(prefix: String, personalVocab: Map<String, Int>, limit: Int = 12): List<String> {
        if (prefix.isBlank()) return emptyList()
        val p = prefix.lowercase()
        val fromPersonal = personalVocab.entries
            .filter { it.key.startsWith(p) && it.key.length > p.length }
            .sortedByDescending { it.value }
            .map { it.key }
        val fromCommon = WORDS
            .filter { it.startsWith(p) && it.length > p.length }
            .sortedBy { it.length }
        return (fromPersonal + fromCommon)
            .distinct()
            .take(limit)
    }

    /** Best autocorrect candidate when [typed] is not a known word (edit distance ≤ 1). */
    fun autocorrect(typed: String, personalVocab: Map<String, Int>): String? {
        if (typed.length < 2) return null
        val lower = typed.lowercase()
        if (isKnown(lower)) return null
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        val candidates = (personalVocab.keys + WORDS)
            .filter { kotlin.math.abs(it.length - lower.length) <= 1 }
        for (candidate in candidates) {
            if (candidate.length < 2) continue
            val dist = editDistance(lower, candidate)
            if (dist == 1) {
                val score = dist * 10 - (personalVocab[candidate] ?: 0)
                if (score < bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
        }
        return best
    }

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
