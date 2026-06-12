package com.gremier.gkeys.ime.suggestions

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextualCandidateRerankerTest {

    @Test
    fun ranksAuxiliaryBeforePredicate() {
        assertEquals(
            "is",
            topCandidate(
                previousWords = listOf("this"),
                nextWord = "clearly",
                candidates = listOf(
                    "its" to 0.78,
                    "is" to 0.62,
                ),
            ),
        )
    }

    @Test
    fun ranksPossessiveBeforeNoun() {
        assertEquals(
            "its",
            topCandidate(
                nextWord = "flavor",
                candidates = listOf(
                    "is" to 0.78,
                    "its" to 0.62,
                ),
            ),
        )
    }

    @Test
    fun ranksGodAfterThank() {
        assertEquals(
            "god",
            topCandidate(
                previousWords = listOf("thank"),
                candidates = listOf(
                    "good" to 0.7,
                    "god" to 0.62,
                ),
            ),
        )
    }

    @Test
    fun ranksGoodAfterWas() {
        assertEquals(
            "good",
            topCandidate(
                previousWords = listOf("that", "was"),
                candidates = listOf(
                    "god" to 0.72,
                    "good" to 0.62,
                ),
            ),
        )
    }

    @Test
    fun ranksToInGoingToThe() {
        assertEquals(
            "to",
            topCandidate(
                previousWords = listOf("i", "am", "going"),
                nextWord = "the",
                candidates = listOf(
                    "too" to 0.72,
                    "two" to 0.72,
                    "to" to 0.62,
                ),
            ),
        )
    }

    @Test
    fun ranksTooBeforeMuch() {
        assertEquals(
            "too",
            topCandidate(
                previousWords = listOf("that", "is"),
                nextWord = "much",
                candidates = listOf(
                    "to" to 0.72,
                    "two" to 0.72,
                    "too" to 0.62,
                ),
            ),
        )
    }

    @Test
    fun ranksTheirBeforeNoun() {
        assertEquals(
            "their",
            topCandidate(
                nextWord = "house",
                candidates = listOf(
                    "there" to 0.72,
                    "their" to 0.62,
                ),
            ),
        )
    }

    @Test
    fun ranksContractionBeforeVerbLikeWord() {
        assertEquals(
            "they're",
            topCandidate(
                nextWord = "going",
                candidates = listOf(
                    "their" to 0.72,
                    "there" to 0.72,
                    "they're" to 0.62,
                ),
            ),
        )
    }

    private fun topCandidate(
        previousWords: List<String> = emptyList(),
        nextWord: String? = null,
        candidates: List<Pair<String, Double>>,
    ): String {
        return ContextualCandidateReranker.rerank(
            candidates.map { (word, touchScore) ->
                ContextualCandidateReranker.Candidate(
                    word = word,
                    swipeOrTouchScore = touchScore,
                    baseFrequencyScore = 0.5,
                )
            },
            ContextualCandidateReranker.Context(
                previousWords = previousWords,
                nextWord = nextWord,
            ),
        ).first().word
    }
}
