package com.gremier.gkeys.ime

import android.graphics.Rect
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

class SwipeTyper(
    private val onWordCommitted: (String) -> Unit
) {
    private val keyViews = mutableListOf<Pair<View, Char>>()
    private val path = mutableListOf<Char>()
    private var isSwiping = false
    private var suppressNextClick = false
    private var enabled = true

    companion object {
        const val SWIPE_START_THRESHOLD = 20f
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) reset()
    }

    fun registerKey(view: View, label: String) {
        if (label.length == 1 && label[0].isLetter()) {
            keyViews.add(view to label.lowercase()[0])
        }
    }

    fun clearKeys() {
        keyViews.clear()
    }

    fun shouldSuppressClick(): Boolean {
        val suppress = suppressNextClick
        suppressNextClick = false
        return suppress
    }

    fun onTouchDown(rawX: Float, rawY: Float) {
        if (!enabled) return
        isSwiping = false
        path.clear()
        keyAt(rawX, rawY)?.let { path.add(it) }
    }

    fun onTouchMove(rawX: Float, rawY: Float) {
        if (!enabled) return
        isSwiping = true
        keyAt(rawX, rawY)?.let { c ->
            if (path.isEmpty() || path.last() != c) path.add(c)
        }
    }

    fun onTouchUp() {
        if (!enabled) {
            reset()
            return
        }
        if (isSwiping && path.size >= 2) {
            SwipeDecoder.decode(path)?.let { word ->
                if (word.length >= 2) {
                    onWordCommitted(word)
                    suppressNextClick = true
                }
            }
        }
        reset()
    }

    private fun reset() {
        isSwiping = false
        path.clear()
    }

    private fun keyAt(rawX: Float, rawY: Float): Char? {
        var best: Char? = null
        var bestDist = Float.MAX_VALUE
        for ((view, char) in keyViews) {
            if (!view.isShown) continue
            val rect = Rect()
            if (!view.getGlobalVisibleRect(rect)) continue
            if (rect.contains(rawX.toInt(), rawY.toInt())) {
                return char
            }
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val dist = hypot(rawX - cx, rawY - cy)
            val radius = max(rect.width(), rect.height()) * 0.65f
            if (dist < radius && dist < bestDist) {
                bestDist = dist
                best = char
            }
        }
        return best
    }
}
