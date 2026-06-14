package com.gremier.gkeys.ime.slm

import java.io.File

interface LocalSlmBackend {
    fun load(modelFile: File): Boolean
    fun unload()
    fun isLoaded(): Boolean
    fun rerank(request: SlmRerankRequest): SlmRerankResult
}

data class SlmRerankRequest(
    val previousWords: List<String>,
    val currentPartial: String,
    val candidates: List<String>,
)

data class SlmRerankResult(
    val rankedCandidates: List<String>,
)

class MockLocalSlmBackend : LocalSlmBackend {
    private var loaded = false

    override fun load(modelFile: File): Boolean {
        loaded = modelFile.exists() && modelFile.length() > 0L
        return loaded
    }

    override fun unload() {
        loaded = false
    }

    override fun isLoaded(): Boolean = loaded

    override fun rerank(request: SlmRerankRequest): SlmRerankResult {
        if (!loaded) throw IllegalStateException("Local SLM backend is not loaded")
        // TODO: Replace with llama.cpp/JNI GGUF inference. The native backend must return only
        // candidate IDs/order, never generated prose, and must not log typed content.
        val previous = request.previousWords.lastOrNull().orEmpty().lowercase()
        val ranked = request.candidates.sortedWith(
            compareByDescending<String> { localPhraseScore(previous, it.lowercase()) }
                .thenBy { request.candidates.indexOf(it) }
        )
        return SlmRerankResult(ranked)
    }

    private fun localPhraseScore(previous: String, candidate: String): Int = when {
        previous == "thank" && candidate == "you" -> 5
        previous in setOf("going", "want", "need", "have") && candidate == "to" -> 4
        previous in setOf("in", "on", "at", "for") && candidate == "the" -> 3
        else -> 0
    }
}
