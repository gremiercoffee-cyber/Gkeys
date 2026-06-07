package com.gremier.gkeys.ime

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.hypot

class SwipeTyper(
    private val container: ViewGroup,
    private val onWordCommitted: (String) -> Unit
) {
    private val keyViews = mutableListOf<Pair<View, Char>>()
    private val path = mutableListOf<Char>()
    private var startX = 0f
    private var startY = 0f
    private var isSwiping = false
    private var suppressNextClick = false

    companion object {
        private const val SWIPE_START_THRESHOLD = 24f
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

    fun attach() {
        container.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isSwiping = false
                    path.clear()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isSwiping && hypot(event.rawX - startX, event.rawY - startY) > SWIPE_START_THRESHOLD) {
                        isSwiping = true
                        path.clear()
                    }
                    if (isSwiping) {
                        keyAt(event.rawX, event.rawY)?.let { c ->
                            if (path.isEmpty() || path.last() != c) path.add(c)
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSwiping && path.isNotEmpty()) {
                        SwipeDecoder.decode(path)?.let { word ->
                            onWordCommitted(word)
                            suppressNextClick = true
                        }
                        isSwiping = false
                        path.clear()
                        true
                    } else {
                        isSwiping = false
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun keyAt(rawX: Float, rawY: Float): Char? {
        for ((view, char) in keyViews) {
            if (!view.isShown) continue
            val rect = Rect()
            if (view.getGlobalVisibleRect(rect) && rect.contains(rawX.toInt(), rawY.toInt())) {
                return char
            }
        }
        return null
    }
}
