package com.gremier.gkeys.ime.suggestions

import com.gremier.gkeys.ime.personalization.PersonalLanguageProfile

/**
 * Reranks already-plausible candidates using lightweight local language signals.
 *
 * The geometry/typo decoder owns "could this be the word?". This class owns
 * "does this word make sense here?". Scores are normalized-ish 0..1 inputs so
 * the weights below stay easy to tune from real examples.
 */
object ContextualCandidateReranker {
    data class Candidate(
        val word: String,
        val swipeOrTouchScore: Double,
        val baseFrequencyScore: Double,
        val personalPreferenceScore: Double = 0.0,
        val correctionLearningScore: Double = 0.0,
    )

    data class Context(
        val previousWords: List<String> = emptyList(),
        val nextWord: String? = null,
        val typedWord: String = "",
        val profile: PersonalLanguageProfile? = null,
    ) {
        val previous: String get() = previousWords.lastOrNull().orEmpty().lowercase()
        val next: String get() = nextWord.orEmpty().lowercase()
    }

    data class RankedCandidate(
        val word: String,
        val finalScore: Double,
        val swipeOrTouchScore: Double,
        val baseFrequencyScore: Double,
        val contextLanguageScore: Double,
        val grammarCompatibilityScore: Double,
        val personalPreferenceScore: Double,
        val correctionLearningScore: Double,
        val llmProfileBoostScore: Double,
        val autocorrectUndoPenaltyScore: Double,
        val explanations: List<String> = emptyList(),
    )

    @Volatile
    var lastDebugExplanations: List<String> = emptyList()
        private set

    fun rerank(candidates: List<Candidate>, context: Context): List<RankedCandidate> =
        candidates
            .distinctBy { it.word.lowercase() }
            .map { candidate ->
                val normalized = candidate.word.lowercase()
                val contextScore = contextLanguageScore(context, normalized)
                val grammarScore = grammarCompatibilityScore(context, normalized)
                val personal = candidate.personalPreferenceScore.coerceIn(-1.0, 1.0)
                val learning = candidate.correctionLearningScore.coerceIn(-1.0, 1.0)
                val profileSignal = profileSignal(context, normalized)
                val finalScore =
                    candidate.swipeOrTouchScore.coerceIn(0.0, 1.0) * WEIGHT_TOUCH +
                    candidate.baseFrequencyScore.coerceIn(0.0, 1.0) * WEIGHT_FREQUENCY +
                    contextScore * WEIGHT_CONTEXT +
                    grammarScore * WEIGHT_GRAMMAR +
                    personal * WEIGHT_PERSONAL +
                    learning * WEIGHT_LEARNING +
                    profileSignal.boost * WEIGHT_PROFILE -
                    profileSignal.undoPenalty * WEIGHT_UNDO_PENALTY
                RankedCandidate(
                    word = candidate.word,
                    finalScore = finalScore,
                    swipeOrTouchScore = candidate.swipeOrTouchScore,
                    baseFrequencyScore = candidate.baseFrequencyScore,
                    contextLanguageScore = contextScore,
                    grammarCompatibilityScore = grammarScore,
                    personalPreferenceScore = personal,
                    correctionLearningScore = learning,
                    llmProfileBoostScore = profileSignal.boost,
                    autocorrectUndoPenaltyScore = profileSignal.undoPenalty,
                    explanations = profileSignal.explanations,
                )
            }
            .sortedByDescending { it.finalScore }
            .also { ranked ->
                lastDebugExplanations = ranked.take(3).flatMap { candidate ->
                    candidate.explanations.map { "${candidate.word}: $it" }
                }
            }

    private data class ProfileSignal(
        val boost: Double,
        val undoPenalty: Double,
        val explanations: List<String>,
    )

