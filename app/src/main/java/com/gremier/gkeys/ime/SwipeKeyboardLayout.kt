package com.gremier.gkeys.ime

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.hypot

/**
 * Intercepts drags across keys so swipe-to-type works even though child key
 * views consume tap events. All coordinates passed to [swipeTyper] are local to
 * this container (matched in [SwipeTyper] via offsetDescendantRectToMyCoords).
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

    private var startX = 0f
    private var startY = 0f
    private var isSwiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                isSwiping = false
                swipeTyper?.onTouchDown(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSwiping && hypot(ev.x - startX, ev.y - startY) > SwipeTyper.SWIPE_START_THRESHOLD) {
                    isSwiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
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
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
}
