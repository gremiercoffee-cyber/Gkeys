package com.gremier.gkeys.ime.touch

/**
 * Local-only touch learning profile. No typed text content is stored beyond
 * aggregated word-frequency counts for context biasing.
 */
data class KeyTouchStats(
    val key: Char,
    var tapCount: Int = 0,
    var sumOffsetX: Double = 0.0,
    var sumOffsetY: Double = 0.0,
    var sumOffsetX2: Double = 0.0,
    var sumOffsetY2: Double = 0.0,
    var radiusMultiplier: Float = 1f
) {
    val meanOffsetX: Float
        get() = if (tapCount == 0) 0f else (sumOffsetX / tapCount).toFloat()
    val meanOffsetY: Float
        get() = if (tapCount == 0) 0f else (sumOffsetY / tapCount).toFloat()

    fun stdDevX(keyWidth: Float): Float {
        if (tapCount < 2) return keyWidth * 0.2f
        val mean = sumOffsetX / tapCount
        val variance = (sumOffsetX2 / tapCount) - (mean * mean)
        return kotlin.math.sqrt(variance.coerceAtLeast(0.0)).toFloat().coerceAtLeast(keyWidth * 0.12f)
    }

    fun stdDevY(keyHeight: Float): Float {
        if (tapCount < 2) return keyHeight * 0.2f
        val mean = sumOffsetY / tapCount
        val variance = (sumOffsetY2 / tapCount) - (mean * mean)
        return kotlin.math.sqrt(variance.coerceAtLeast(0.0)).toFloat().coerceAtLeast(keyHeight * 0.12f)
    }

    fun recordOffset(offsetX: Float, offsetY: Float) {
        tapCount++
        sumOffsetX += offsetX
        sumOffsetY += offsetY
        sumOffsetX2 += (offsetX * offsetX).toDouble()
        sumOffsetY2 += (offsetY * offsetY).toDouble()
    }
}

/** Learned adjacent-key correction: touch zone mapped to intended key. */
data class ConfusionSample(
    val wrongKey: Char,
    val correctKey: Char,
    var relOffsetX: Float,
    var relOffsetY: Float,
    var count: Int = 1
)

enum class TypingPosture {
    UNKNOWN, TWO_THUMB, LEFT_THUMB, RIGHT_THUMB
}

data class AdaptiveTouchProfile(
    var version: Int = 1,
    var totalTaps: Int = 0,
    var correctionsLearned: Int = 0,
    var posture: TypingPosture = TypingPosture.UNKNOWN,
    var rightSideTapRatio: Float = 0.5f,
    var avgInterKeyMs: Float = 180f,
    val keyStats: MutableMap<Char, KeyTouchStats> = mutableMapOf(),
    val confusions: MutableList<ConfusionSample> = mutableListOf(),
    val vocabFrequency: MutableMap<String, Int> = mutableMapOf()
) {
    fun statsFor(key: Char): KeyTouchStats =
        keyStats.getOrPut(key.lowercaseChar()) { KeyTouchStats(key.lowercaseChar()) }

    fun accuracyEstimate(): Float {
        if (totalTaps == 0) return 0f
        val correctionRate = correctionsLearned.toFloat() / totalTaps.coerceAtLeast(1)
        return ((1f - correctionRate) * 100f).coerceIn(0f, 99.9f)
    }
}

data class RecentTap(
    val touchX: Float,
    val touchY: Float,
    val resolvedLabel: String,
    val resolvedChar: Char?,
    val timestampMs: Long
)
