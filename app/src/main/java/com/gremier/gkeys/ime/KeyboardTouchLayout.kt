package com.gremier.gkeys.ime



import android.content.Context

import android.util.AttributeSet

import android.view.MotionEvent

import android.widget.LinearLayout

import com.gremier.gkeys.ime.touch.TouchInputResolver
import com.gremier.gkeys.ime.touch.TouchResolution



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

    private var tappedOnDown = false



    private val backspaceLongPressRunnable = Runnable {

        backspaceLongPressFired = true

        deleteRepeatActive = true

        onBackspaceDown?.invoke()

    }



    private val letterLongPressRunnable = Runnable {

        val label = pendingAltLabel ?: return@Runnable

        val alt = keyLongPressAlts[label] ?: keyLongPressAlts[label.lowercase()] ?: return@Runnable

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

                tappedOnDown = false

                pendingAltLabel = null

                removeCallbacks(backspaceLongPressRunnable)

                removeCallbacks(letterLongPressRunnable)

                maybeScheduleBackspaceLongPress(event.x, event.y)

                maybeScheduleLetterLongPress(event.x, event.y)

                maybeFireKeyOnDown(event.x, event.y)

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

                    tappedOnDown = false

                    return true

                }

                if (letterLongPressFired) {

                    pendingAltLabel = null

                    tappedOnDown = false

                    return true

                }

                if (tappedOnDown) {

                    tappedOnDown = false

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



    private fun maybeFireKeyOnDown(x: Float, y: Float) {

        val resolver = touchResolver ?: return

        val resolution = resolver.resolve(x, y) ?: return

        val label = resolution.label

        if (hasLongPressAlt(label)) return

        dispatchTap(resolution, x, y)

        tappedOnDown = true

    }



    private fun hasLongPressAlt(label: String): Boolean =

        keyLongPressAlts.containsKey(label) || keyLongPressAlts.containsKey(label.lowercase())



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

        if (hasLongPressAlt(label)) {

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

        dispatchTap(resolution, x, y)

    }



    /** Commit the key immediately; record letter taps synchronously so corrections stay ordered. */
    private fun dispatchTap(resolution: TouchResolution, x: Float, y: Float) {
        val resolver = touchResolver ?: return
        onKeyTap?.invoke(resolution.label)
        if (resolution.label.length == 1 && resolution.label[0].isLetter()) {
            resolver.recordTap(x, y, resolution)
        }
    }



    companion object {

        private const val BACKSPACE_LONG_PRESS_MS = 380L

        private const val KEY_LONG_PRESS_MS = 380L

    }

}


