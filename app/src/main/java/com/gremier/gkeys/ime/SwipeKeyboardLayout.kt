package com.gremier.gkeys.ime

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import com.gremier.gkeys.ime.touch.TouchInputResolver
import kotlin.math.hypot

/**
 * Intercepts drags for swipe-to-type and centralizes tap resolution with
 * proximity correction (OpenBoard / AOSP-style) when [touchResolver] is enabled.
 */
class SwipeKeyboardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var swipeTyper: SwipeTyper? = null
        set(value) {
            field = value
            value?.root = this
        }

    var touchResolver: TouchInputResolver? = null
    var onKeyTap: ((String) -> Unit)? = null
    var onBackspaceDown: (() -> Unit)? = null
    var onBackspaceUp: (() -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var isSwiping = false
    private var backspaceLongPressFired = false

    private val backspaceLongPressRunnable = Runnable {
        backspaceLongPressFired = true
        onBackspaceDown?.invoke()
    }

    private val useCentralTapHandling: Boolean
        get() = touchResolver?.enabled == true && onKeyTap != null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                isSwiping = false
                backspaceLongPressFired = false
                removeCallbacks(backspaceLongPressRunnable)
                swipeTyper?.onTouchDown(ev.x, ev.y)
                if (useCentralTapHandling) {
                    maybeScheduleBackspaceLongPress(ev.x, ev.y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSwiping && hypot(ev.x - startX, ev.y - startY) > SwipeTyper.SWIPE_START_THRESHOLD) {
                    isSwiping = true
                    removeCallbacks(backspaceLongPressRunnable)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isSwiping) {
                    swipeTyper?.onTouchMove(ev.x, ev.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwiping) {
                    swipeTyper?.onTouchUp()
                    isSwiping = false
                    return true
                }
                if (useCentralTapHandling) return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!useCentralTapHandling) {
            return handleSwipeOnlyTouch(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isSwiping = false
                backspaceLongPressFired = false
                removeCallbacks(backspaceLongPressRunnable)
                swipeTyper?.onTouchDown(event.x, event.y)
                maybeScheduleBackspaceLongPress(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSwiping && hypot(event.x - startX, event.y - startY) > SwipeTyper.SWIPE_START_THRESHOLD) {
                    isSwiping = true
                    removeCallbacks(backspaceLongPressRunnable)
                }
                if (isSwiping) swipeTyper?.onTouchMove(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(backspaceLongPressRunnable)
                if (isSwiping) {
                    swipeTyper?.onTouchUp()
                    isSwiping = false
                } else {
                    dispatchResolvedTap(event.x, event.y)
                }
                backspaceLongPressFired = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun maybeScheduleBackspaceLongPress(x: Float, y: Float) {
        val resolver = touchResolver ?: return
        if (resolver.resolve(x, y)?.label == "⌫") {
            postDelayed(backspaceLongPressRunnable, BACKSPACE_LONG_PRESS_MS)
        }
    }

    private fun dispatchResolvedTap(x: Float, y: Float) {
        if (swipeTyper?.shouldSuppressClick() == true) return
        val resolver = touchResolver ?: return
        val resolution = resolver.resolve(x, y) ?: return
        resolver.recordTap(x, y, resolution)
        val label = resolution.label
        if (label == "⌫") {
            if (backspaceLongPressFired) {
                onBackspaceUp?.invoke()
            } else {
                onKeyTap?.invoke(label)
            }
        } else {
            onKeyTap?.invoke(label)
        }
    }

    private fun handleSwipeOnlyTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                swipeTyper?.onTouchDown(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSwiping && hypot(event.x - startX, event.y - startY) > SwipeTyper.SWIPE_START_THRESHOLD) {
                    isSwiping = true
                }
                if (isSwiping) swipeTyper?.onTouchMove(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwiping) {
                    swipeTyper?.onTouchUp()
                    isSwiping = false
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val BACKSPACE_LONG_PRESS_MS = 380L
    }
}