    private fun profileSignal(context: Context, candidate: String): ProfileSignal {
        val profile = context.profile ?: return ProfileSignal(0.0, 0.0, emptyList())
        val previous = context.previous
        val typed = context.typedWord.lowercase()
        var boost = 0.0
        var penalty = 0.0
        val reasons = mutableListOf<String>()

        profile.customVocabulary.firstOrNull { it.text.equals(candidate, ignoreCase = true) }?.let {
            boost += it.weight.coerceIn(0.0, 1.0)
            reasons += it.reason.ifBlank { "boosted as personal vocabulary" }
        }
        profile.recentTopicBoosts.firstOrNull { it.text.equals(candidate, ignoreCase = true) }?.let {
            boost += it.weight.coerceIn(0.0, 1.0) * 0.8
            reasons += it.reason.ifBlank { "boosted as a recent project or brand term" }
        }
        profile.nextWordPredictions.firstOrNull {
            it.previous.equals(previous, ignoreCase = true) && it.next.equals(candidate, ignoreCase = true)
        }?.let {
            boost += it.weight.coerceIn(0.0, 1.0)
            reasons += it.reason.ifBlank { "boosted because user often writes '$previous $candidate'" }
        }
        profile.phraseBoosts.firstOrNull {
            it.text.equals("$previous $candidate", ignoreCase = true)
        }?.let {
            boost += it.weight.coerceIn(0.0, 1.0)
            reasons += it.reason.ifBlank { "boosted because user often writes '${it.text}'" }
        }
        if (profile.neverAutocorrect.any { it.equals(typed, ignoreCase = true) || it.equals(candidate, ignoreCase = true) }) {
            penalty += 1.0
            reasons += "penalized because this word is marked never autocorrect"
        }
        profile.correctionPenalties.firstOrNull {
            it.typed.equals(typed, ignoreCase = true) && it.correction.equals(candidate, ignoreCase = true)
        }?.let {
            penalty += it.weight.coerceIn(0.0, 1.0)
            reasons += it.reason.ifBlank { "penalized because user previously undid this autocorrect" }
        }
        return ProfileSignal(
            boost = boost.coerceIn(0.0, 1.0),
            undoPenalty = penalty.coerceIn(0.0, 1.0),
            explanations = reasons.take(3),
        )
    }

