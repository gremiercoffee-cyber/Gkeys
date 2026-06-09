package com.gremier.gkeys.ime.suggestions

import android.os.Handler
import android.os.Looper

/**
 * Gboard-style suggestion bar visibility: show on first keypress, hide after idle/submit/focus loss.
 */
class SuggestionVisibilityController(
    private val idleTimeoutMs: Long = 1000L,
    private val onVisibilityChanged: (visible: Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var visible = false

    private val hideRunnable = Runnable {
        if (visible) {
            visible = false
            onVisibilityChanged(false)
        }
    }

    fun isVisible(): Boolean = visible

    /** Call when the user presses a letter key while suggestions are supported. */
    fun onTypingKey() {
        handler.removeCallbacks(hideRunnable)
        if (!visible) {
            visible = true
            onVisibilityChanged(true)
        }
        handler.postDelayed(hideRunnable, idleTimeoutMs)
    }

    /** Call on space, enter, focus loss, or mode changes that end typing. */
    fun hideImmediately() {
        handler.removeCallbacks(hideRunnable)
        if (visible) {
            visible = false
            onVisibilityChanged(false)
        }
    }

    fun release() {
        handler.removeCallbacks(hideRunnable)
        visible = false
    }
}
