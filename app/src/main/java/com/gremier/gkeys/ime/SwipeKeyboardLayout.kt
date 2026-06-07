package com.gremier.gkeys.ime

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.hypot

/**
 * Intercepts horizontal drags across keys so swipe-to-type works even though
 * child key views consume tap events.
 */
class SwipeKeyboardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var swipeTyper: SwipeTyper? = null

    private var startX = 0f
    private var startY = 0f
    private var isSwiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.rawX
                startY = ev.rawY
                isSwiping = false
                swipeTyper?.onTouchDown(ev.rawX, ev.rawY)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSwiping && hypot(ev.rawX - startX, ev.rawY - startY) > SwipeTyper.SWIPE_START_THRESHOLD) {
                    isSwiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    swipeTyper?.onTouchMove(ev.rawX, ev.rawY)
                    return true
                }
                if (isSwiping) {
                    swipeTyper?.onTouchMove(ev.rawX, ev.rawY)
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
        if (!isSwiping) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> swipeTyper?.onTouchMove(event.rawX, event.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swipeTyper?.onTouchUp()
                isSwiping = false
            }
        }
        return true
    }
}
