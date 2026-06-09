package com.gremier.gkeys.ime.suggestions

/**
 * Word-level bigram weights for context-aware predictions and autocorrect.
 */
object WordBigramModel {

    private val successors: Map<String, Map<String, Float>> = buildMap {
        fun row(prev: String, vararg pairs: Pair<String, Float>) {
            put(prev, pairs.toMap())
        }
        row("i", "am" to 1f, "have" to 0.95f, "will" to 0.92f, "can" to 0.9f, "think" to 0.88f,
            "was" to 0.85f, "don't" to 0.82f, "need" to 0.8f, "want" to 0.78f, "would" to 0.75f,
            "should" to 0.72f, "could" to 0.7f, "love" to 0.68f, "know" to 0.65f, "just" to 0.62f)
        row("you", "are" to 1f, "can" to 0.95f, "have" to 0.92f, "will" to 0.9f, "should" to 0.88f,
            "know" to 0.85f, "need" to 0.82f, "want" to 0.8f, "too" to 0.75f, "may" to 0.72f,
            "might" to 0.7f, "must" to 0.68f, "would" to 0.65f)
        row("the", "best" to 1f, "same" to 0.95f, "first" to 0.92f, "only" to 0.9f, "most" to 0.88f,
            "next" to 0.85f, "way" to 0.82f, "other" to 0.8f, "last" to 0.78f, "right" to 0.75f,
            "whole" to 0.72f, "new" to 0.7f, "main" to 0.68f)
        row("to", "be" to 1f, "the" to 0.95f, "get" to 0.92f, "see" to 0.9f, "make" to 0.88f,
            "go" to 0.85f, "do" to 0.82f, "have" to 0.8f, "know" to 0.78f, "help" to 0.75f,
            "find" to 0.72f, "take" to 0.7f, "keep" to 0.68f)
        row("and", "the" to 1f, "i" to 0.92f, "then" to 0.88f, "also" to 0.85f, "you" to 0.82f,
            "it" to 0.8f, "we" to 0.78f, "they" to 0.75f, "that" to 0.72f, "if" to 0.7f)
        row("is", "the" to 1f, "a" to 0.95f, "not" to 0.92f, "it" to 0.9f, "this" to 0.88f,
            "that" to 0.85f, "there" to 0.82f, "very" to 0.8f, "just" to 0.78f, "now" to 0.75f,
            "still" to 0.72f, "going" to 0.7f)
        row("it", "is" to 1f, "was" to 0.95f, "will" to 0.92f, "would" to 0.9f, "could" to 0.88f,
            "should" to 0.85f, "looks" to 0.82f, "seems" to 0.8f, "doesn't" to 0.78f, "takes" to 0.75f)
        row("we", "are" to 1f, "have" to 0.95f, "will" to 0.92f, "can" to 0.9f, "should" to 0.88f,
            "need" to 0.85f, "want" to 0.82f, "were" to 0.8f, "could" to 0.78f, "would" to 0.75f)
        row("for", "the" to 1f, "a" to 0.95f, "you" to 0.92f, "me" to 0.9f, "us" to 0.88f,
            "this" to 0.85f, "that" to 0.82f, "now" to 0.8f, "example" to 0.75f, "more" to 0.72f)
        row("that", "is" to 1f, "was" to 0.95f, "would" to 0.92f, "could" to 0.9f, "should" to 0.88f,
            "the" to 0.85f, "you" to 0.82f, "i" to 0.8f, "we" to 0.78f, "they" to 0.75f)
        row("this", "is" to 1f, "was" to 0.95f, "will" to 0.92f, "would" to 0.9f, "could" to 0.88f,
            "should" to 0.85f, "time" to 0.82f, "week" to 0.78f, "year" to 0.75f)
        row("are", "you" to 1f, "we" to 0.95f, "they" to 0.92f, "the" to 0.9f, "not" to 0.88f,
            "there" to 0.85f, "going" to 0.82f, "still" to 0.8f, "available" to 0.75f)
        row("was", "a" to 1f, "the" to 0.95f, "not" to 0.92f, "very" to 0.9f, "really" to 0.88f,
            "just" to 0.85f, "so" to 0.82f, "going" to 0.8f, "wondering" to 0.75f)
        row("with", "the" to 1f, "you" to 0.95f, "me" to 0.92f, "a" to 0.9f, "my" to 0.88f,
            "your" to 0.85f, "this" to 0.82f, "that" to 0.8f, "him" to 0.75f, "her" to 0.72f)
        row("have", "a" to 1f, "the" to 0.95f, "to" to 0.92f, "you" to 0.9f, "been" to 0.88f,
            "any" to 0.85f, "no" to 0.82f, "not" to 0.8f, "to" to 0.78f)
        row("will", "be" to 1f, "have" to 0.95f, "get" to 0.92f, "see" to 0.9f, "go" to 0.88f,
            "not" to 0.85f, "you" to 0.82f, "need" to 0.8f, "take" to 0.78f)
        row("can", "you" to 1f, "i" to 0.95f, "we" to 0.92f, "be" to 0.9f, "get" to 0.88f,
            "see" to 0.85f, "do" to 0.82f, "not" to 0.8f, "help" to 0.78f, "also" to 0.75f)
        row("thank", "you" to 1f, "god" to 0.85f, "goodness" to 0.7f)
        row("thanks", "for" to 1f, "again" to 0.85f, "so" to 0.8f)
        row("how", "are" to 1f, "is" to 0.95f, "do" to 0.92f, "can" to 0.9f, "about" to 0.88f,
            "much" to 0.85f, "many" to 0.82f, "long" to 0.8f, "to" to 0.78f)
        row("what", "is" to 1f, "are" to 0.95f, "do" to 0.92f, "did" to 0.9f, "was" to 0.88f,
            "about" to 0.85f, "time" to 0.82f, "if" to 0.8f, "you" to 0.78f)
        row("do", "you" to 1f, "i" to 0.95f, "we" to 0.92f, "not" to 0.9f, "the" to 0.88f,
            "they" to 0.85f, "it" to 0.82f, "this" to 0.8f)
        row("did", "you" to 1f, "i" to 0.95f, "we" to 0.92f, "not" to 0.9f, "he" to 0.85f,
            "they" to 0.82f, "it" to 0.8f)
        row("don't", "know" to 1f, "think" to 0.95f, "want" to 0.92f, "need" to 0.9f,
            "have" to 0.88f, "like" to 0.85f, "see" to 0.82f, "worry" to 0.8f)
        row("can't", "wait" to 1f, "believe" to 0.95f, "find" to 0.92f, "see" to 0.9f,
            "help" to 0.88f, "make" to 0.85f)
        row("let", "me" to 1f, "us" to 0.95f, "you" to 0.9f, "them" to 0.85f)
        row("see", "you" to 1f, "the" to 0.95f, "if" to 0.92f, "what" to 0.9f, "how" to 0.88f)
        row("good", "morning" to 1f, "night" to 0.95f, "luck" to 0.92f, "idea" to 0.88f,
            "job" to 0.85f, "day" to 0.82f, "thing" to 0.8f)
        row("looking", "forward" to 1f, "for" to 0.95f, "at" to 0.9f, "to" to 0.88f)
        row("talk", "to" to 1f, "about" to 0.95f, "with" to 0.92f, "soon" to 0.85f)
        row("please", "let" to 1f, "send" to 0.95f, "call" to 0.92f, "help" to 0.9f,
            "check" to 0.88f, "confirm" to 0.85f)
        row("hope", "you" to 1f, "to" to 0.95f, "this" to 0.92f, "that" to 0.9f, "all" to 0.88f)
        row("on", "the" to 1f, "my" to 0.95f, "your" to 0.92f, "a" to 0.9f, "this" to 0.88f,
            "monday" to 0.85f, "friday" to 0.82f, "time" to 0.8f)
        row("in", "the" to 1f, "a" to 0.95f, "this" to 0.92f, "my" to 0.9f, "your" to 0.88f,
            "case" to 0.85f, "order" to 0.82f, "fact" to 0.8f)
        row("at", "the" to 1f, "least" to 0.95f, "all" to 0.92f, "this" to 0.9f, "home" to 0.88f,
            "work" to 0.85f, "night" to 0.82f)
        row("my", "name" to 1f, "phone" to 0.95f, "email" to 0.92f, "address" to 0.9f,
            "friend" to 0.88f, "family" to 0.85f, "opinion" to 0.82f)
        row("your", "name" to 1f, "email" to 0.95f, "phone" to 0.92f, "address" to 0.9f,
            "help" to 0.88f, "time" to 0.85f)
        row("if", "you" to 1f, "i" to 0.95f, "we" to 0.92f, "they" to 0.9f, "not" to 0.88f,
            "possible" to 0.85f, "needed" to 0.82f)
        row("when", "you" to 1f, "i" to 0.95f, "we" to 0.92f, "they" to 0.9f, "the" to 0.88f,
            "are" to 0.85f, "is" to 0.82f)
        row("because", "i" to 1f, "you" to 0.95f, "we" to 0.92f, "they" to 0.9f, "of" to 0.88f,
            "the" to 0.85f)
        row("but", "i" to 1f, "you" to 0.95f, "we" to 0.92f, "they" to 0.9f, "the" to 0.88f,
            "not" to 0.85f, "if" to 0.82f)
        row("so", "i" to 1f, "you" to 0.95f, "we" to 0.92f, "they" to 0.9f, "the" to 0.88f,
            "much" to 0.85f, "far" to 0.82f)
        row("just", "a" to 1f, "the" to 0.95f, "wanted" to 0.92f, "got" to 0.9f, "like" to 0.88f,
            "need" to 0.85f, "checking" to 0.82f)
        row("really", "appreciate" to 1f, "good" to 0.95f, "like" to 0.92f, "need" to 0.9f,
            "sorry" to 0.88f, "want" to 0.85f)
        row("sorry", "for" to 1f, "about" to 0.95f, "to" to 0.92f, "i" to 0.9f, "if" to 0.88f)
        row("hello", "how" to 1f, "there" to 0.95f, "everyone" to 0.9f, "again" to 0.85f)
        row("hey", "how" to 1f, "there" to 0.95f, "what" to 0.92f, "guys" to 0.88f)
    }

    private val SENTENCE_STARTERS = listOf(
        "i", "the", "you", "we", "it", "this", "that", "please", "thanks", "sorry",
        "hello", "hey", "good", "just", "if", "when", "how", "what", "do", "can"
    )

    fun contextScore(previousWord: String, candidate: String): Float {
        if (previousWord.isBlank()) return 0f
        val prev = previousWord.lowercase()
        val cand = candidate.lowercase()
        return successors[prev]?.get(cand) ?: 0f
    }

    fun nextWordCandidates(previousWord: String): List<String> {
        if (previousWord.isBlank()) return SENTENCE_STARTERS
        val prev = previousWord.lowercase()
        return successors[prev]?.entries
            ?.sortedByDescending { it.value }
            ?.map { it.key }
            .orEmpty()
    }
}
