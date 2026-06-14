package com.gremier.gkeys.ime.slm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

interface LocalSlmBackend {
    val runtimeAvailable: Boolean
        get() = true

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

class MediaPipeTaskSlmBackend(
    private val context: Context,
) : LocalSlmBackend {
    private val tag = "MediaPipeTaskSlmBackend"
    private var inference: LlmInference? = null
    private var loadedPath: String? = null

    override fun load(modelFile: File): Boolean {
        if (!modelFile.exists() || modelFile.length() <= 0L) return false
        if (inference != null && loadedPath == modelFile.absolutePath) return true
        unload()
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(64)
                .setMaxTopK(8)
                .build()
            inference = LlmInference.createFromOptions(context.applicationContext, options)
            loadedPath = modelFile.absolutePath
            true
        } catch (e: Throwable) {
            Log.e(tag, "load_failed reason=${e.message ?: e.javaClass.simpleName}", e)
            unload()
            false
        }
    }

    override fun unload() {
        try {
            inference?.close()
        } catch (_: Throwable) {
        }
        inference = null
        loadedPath = null
    }

    override fun isLoaded(): Boolean = inference != null

    override fun rerank(request: SlmRerankRequest): SlmRerankResult {
        val engine = inference ?: throw IllegalStateException("MediaPipe .task model is not loaded")
        val prompt = buildPrompt(request)
        val response = engine.generateResponse(prompt)
        return SlmRerankResult(parseCandidateOrder(response, request.candidates))
    }

    private fun buildPrompt(request: SlmRerankRequest): String {
        val contextWords = request.previousWords.takeLast(10).joinToString(" ")
        val partial = request.currentPartial
        val candidates = request.candidates.take(8).mapIndexed { index, candidate ->
            "${index + 1}. $candidate"
        }.joinToString("\n")
        return """
            Rank keyboard suggestions for the next word.
            Return only candidate numbers, best first, comma-separated.
            Do not add words or explanation.

            Previous words: $contextWords
            Current partial: $partial
            Candidates:
            $candidates
        """.trimIndent()
    }

    private fun parseCandidateOrder(response: String, candidates: List<String>): List<String> {
        val orderedIndexes = Regex("""\d+""")
            .findAll(response)
            .mapNotNull { it.value.toIntOrNull()?.minus(1) }
            .filter { it in candidates.indices }
            .distinct()
            .toList()
        return orderedIndexes.map { candidates[it] } + candidates.filterIndexed { index, _ -> index !in orderedIndexes }
    }
}
