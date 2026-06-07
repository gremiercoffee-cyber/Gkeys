package com.gremier.gkeys.ime

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
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

    /** The container whose coordinate space incoming x/y values are expressed in. */
    var root: ViewGroup? = null

    companion object {
        const val SWIPE_START_THRESHOLD = 18f
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

    fun onTouchDown(x: Float, y: Float) {
        if (!enabled) return
        isSwiping = false
        path.clear()
        keyAt(x, y)?.let { path.add(it) }
    }

    fun onTouchMove(x: Float, y: Float) {
        if (!enabled) return
        isSwiping = true
        keyAt(x, y)?.let { c ->
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

    /** Resolves the key under a point expressed in [root]'s coordinate space. */
    private fun keyAt(x: Float, y: Float): Char? {
        val container = root ?: return null
        val rect = Rect()
        var best: Char? = null
        var bestDist = Float.MAX_VALUE
        for ((view, char) in keyViews) {
            if (!view.isShown) continue
            rect.set(0, 0, view.width, view.height)
            container.offsetDescendantRectToMyCoords(view, rect)
            if (rect.contains(x.toInt(), y.toInt())) {
                return char
            }
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val dist = hypot(x - cx, y - cy)
            val radius = max(rect.width(), rect.height()) * 0.7f
            if (dist < radius && dist < bestDist) {
                bestDist = dist
                best = char
            }
        }
        return best
    }
}
