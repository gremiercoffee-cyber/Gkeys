package com.gremier.gkeys.ime.touch

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import kotlin.math.hypot

/**
 * Resolves touch points to keys using proximity scoring inspired by AOSP LatinIME /
 * OpenBoard: position correction, expanded hit zones, bigram weighting, and
 * personalized offset learning.
 */
class TouchInputResolver(
    private val personalization: TouchPersonalization
) {
    private val targets = mutableListOf<KeyHitTarget>()
    private var averageKeyWidth = 48f
    private var averageKeyHeight = 48f
    private var previousChar: Char? = null
    var enabled = true
    var rightHandedMode = false

    fun clearTargets() {
        targets.clear()
    }

    fun setPreviousChar(c: Char?) {
        previousChar = c?.lowercaseChar()
    }

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
    }

    fun resolve(touchX: Float, touchY: Float): TouchResolution? {
        if (!enabled || targets.isEmpty()) return null

        val blend = personalizationBlend()
        val corrected = personalization.applyPersonalizedCorrection(
            touchX, touchY,
            rowYShift = 0f,
            blendFactor = blend,
            rightHanded = rightHandedMode,
            keyWidth = averageKeyWidth
        )
        var correctedX = corrected.first
        var correctedY = corrected.second

        val searchDist = TouchPositionCorrection.SEARCH_DISTANCE_FACTOR * averageKeyWidth
        var best: KeyHitTarget? = null
        var bestScore = Float.MAX_VALUE

        for (target in targets) {
            val dx = correctedX - target.sweetSpotX
            val dy = correctedY - target.sweetSpotY
            val distance = hypot(dx, dy)

            if (distance > searchDist * bigramRadiusBoost(target)) continue

            val radius = effectiveRadius(target)
            val score = distance / radius
            if (score < bestScore) {
                bestScore = score
                best = target
            }
        }

        // Fallback: nearest key within hard search limit
        if (best == null) {
            for (target in targets) {
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

    fun recordTap(touchX: Float, touchY: Float, resolution: TouchResolution) {
        val target = targets.firstOrNull { it.label == resolution.label } ?: return
        personalization.recordSample(touchX, touchY, target, averageKeyWidth)
    }

    private fun effectiveRadius(target: KeyHitTarget): Float {
        val diagonal = hypot(target.width, target.height)
        var radius = TouchPositionCorrection.BASE_RADIUS_FRACTION * diagonal
        radius *= BigramModel.frequencyMultiplier(target.char)
        radius *= bigramRadiusBoost(target)
        return radius.coerceAtLeast(averageKeyWidth * 0.28f)
    }

    private fun bigramRadiusBoost(target: KeyHitTarget): Float {
        val c = target.char ?: return 1f
        return BigramModel.bigramMultiplier(previousChar, c)
    }

    private fun personalizationBlend(): Float {
        val n = personalization.sampleCount()
        return (0.25f + n / 40f).coerceIn(0.25f, 0.85f)
    }
}
