package com.gremier.gkeys.ime.slm

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.gremier.gkeys.settings.AiPredictionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SlmRerankDebugSnapshot(
    val originalCandidates: List<String>,
    val rerankedCandidates: List<String>,
    val latencyMs: Long,
    val applied: Boolean,
    val reason: String,
    val previousWordCount: Int,
    val currentPartialLength: Int,
)

object SlmSuggestionReranker {
    private const val TAG = "SlmSuggestionReranker"

    @Volatile
    private var lastDebugSnapshot: SlmRerankDebugSnapshot? = null

    fun rerankIfAvailable(
        context: Context,
        previousWords: List<String>,
        currentPartial: String,
        candidates: List<String>,
        underLatencyPressure: Boolean,
    ): List<String> {
        if (candidates.size < 2) {
            recordDebug(previousWords, currentPartial, candidates, candidates, 0L, "skipped_too_few_candidates")
            return candidates
        }
        if (underLatencyPressure) {
            recordDebug(previousWords, currentPartial, candidates, candidates, 0L, "skipped_latency_pressure")
            return candidates
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            recordDebug(previousWords, currentPartial, candidates, candidates, 0L, "skipped_main_thread")
            return candidates
        }
        return runBlocking(Dispatchers.Default) {
            rerankDetailed(
                context = context,
                previousWords = previousWords,
                currentPartial = currentPartial,
                candidates = candidates,
            ).rerankedCandidates
        }
    }

    suspend fun rerankSuspend(
        context: Context,
        previousWords: List<String>,
        currentPartial: String,
        candidates: List<String>,
        backendOverride: LocalSlmBackend? = null,
    ): List<String> = withContext(Dispatchers.Default) {
        rerankDetailed(
            context = context,
            previousWords = previousWords,
            currentPartial = currentPartial,
            candidates = candidates,
            backendOverride = backendOverride,
        ).rerankedCandidates
    }

    suspend fun runDiagnostic(
        context: Context,
        previousWords: List<String>,
        currentPartial: String,
        candidates: List<String>,
        backendOverride: LocalSlmBackend? = null,
    ): SlmRerankDebugSnapshot = withContext(Dispatchers.Default) {
        rerankDetailed(
            context = context,
            previousWords = previousWords,
            currentPartial = currentPartial,
            candidates = candidates,
            backendOverride = backendOverride,
        )
    }

    fun lastDebugSnapshot(): SlmRerankDebugSnapshot? = lastDebugSnapshot

    private suspend fun rerankDetailed(
        context: Context,
        previousWords: List<String>,
        currentPartial: String,
        candidates: List<String>,
        backendOverride: LocalSlmBackend? = null,
    ): SlmRerankDebugSnapshot = withContext(Dispatchers.Default) {
        val start = SystemClock.uptimeMillis()
        if (candidates.size < 2) {
            return@withContext recordDebug(
                previousWords,
                currentPartial,
                candidates,
                candidates,
                SystemClock.uptimeMillis() - start,
                "skipped_too_few_candidates",
            )
        }
        val manager = if (backendOverride == null) {
            LocalSlmManager(context)
        } else {
            LocalSlmManager(context) { backendOverride }
        }
        if (!AiPredictionSettings.enabled(context).first()) {
            return@withContext recordDebug(
                previousWords,
                currentPartial,
                candidates,
                candidates,
                SystemClock.uptimeMillis() - start,
                "skipped_disabled",
            )
        }
        if (!manager.ensureLoaded()) {
            return@withContext recordDebug(
                previousWords,
                currentPartial,
                candidates,
                candidates,
                SystemClock.uptimeMillis() - start,
                "skipped_model_unavailable",
            )
        }
        val reranked = rerankWithBackend(
            backend = manager.backend(),
            previousWords = previousWords,
            currentPartial = currentPartial,
            candidates = candidates,
            timeoutMs = LocalModelConfig.RERANK_TIMEOUT_MS,
        )
        recordDebug(
            previousWords,
            currentPartial,
            candidates,
            reranked,
            SystemClock.uptimeMillis() - start,
            if (reranked != candidates) "reranked" else "unchanged",
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

    private fun recordDebug(
        previousWords: List<String>,
        currentPartial: String,
        originalCandidates: List<String>,
        rerankedCandidates: List<String>,
        latencyMs: Long,
        reason: String,
    ): SlmRerankDebugSnapshot {
        val snapshot = SlmRerankDebugSnapshot(
            originalCandidates = originalCandidates,
            rerankedCandidates = rerankedCandidates,
            latencyMs = latencyMs,
            applied = rerankedCandidates != originalCandidates,
            reason = reason,
            previousWordCount = previousWords.size,
            currentPartialLength = currentPartial.length,
        )
        lastDebugSnapshot = snapshot
        Log.i(
            TAG,
            "rerank_result applied=${snapshot.applied} reason=$reason " +
                "candidateCount=${originalCandidates.size} latencyMs=$latencyMs " +
                "previousWordCount=${previousWords.size} partialLength=${currentPartial.length}",
        )
        return snapshot
    }
}
