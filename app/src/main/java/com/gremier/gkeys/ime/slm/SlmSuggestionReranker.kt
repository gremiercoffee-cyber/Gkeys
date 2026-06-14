package com.gremier.gkeys.ime.slm

import android.content.Context
import android.os.Looper
import com.gremier.gkeys.settings.AiPredictionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object SlmSuggestionReranker {
    fun rerankIfAvailable(
        context: Context,
        previousWords: List<String>,
        currentPartial: String,
        candidates: List<String>,
        underLatencyPressure: Boolean,
    ): List<String> {
        if (candidates.size < 2 || underLatencyPressure) return candidates
        if (Looper.myLooper() == Looper.getMainLooper()) return candidates
        return runBlocking(Dispatchers.Default) {
            rerankSuspend(context, previousWords, currentPartial, candidates)
        }
    }

    suspend fun rerankSuspend(
        context: Context,
        previousWords: List<String>,
        currentPartial: String,
        candidates: List<String>,
        backendOverride: LocalSlmBackend? = null,
    ): List<String> = withContext(Dispatchers.Default) {
        if (!AiPredictionSettings.enabled(context).first()) return@withContext candidates
        val manager = if (backendOverride == null) {
            LocalSlmManager(context)
        } else {
            LocalSlmManager(context) { backendOverride }
        }
        if (!manager.ensureLoaded()) return@withContext candidates
        rerankWithBackend(
            backend = manager.backend(),
            previousWords = previousWords,
            currentPartial = currentPartial,
            candidates = candidates,
            timeoutMs = LocalModelConfig.RERANK_TIMEOUT_MS,
        )
    }

    internal suspend fun rerankWithBackend(
        backend: LocalSlmBackend,
        previousWords: List<String>,
        currentPartial: String,
        candidates: List<String>,
        timeoutMs: Long,
    ): List<String> {
        return try {
            withTimeoutOrNull(timeoutMs) {
                val request = SlmRerankRequest(
                    previousWords = previousWords.takeLast(15),
                    currentPartial = currentPartial.take(32),
                    candidates = candidates.take(8),
                )
                val ranked = backend.rerank(request).rankedCandidates
                mergeOrder(candidates, ranked)
            } ?: candidates
        } catch (e: Throwable) {
            candidates
        }
    }

    private fun mergeOrder(original: List<String>, ranked: List<String>): List<String> {
        val known = original.toSet()
        val ordered = ranked.filter { it in known }.distinct()
        return ordered + original.filterNot { it in ordered }
    }
}
