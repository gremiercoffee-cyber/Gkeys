package com.gremier.gkeys.ime

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.gremier.gkeys.ime.gesture.GestureEngine
import com.gremier.gkeys.ime.gesture.GesturePoint
import com.gremier.gkeys.ime.gesture.GestureSuggestion
import kotlin.math.hypot
import kotlin.math.max

class SwipeTyper(
    context: android.content.Context,
    private val onSuggestionsChanged: (List<GestureSuggestion>) -> Unit,
    private val onWordCommitted: (String) -> Unit
) {
    private val keyViews = mutableListOf<Pair<View, Char>>()
    private val points = mutableListOf<GesturePoint>()
    private val engine = GestureEngine(context)

    private var isSwiping = false
    private var suppressNextClick = false
    private var enabled = true
    private var lastSuggestMs = 0L

    /** The container whose coordinate space incoming x/y values are expressed in. */
    var root: ViewGroup? = null

    companion object {
        const val SWIPE_START_THRESHOLD = 18f
        private const val MIN_POINT_DISTANCE = 6f
        private const val SUGGEST_INTERVAL_MS = 45L
        private const val MIN_POINTS_FOR_MATCH = 4
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
        engine.geometry.clear()
    }

    /** Call after the keyboard layout pass so key centres are measured. */
    fun refreshGeometry(container: ViewGroup) {
        engine.geometry.clear()
        val rect = Rect()
        for ((view, char) in keyViews) {
            if (view.width <= 0 || view.height <= 0) continue
            rect.set(0, 0, view.width, view.height)
            container.offsetDescendantRectToMyCoords(view, rect)
            val size = max(rect.width(), rect.height()).toFloat()
            engine.geometry.setKeyCenter(char, rect.exactCenterX(), rect.exactCenterY(), size)
        }
    }

    fun shouldSuppressClick(): Boolean {
        val suppress = suppressNextClick
        suppressNextClick = false
        return suppress
    }

    fun onTouchDown(x: Float, y: Float) {
        if (!enabled) return
        isSwiping = false
        points.clear()
        onSuggestionsChanged(emptyList())
        addPoint(x, y)
    }

    fun onTouchMove(x: Float, y: Float) {
        if (!enabled) return
        isSwiping = true
        addPoint(x, y)
        maybeUpdateSuggestions()
    }

    fun onTouchUp() {
        if (!enabled) {
            reset()
            return
        }
        if (isSwiping && points.size >= MIN_POINTS_FOR_MATCH) {
            val suggestions = engine.suggest(points, topN = 3)
            onSuggestionsChanged(suggestions)
            suggestions.firstOrNull()?.word?.let { word ->
                if (word.length >= 2) {
                    onWordCommitted(word)
                    suppressNextClick = true
                }
            }
        } else {
            onSuggestionsChanged(emptyList())
        }
        reset()
    }

    fun clearSuggestions() {
        onSuggestionsChanged(emptyList())
    }

    private fun reset() {
        isSwiping = false
        points.clear()
    }

    private fun addPoint(x: Float, y: Float) {
        val p = GesturePoint(x, y)
        if (points.isNotEmpty()) {
            val last = points.last()
            if (hypot(p.x - last.x, p.y - last.y) < MIN_POINT_DISTANCE) return
        }
        points.add(p)
    }

    private fun maybeUpdateSuggestions() {
        if (points.size < MIN_POINTS_FOR_MATCH) return
        val now = System.currentTimeMillis()
        if (now - lastSuggestMs < SUGGEST_INTERVAL_MS) return
        lastSuggestMs = now
        onSuggestionsChanged(engine.suggest(points, topN = 3))
    }
}
