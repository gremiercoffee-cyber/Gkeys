package com.gremier.gkeys.ime.touch

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Continuously learns each user's touch patterns and adjusts key recognition.
 * All data stays on-device in [AdaptiveTouchStore].
 */
class AdaptiveTouchIntelligence(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private var profile = AdaptiveTouchProfile()
    private var enabled = true
    private var loaded = false
    private var lastTapTimeMs = 0L
    private var keyboardWidth = 1f
    private var currentWordPrefix = ""

    var recentTap: RecentTap? = null
        private set

    companion object {
        private const val CORRECTION_WINDOW_MS = 2500L
        private const val EMA_ALPHA = 0.12f
        private const val RADIUS_LEARN_ALPHA = 0.08f
        private const val MAX_RADIUS_MUL = 1.45f
        private const val MIN_RADIUS_MUL = 0.72f
        private const val CONFUSION_MATCH_SIGMA = 0.35f
    }

    fun load() {
        if (loaded) return
        profile = AdaptiveTouchStore.load(appContext)
        loaded = true
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    fun profile(): AdaptiveTouchProfile = profile

    fun setKeyboardWidth(width: Float) {
        if (width > 0f) keyboardWidth = width
    }

    fun setWordPrefix(prefix: String) {
        currentWordPrefix = prefix.lowercase().take(24)
    }

    fun recordTap(
        touchX: Float,
        touchY: Float,
        target: KeyHitTarget,
        resolvedLabel: String,
        interKeyMs: Long
    ) {
        if (!enabled) return
        load()
        val now = System.currentTimeMillis()
        if (interKeyMs in 20..2000) {
            profile.avgInterKeyMs = profile.avgInterKeyMs * 0.9f + interKeyMs * 0.1f
        }
        lastTapTimeMs = now

        val offsetX = touchX - target.centerX
        val offsetY = touchY - target.centerY
        target.char?.let { ch ->
            val stats = profile.statsFor(ch)
            stats.recordOffset(offsetX, offsetY)
        }

        profile.totalTaps++
        updatePosture(touchX)

        recentTap = RecentTap(
            touchX = touchX,
            touchY = touchY,
            resolvedLabel = resolvedLabel,
            resolvedChar = target.char,
            timestampMs = now
        )
        scheduleSave()
    }

    /**
     * User deleted the last output and typed a different letter — strongest learning signal.
     */
    fun recordCorrection(correctKey: Char, correctTarget: KeyHitTarget?) {
        if (!enabled) return
        load()
        val tap = recentTap ?: return
        if (System.currentTimeMillis() - tap.timestampMs > CORRECTION_WINDOW_MS) return
        val wrong = tap.resolvedChar ?: return
        val correct = correctKey.lowercaseChar()
        if (wrong == correct) return

        profile.correctionsLearned++

        val wrongStats = profile.statsFor(wrong)
        wrongStats.radiusMultiplier = (wrongStats.radiusMultiplier - RADIUS_LEARN_ALPHA)
            .coerceIn(MIN_RADIUS_MUL, MAX_RADIUS_MUL)

        val correctStats = profile.statsFor(correct)
        correctStats.radiusMultiplier = (correctStats.radiusMultiplier + RADIUS_LEARN_ALPHA)
            .coerceIn(MIN_RADIUS_MUL, MAX_RADIUS_MUL)

        if (correctTarget != null) {
            val relX = (tap.touchX - correctTarget.centerX) / correctTarget.width.coerceAtLeast(1f)
            val relY = (tap.touchY - correctTarget.centerY) / correctTarget.height.coerceAtLeast(1f)
            mergeConfusion(wrong, correct, relX, relY)
            correctStats.recordOffset(
                tap.touchX - correctTarget.centerX,
                tap.touchY - correctTarget.centerY
            )
        }

        recentTap = null
        scheduleSave()
    }

    fun recordWordCompleted(word: String) {
        if (!enabled || word.length < 2) return
        load()
        val key = word.lowercase().take(32)
        profile.vocabFrequency[key] = (profile.vocabFrequency[key] ?: 0) + 1
        AdaptiveTouchStore.trimProfile(profile)
        scheduleSave()
    }

    fun reset() {
        profile = AdaptiveTouchProfile()
        recentTap = null
        AdaptiveTouchStore.reset(appContext)
    }

    /** Score multiplier — lower is better for the resolver. */
    fun adaptiveBoost(
        touchX: Float,
        touchY: Float,
        target: KeyHitTarget,
        wordPrefix: String
    ): Float {
        if (!enabled) return 1f
        val ch = target.char ?: return 1f
        var boost = 1f

        boost *= confusionBoost(touchX, touchY, target, ch)
        boost *= contextBoost(ch, wordPrefix.ifBlank { currentWordPrefix })
        boost *= postureBoost(target)

        val stats = profile.keyStats[ch]
        if (stats != null && stats.tapCount >= 8) {
            val dx = touchX - (target.centerX + stats.meanOffsetX)
            val dy = touchY - (target.centerY + stats.meanOffsetY)
            val sigmaX = stats.stdDevX(target.width)
            val sigmaY = stats.stdDevY(target.height)
            val gaussian = exp(
                -0.5 * ((dx * dx) / (sigmaX * sigmaX) + (dy * dy) / (sigmaY * sigmaY))
            ).toFloat()
            boost *= (0.75f + gaussian * 0.5f)
        }
        return boost.coerceIn(0.55f, 1.6f)
    }

    fun adaptiveRadiusMultiplier(target: KeyHitTarget): Float {
        if (!enabled) return 1f
        val ch = target.char ?: return 1f
        val stats = profile.keyStats[ch] ?: return speedRadiusFactor()
        return (stats.radiusMultiplier * speedRadiusFactor()).coerceIn(MIN_RADIUS_MUL, MAX_RADIUS_MUL)
    }

    fun personalizedCenter(target: KeyHitTarget): Pair<Float, Float> {
        if (!enabled) return target.sweetSpotX to target.sweetSpotY
        val ch = target.char ?: return target.sweetSpotX to target.sweetSpotY
        val stats = profile.keyStats[ch] ?: return target.sweetSpotX to target.sweetSpotY
        if (stats.tapCount < 5) return target.sweetSpotX to target.sweetSpotY
        val blend = (stats.tapCount / 60f).coerceIn(0f, 0.65f)
        val cx = target.sweetSpotX + stats.meanOffsetX * blend
        val cy = target.sweetSpotY + stats.meanOffsetY * blend
        return cx to cy
    }

    fun speedRadiusFactor(): Float = when {
        profile.avgInterKeyMs < 110f -> 1.22f
        profile.avgInterKeyMs < 150f -> 1.12f
        profile.avgInterKeyMs > 280f -> 0.92f
        else -> 1f
    }

    fun statsSummary(): String {
        load()
        val taps = profile.totalTaps
        val corrections = profile.correctionsLearned
        val accuracy = profile.accuracyEstimate()
        val learnedKeys = profile.keyStats.count { it.value.tapCount >= 10 }
        return if (taps == 0) {
            "No touch data yet — keep typing to personalize"
        } else {
            "$taps taps · $corrections corrections learned · ~${accuracy.toInt()}% accuracy · $learnedKeys keys profiled"
        }
    }

    private fun confusionBoost(touchX: Float, touchY: Float, target: KeyHitTarget, candidate: Char): Float {
        var boost = 1f
        for (sample in profile.confusions) {
            if (sample.correctKey != candidate) continue
            val relX = (touchX - target.centerX) / target.width.coerceAtLeast(1f)
            val relY = (touchY - target.centerY) / target.height.coerceAtLeast(1f)
            val dist = hypot(relX - sample.relOffsetX, relY - sample.relOffsetY)
            if (dist < CONFUSION_MATCH_SIGMA) {
                val weight = 1f + (sample.count.coerceAtMost(20) / 20f) * 0.35f
                boost /= weight
            }
        }
        for (sample in profile.confusions) {
            if (sample.wrongKey != candidate) continue
            val relX = (touchX - target.centerX) / target.width.coerceAtLeast(1f)
            val relY = (touchY - target.centerY) / target.height.coerceAtLeast(1f)
            val dist = hypot(relX - sample.relOffsetX, relY - sample.relOffsetY)
            if (dist < CONFUSION_MATCH_SIGMA) {
                boost *= 1f + (sample.count.coerceAtMost(20) / 20f) * 0.25f
            }
        }
        return boost
    }

    private fun contextBoost(candidate: Char, prefix: String): Float {
        if (prefix.isBlank()) return 1f
        var bestBoost = 1f
        val entries = profile.vocabFrequency.entries
            .filter { it.key.startsWith(prefix) && it.value >= 2 }
            .sortedByDescending { it.value }
            .take(8)
        for ((word, freq) in entries) {
            if (word.length <= prefix.length) continue
            val next = word.getOrNull(prefix.length)?.lowercaseChar() ?: continue
            if (next == candidate) {
                val w = 1f + (freq.coerceAtMost(50) / 50f) * 0.3f
                if (w > bestBoost) bestBoost = w
            }
        }
        val bigram = BigramModel.bigramMultiplier(prefix.lastOrNull(), candidate)
        if (bigram > 1.05f) {
            bestBoost = kotlin.math.max(bestBoost, bigram * 0.85f)
        }
        return if (bestBoost > 1f) 1f / bestBoost else 1f
    }

    private fun postureBoost(target: KeyHitTarget): Float {
        return when (profile.posture) {
            TypingPosture.RIGHT_THUMB -> {
                if (target.centerX > keyboardWidth * 0.45f) 0.94f else 1.04f
            }
            TypingPosture.LEFT_THUMB -> {
                if (target.centerX < keyboardWidth * 0.55f) 0.94f else 1.04f
            }
            TypingPosture.TWO_THUMB -> 0.98f
            TypingPosture.UNKNOWN -> 1f
        }
    }

    private fun updatePosture(touchX: Float) {
        if (keyboardWidth <= 1f) return
        val right = if (touchX > keyboardWidth * 0.5f) 1f else 0f
        profile.rightSideTapRatio = profile.rightSideTapRatio * 0.98f + right * 0.02f
        profile.posture = when {
            profile.rightSideTapRatio > 0.62f -> TypingPosture.RIGHT_THUMB
            profile.rightSideTapRatio < 0.38f -> TypingPosture.LEFT_THUMB
            profile.totalTaps > 200 -> TypingPosture.TWO_THUMB
            else -> TypingPosture.UNKNOWN
        }
    }

    private fun mergeConfusion(wrong: Char, correct: Char, relX: Float, relY: Float) {
        val existing = profile.confusions.firstOrNull {
            it.wrongKey == wrong && it.correctKey == correct
        }
        if (existing != null) {
            existing.count++
            existing.relOffsetX = existing.relOffsetX * (1f - EMA_ALPHA) + relX * EMA_ALPHA
            existing.relOffsetY = existing.relOffsetY * (1f - EMA_ALPHA) + relY * EMA_ALPHA
        } else {
            profile.confusions.add(ConfusionSample(wrong, correct, relX, relY, 1))
        }
        AdaptiveTouchStore.trimProfile(profile)
    }

    private var savePending = false
    private fun scheduleSave() {
        if (savePending) return
        savePending = true
        scope.launch {
            kotlinx.coroutines.delay(800)
            AdaptiveTouchStore.save(appContext, profile)
            savePending = false
        }
    }
}
