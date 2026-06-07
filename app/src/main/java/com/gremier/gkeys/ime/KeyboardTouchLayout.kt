package com.gremier.gkeys.ime

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import com.gremier.gkeys.ime.touch.TouchInputResolver

/**
 * Centralizes tap resolution with proximity correction for letter keys.
 */
class KeyboardTouchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var touchResolver: TouchInputResolver? = null
    var onKeyTap: ((String) -> Unit)? = null
    var onKeyLongPress: ((String) -> Unit)? = null
    var keyLongPressAlts: Map<String, String> = emptyMap()
    var onBackspaceDown: (() -> Unit)? = null
    var onBackspaceUp: (() -> Unit)? = null

    private var backspaceLongPressFired = false
    private var letterLongPressFired = false
    private var deleteRepeatActive = false
    private var pendingAltLabel: String? = null

    private val backspaceLongPressRunnable = Runnable {
        backspaceLongPressFired = true
        deleteRepeatActive = true
        onBackspaceDown?.invoke()
    }

    private val letterLongPressRunnable = Runnable {
        val label = pendingAltLabel ?: return@Runnable
        val alt = keyLongPressAlts[label] ?: return@Runnable
        letterLongPressFired = true
        onKeyLongPress?.invoke(alt)
    }

    private val useCentralTapHandling: Boolean
        get() = touchResolver?.enabled == true && onKeyTap != null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!useCentralTapHandling) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                backspaceLongPressFired = false
                letterLongPressFired = false
                pendingAltLabel = null
                removeCallbacks(backspaceLongPressRunnable)
                removeCallbacks(letterLongPressRunnable)
                maybeScheduleBackspaceLongPress(event.x, event.y)
                maybeScheduleLetterLongPress(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(backspaceLongPressRunnable)
                removeCallbacks(letterLongPressRunnable)
                if (deleteRepeatActive || backspaceLongPressFired) {
                    finishDeleteRepeat()
                    return true
                }
                if (letterLongPressFired) {
                    pendingAltLabel = null
                    return true
                }
                dispatchResolvedTap(event.x, event.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!useCentralTapHandling) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (deleteRepeatActive || backspaceLongPressFired) {
                    removeCallbacks(backspaceLongPressRunnable)
                    finishDeleteRepeat()
                    return true
                }
            }
        }
        return false
    }

    private fun maybeScheduleBackspaceLongPress(x: Float, y: Float) {
        val resolver = touchResolver ?: return
        if (resolver.resolve(x, y)?.label == "⌫") {
            postDelayed(backspaceLongPressRunnable, BACKSPACE_LONG_PRESS_MS)
        }
    }

    private fun maybeScheduleLetterLongPress(x: Float, y: Float) {
        if (onKeyLongPress == null || keyLongPressAlts.isEmpty()) return
        val resolver = touchResolver ?: return
        val label = resolver.resolve(x, y)?.label ?: return
        if (keyLongPressAlts.containsKey(label)) {
            pendingAltLabel = label
            postDelayed(letterLongPressRunnable, KEY_LONG_PRESS_MS)
        }
    }

    private fun finishDeleteRepeat() {
        deleteRepeatActive = false
        backspaceLongPressFired = false
        onBackspaceUp?.invoke()
    }

    private fun dispatchResolvedTap(x: Float, y: Float) {
        val resolver = touchResolver ?: return
        val resolution = resolver.resolve(x, y) ?: return
        resolver.recordTap(x, y, resolution)
        onKeyTap?.invoke(resolution.label)
    }

    companion object {
        private const val BACKSPACE_LONG_PRESS_MS = 380L
        private const val KEY_LONG_PRESS_MS = 380L
    }
}
