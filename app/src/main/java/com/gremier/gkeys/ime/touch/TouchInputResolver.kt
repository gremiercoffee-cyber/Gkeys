package com.gremier.gkeys.ime.touch

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import kotlin.math.hypot

/**
 * Resolves touch points to keys using proximity scoring, adaptive personal key maps,
 * confusion learning, context biasing, and dynamic hitbox resizing.
 */
class TouchInputResolver(
    private val personalization: TouchPersonalization,
    private val adaptive: AdaptiveTouchIntelligence
) {
    private val targets = mutableListOf<KeyHitTarget>()
    private var averageKeyWidth = 48f
    private var averageKeyHeight = 48f
    private var previousChar: Char? = null
    private var lastTapTimeMs = 0L
    var enabled = true
    var rightHandedMode = false

    fun clearTargets() {
        targets.clear()
        averageKeyWidth = 48f
        averageKeyHeight = 48f
    }

    fun setPreviousChar(c: Char?) {
        previousChar = c?.lowercaseChar()
    }

    fun targetForLabel(label: String): KeyHitTarget? =
        targets.firstOrNull { it.label == label }

    fun letterTargets(): List<KeyHitTarget> =
        targets.filter { it.char != null }

    fun registerTarget(label: String, row: Int, centerX: Float, centerY: Float, width: Float, height: Float) {
        val char = label.singleOrNull()?.lowercaseChar()?.takeIf { it.isLetter() }
        val rowShift = TouchPositionCorrection.yShiftForRow(row, height, rightHandedMode)
        val xShift = TouchPositionCorrection.xShiftForKey(width, rightHandedMode)
        val sweetX = centerX + xShift
        val sweetY = centerY + rowShift
        targets.add(
            KeyHitTarget(
                label = label,
                char = char,
                row = row,
                centerX = centerX,
                centerY = centerY,
                width = width,
                height = height,
                sweetSpotX = sweetX,
                sweetSpotY = sweetY
            )
        )
        averageKeyWidth = (averageKeyWidth * (targets.size - 1) + width) / targets.size
        averageKeyHeight = (averageKeyHeight * (targets.size - 1) + height) / targets.size
    }

    fun refreshFromViews(container: ViewGroup, keyViews: List<Triple<View, String, Int>>) {
        clearTargets()
        val rect = Rect()
        for ((view, label, row) in keyViews) {
            if (view.width <= 0 || view.height <= 0) continue
            rect.set(0, 0, view.width, view.height)
            container.offsetDescendantRectToMyCoords(view, rect)
            registerTarget(
                label = label,
                row = row,
                centerX = rect.exactCenterX(),
                centerY = rect.exactCenterY(),
                width = rect.width().toFloat(),
                height = rect.height().toFloat()
            )
        }
        adaptive.setKeyboardWidth(container.width.toFloat())
    }

    fun resolve(touchX: Float, touchY: Float): TouchResolution? {
        if (!enabled || targets.isEmpty()) return null

        // Wide non-letter keys should win inside their painted bounds. This is
        // especially important for the spacebar, whose top edge sits near v/b/n.
        priorityNonLetterTarget(touchX, touchY)?.let {
            return TouchResolution(it.label, 0f)
        }

        val blend = personalizationBlend()
        val corrected = personalization.applyPersonalizedCorrection(
            touchX, touchY,
            rowYShift = 0f,
            blendFactor = blend,
            rightHanded = rightHandedMode,
            keyWidth = averageKeyWidth
        )
        val correctedX = corrected.first
        val correctedY = corrected.second

        val searchDist = TouchPositionCorrection.SEARCH_DISTANCE_FACTOR * averageKeyWidth
        var best: KeyHitTarget? = null
        var bestScore = Float.MAX_VALUE

        for (target in targets) {
            if (!isEligibleCandidate(correctedX, correctedY, target)) continue

            val (spotX, spotY) = adaptive.personalizedCenter(target)
            val dx = correctedX - spotX
            val dy = correctedY - spotY
            val distance = hypot(dx, dy)

            if (distance > searchDist * bigramRadiusBoost(target)) continue

            val radius = effectiveRadius(target)
            var score = distance / radius
            score *= adaptive.adaptiveBoost(touchX, touchY, target, "")
            if (score < bestScore) {
                bestScore = score
                best = target
            }
        }

        if (best == null) {
            for (target in targets) {
                if (!isEligibleCandidate(correctedX, correctedY, target)) continue

                val dx = correctedX - target.centerX
                val dy = correctedY - target.centerY
                val distance = hypot(dx, dy)
                if (distance < searchDist && distance < bestScore) {
                    bestScore = distance
                    best = target
                }
            }
        }

        return best?.let { TouchResolution(it.label, bestScore) }
    }

    private fun priorityNonLetterTarget(x: Float, y: Float): KeyHitTarget? =
        targets.firstOrNull { target ->
            target.char == null &&
                isPriorityNonLetter(target.label) &&
                x >= target.centerX - target.width * 0.5f &&
                x <= target.centerX + target.width * 0.5f &&
                y >= target.centerY - target.height * 0.5f &&
                y <= target.centerY + target.height * 0.5f
        }

    private fun isPriorityNonLetter(label: String): Boolean {
        val normalized = label.lowercase()
        return normalized == "space" ||
            normalized == " " ||
            normalized.contains("space") ||
            normalized == "␣"
    }

    fun recordTap(touchX: Float, touchY: Float, resolution: TouchResolution) {
        val target = targets.firstOrNull { it.label == resolution.label } ?: return
        if (target.char == null) return
        val now = System.currentTimeMillis()
        val interKey = if (lastTapTimeMs > 0L) now - lastTapTimeMs else 180L
        lastTapTimeMs = now

        personalization.recordSample(touchX, touchY, target, averageKeyWidth)
        adaptive.recordTap(touchX, touchY, target, resolution.label, interKey)
    }

    fun recordBackspaceOnRecentTap() {
        adaptive.recordBackspaceOnRecentTap()
    }

    fun recordCorrection(correctLabel: String) {
        val ch = correctLabel.singleOrNull()?.lowercaseChar()?.takeIf { it.isLetter() } ?: return
        val target = targets.firstOrNull { it.label.equals(correctLabel, ignoreCase = true) }
        adaptive.recordCorrection(ch, target)
    }

    private fun effectiveRadius(target: KeyHitTarget): Float {
        if (target.char == null) {
            val shortSide = minOf(target.width, target.height)
            return (shortSide * NON_LETTER_RADIUS_FRACTION)
                .coerceAtLeast(averageKeyWidth * NON_LETTER_MIN_RADIUS_FRACTION)
        }

        val diagonal = hypot(target.width, target.height)
        var radius = TouchPositionCorrection.BASE_RADIUS_FRACTION * diagonal
        radius *= BigramModel.frequencyMultiplier(target.char)
        radius *= bigramRadiusBoost(target)
        radius *= adaptive.adaptiveRadiusMultiplier(target)
        return radius.coerceAtLeast(averageKeyWidth * 0.28f)
    }

    private fun isEligibleCandidate(x: Float, y: Float, target: KeyHitTarget): Boolean {
        if (target.char != null) return true

        val halfWidth = target.width * 0.5f
        val halfHeight = target.height * 0.5f
        val horizontalPadding = minOf(averageKeyWidth * NON_LETTER_HORIZONTAL_PADDING_KEYS, target.width * 0.08f)
        val verticalPadding = minOf(averageKeyHeight * NON_LETTER_VERTICAL_PADDING_KEYS, target.height * 0.18f)

        return x >= target.centerX - halfWidth - horizontalPadding &&
            x <= target.centerX + halfWidth + horizontalPadding &&
            y >= target.centerY - halfHeight - verticalPadding &&
            y <= target.centerY + halfHeight + verticalPadding
    }

    private fun bigramRadiusBoost(target: KeyHitTarget): Float {
        val c = target.char ?: return 1f
        return BigramModel.bigramMultiplier(previousChar, c)
    }

    private fun personalizationBlend(): Float {
        val n = personalization.sampleCount()
        return (0.25f + n / 40f).coerceIn(0.25f, 0.85f)
    }

    companion object {
        private const val NON_LETTER_RADIUS_FRACTION = 0.58f
        private const val NON_LETTER_MIN_RADIUS_FRACTION = 0.24f
        private const val NON_LETTER_HORIZONTAL_PADDING_KEYS = 0.10f
        private const val NON_LETTER_VERTICAL_PADDING_KEYS = 0.12f
    }
}