    private fun contextLanguageScore(context: Context, candidate: String): Double {
        val previous = context.previous
        val next = context.next
        var score = 0.0

        score += when {
            previous == "it" && candidate == "is" -> 1.0
            previous == "this" && candidate == "is" -> 0.95
            previous == "that" && candidate == "is" -> 0.95
            previous == "he" && candidate == "is" -> 0.85
            previous == "she" && candidate == "is" -> 0.85
            previous in QUESTION_WORDS && candidate == "is" -> 0.72
            previous == "for" && candidate == "us" -> 0.95
            previous == "with" && candidate == "us" -> 0.88
            previous == "let" && candidate == "us" -> 0.82
            previous == "thank" && candidate == "god" -> 1.0
            previous == "feel" && candidate == "good" -> 0.9
            previous == "is" && candidate == "good" -> 0.82
            previous == "was" && candidate == "good" -> 0.82
            previous == "a" && candidate == "good" -> 0.58
            previous in setOf("going", "want", "have", "need") && candidate == "to" -> 0.95
            previous in setOf("in", "on", "at", "for", "with", "to") && candidate == "the" -> 0.82
            else -> 0.0
        }

        score += when {
            next in PREDICATE_STARTERS && candidate in AUXILIARY_WORDS -> 0.9
            next == "much" && candidate == "too" -> 1.0
            next == "many" && candidate == "too" -> 0.88
            next == "store" && candidate == "the" -> 0.35
            next in NOUN_LIKE_WORDS && candidate in POSSESSIVES -> 0.92
            next in VERB_LIKE_WORDS && candidate in CONTRACTION_AUXILIARIES -> 0.95
            next.endsWith("ing") && candidate in CONTRACTION_AUXILIARIES -> 0.9
            next.endsWith("ing") && candidate in AUXILIARY_WORDS -> 0.82
            next == "idea" && candidate == "good" -> 0.95
            next == "flavor" && candidate == "its" -> 1.0
            next == "house" && candidate == "their" -> 1.0
            else -> 0.0
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun grammarCompatibilityScore(context: Context, candidate: String): Double {
        val previous = context.previous
        val next = context.next
        var score = 0.45

        when (candidate) {
            "is", "are", "am", "was", "were" -> {
                if (next in PREDICATE_STARTERS || next.endsWith("ing") || next.endsWith("ed")) score += 0.45
                if (next in NOUN_LIKE_WORDS) score -= 0.22
                if (previous in SUBJECT_PRONOUNS || previous in setOf("this", "that", "what", "where")) score += 0.22
            }
            "its", "their", "your", "my", "our", "his", "her" -> {
                if (next in NOUN_LIKE_WORDS) score += 0.48
                if (next in PREDICATE_STARTERS || next.endsWith("ing")) score -= 0.42
                if (candidate == "their" && next in NOUN_LIKE_WORDS) score += 0.02
                if (candidate == "their" && next.endsWith("ing")) score -= 0.35
            }
            "it's", "they're", "you're", "we're" -> {
                if (next in PREDICATE_STARTERS || next.endsWith("ing")) score += 0.45
                if (next in NOUN_LIKE_WORDS) score -= 0.35
                if (candidate == "they're" && (next.endsWith("ing") || next in PREDICATE_STARTERS)) {
                    score += 0.05
                }
            }
            "to" -> {
                if (previous in setOf("going", "want", "have", "need")) score += 0.4
                if (next in setOf("the", "a", "an") || next in VERB_LIKE_WORDS) score += 0.22
            }
            "too" -> {
                if (next in setOf("much", "many")) score += 0.48
                if (previous in setOf("going", "want", "have", "need")) score -= 0.3
            }
            "two" -> {
                if (next in COUNTABLE_NOUNS) score += 0.35
                if (previous in setOf("going", "want", "have", "need")) score -= 0.25
            }
            "good" -> {
                if (previous in setOf("feel", "is", "was", "am", "are", "very", "really", "a")) score += 0.35
                if (next in NOUN_LIKE_WORDS || next in setOf("idea", "job")) score += 0.25
            }
            "god" -> {
                if (previous == "thank" || next == "willing") score += 0.55
                if (next in NOUN_LIKE_WORDS && previous != "thank") score -= 0.25
            }
            "there" -> {
                if (next in AUXILIARY_WORDS || next in setOf("is", "are", "was", "were")) score += 0.35
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    private val SUBJECT_PRONOUNS = setOf("i", "you", "he", "she", "it", "we", "they")
    private val QUESTION_WORDS = setOf("what", "where", "when", "why", "how", "who")
    private val AUXILIARY_WORDS = setOf("is", "are", "am", "was", "were", "be", "been", "being")
    private val POSSESSIVES = setOf("its", "their", "your", "my", "our", "his", "her")
    private val CONTRACTION_AUXILIARIES = setOf("it's", "they're", "you're", "we're", "he's", "she's")
    private val PREDICATE_STARTERS = setOf(
        "not", "clearly", "really", "very", "so", "wrong", "right", "good", "bad",
        "ready", "done", "working", "going", "coming", "using", "much", "many"
    )
    private val VERB_LIKE_WORDS = setOf(
        "go", "going", "come", "coming", "work", "working", "use", "using", "see", "make", "made",
        "do", "doing", "get", "getting", "take", "taking", "have", "having"
    )
    private val NOUN_LIKE_WORDS = setOf(
        "flavor", "house", "phone", "idea", "store", "car", "name", "time", "way", "job",
        "thing", "stuff", "text", "message", "keyboard", "word", "screen", "home"
    )
    private val COUNTABLE_NOUNS = setOf("things", "people", "minutes", "hours", "days", "words", "messages")

    // Geometry/typo remains the largest single input, but context+grammar together
    // can override it for clearly wrong short words such as "this its clearly".
    private const val WEIGHT_TOUCH = 0.34
    private const val WEIGHT_FREQUENCY = 0.12
    private const val WEIGHT_CONTEXT = 0.22
    private const val WEIGHT_GRAMMAR = 0.20
    private const val WEIGHT_PERSONAL = 0.07
    private const val WEIGHT_LEARNING = 0.05
    private const val WEIGHT_PROFILE = 0.14
    private const val WEIGHT_UNDO_PENALTY = 0.30
}
