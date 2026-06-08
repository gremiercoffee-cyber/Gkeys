package com.gremier.gkeys.ime.bubble

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import com.gremier.gkeys.R
import kotlin.math.abs
import kotlin.math.roundToInt

interface VoiceBubbleListener {
    fun onBubbleTap()
    fun onBubbleSwipeUp()
    fun onShowKeyboardRequested()
    fun onVibrate()
}

enum class VoiceBubbleState { IDLE, RECORDING, PROCESSING }

/**
 * Floating overlay bubble for voice-first dictation when the keyboard is hidden.
 */
class VoiceBubbleController(
    private val context: Context,
    private val listener: VoiceBubbleListener
) {
    companion object {
        private const val BUBBLE_SIZE_DP = 56
        private const val EDGE_MARGIN_DP = 16
        private const val DRAG_THRESHOLD_PX = 8
        private const val SWIPE_UP_THRESHOLD_PX = 80
        private const val LONG_PRESS_MS = 450L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val bubbleSizePx = (BUBBLE_SIZE_DP * density).roundToInt()
    private val edgeMarginPx = (EDGE_MARGIN_DP * density).roundToInt()

    private var rootView: FrameLayout? = null
    private var bubbleBody: FrameLayout? = null
    private var bubbleIcon: ImageView? = null
    private var pulseRing: View? = null
    private var shimmer: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAttached = false
    private var state = VoiceBubbleState.IDLE

    private var posX = -1
    private var posY = -1
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        showContextMenu()
    }

    private var pulseAnimator: AnimatorSet? = null
    private var shimmerRotateAnimator: ObjectAnimator? = null
    private var ringPulseAnimator: ObjectAnimator? = null

    fun canDrawOverlay(): Boolean =
        Settings.canDrawOverlays(context)

    fun show() {
        if (!canDrawOverlay()) return
        if (isAttached) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.voice_bubble, null) as FrameLayout
        rootView = view
        bubbleBody = view.findViewById(R.id.bubble_body)
        bubbleIcon = view.findViewById(R.id.bubble_icon)
        pulseRing = view.findViewById(R.id.bubble_pulse_ring)
        shimmer = view.findViewById(R.id.bubble_shimmer)

        val params = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (posX < 0 || posY < 0) {
                placeDefaultPosition(this)
            } else {
                x = posX
                y = posY
            }
        }
        layoutParams = params

        view.setOnTouchListener { _, event -> handleTouch(event) }

        try {
            windowManager.addView(view, params)
            isAttached = true
            view.alpha = 0f
            view.scaleX = 0.5f
            view.scaleY = 0.5f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            applyStateVisuals()
        } catch (e: Exception) {
            android.util.Log.e("VoiceBubble", "Failed to show bubble", e)
            rootView = null
        }
    }

    fun hide(animate: Boolean = true, onEnd: (() -> Unit)? = null) {
        val view = rootView ?: run {
            onEnd?.invoke()
            return
        }
        stopAnimators()
        if (!animate) {
            removeViewImmediate(view)
            onEnd?.invoke()
            return
        }
        view.animate()
            .alpha(0f)
            .scaleX(0.4f)
            .scaleY(0.4f)
            .setDuration(180)
            .withEndAction {
                removeViewImmediate(view)
                onEnd?.invoke()
            }
            .start()
    }

    fun destroy() {
        hide(animate = false)
    }

    fun setState(newState: VoiceBubbleState) {
        if (state == newState) return
        state = newState
        applyStateVisuals()
    }

    fun animateExpandHandoff(onMidpoint: () -> Unit, onEnd: () -> Unit) {
        val view = rootView ?: run {
            onMidpoint()
            onEnd()
            return
        }
        view.animate()
            .scaleX(1.35f)
            .scaleY(1.35f)
            .alpha(0.6f)
            .setDuration(140)
            .withEndAction {
                onMidpoint()
                view.animate()
                    .alpha(0f)
                    .scaleX(0.2f)
                    .scaleY(0.2f)
                    .setDuration(160)
                    .withEndAction {
                        hide(animate = false)
                        onEnd()
                    }
                    .start()
            }
            .start()
    }

    private fun removeViewImmediate(view: View) {
        if (!isAttached) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        isAttached = false
        rootView = null
        bubbleBody = null
        bubbleIcon = null
        pulseRing = null
        shimmer = null
        layoutParams = null
    }

    private fun placeDefaultPosition(params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val insetBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                windowManager.currentWindowMetrics.windowInsets
                    .getInsets(android.view.WindowInsets.Type.systemBars()).bottom
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
        params.x = metrics.widthPixels - bubbleSizePx - edgeMarginPx
        params.y = metrics.heightPixels - bubbleSizePx - edgeMarginPx - insetBottom
        posX = params.x
        posY = params.y
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val params = layoutParams ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                longPressTriggered = false
                touchStartX = event.rawX
                touchStartY = event.rawY
                dragStartX = params.x.toFloat()
                dragStartY = params.y.toFloat()
                rootView?.removeCallbacks(longPressRunnable)
                rootView?.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                    isDragging = true
                    rootView?.removeCallbacks(longPressRunnable)
                }
                if (isDragging) {
                    params.x = (dragStartX + dx).roundToInt()
                    params.y = (dragStartY + dy).roundToInt()
                    clampToScreen(params)
                    posX = params.x
                    posY = params.y
                    try {
                        windowManager.updateViewLayout(rootView, params)
                    } catch (_: Exception) {
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                rootView?.removeCallbacks(longPressRunnable)
                if (longPressTriggered) return true
                if (isDragging) {
                    snapToNearestEdge(params)
                    posX = params.x
                    posY = params.y
                    try {
                        windowManager.updateViewLayout(rootView, params)
                    } catch (_: Exception) {
                    }
                    return true
                }
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (dy < -SWIPE_UP_THRESHOLD_PX && abs(dy) > abs(dx)) {
                    listener.onVibrate()
                    listener.onBubbleSwipeUp()
                } else if (abs(dx) < DRAG_THRESHOLD_PX * 2 && abs(dy) < DRAG_THRESHOLD_PX * 2) {
                    listener.onVibrate()
                    listener.onBubbleTap()
                }
                return true
            }
        }
        return true
    }

    private fun showContextMenu() {
        val anchor = rootView ?: return
        listener.onVibrate()
        val menu = PopupMenu(context, anchor)
        menu.menu.add(0, 1, 0, "Show Keyboard")
        menu.setOnMenuItemClickListener {
            listener.onShowKeyboardRequested()
            true
        }
        menu.show()
    }

    private fun clampToScreen(params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val maxX = metrics.widthPixels - bubbleSizePx - edgeMarginPx / 2
        val maxY = metrics.heightPixels - bubbleSizePx - edgeMarginPx
        params.x = params.x.coerceIn(edgeMarginPx / 2, maxX)
        params.y = params.y.coerceIn(edgeMarginPx, maxY)
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val centerX = params.x + bubbleSizePx / 2
        val midScreen = metrics.widthPixels / 2
        params.x = if (centerX < midScreen) {
            edgeMarginPx
        } else {
            metrics.widthPixels - bubbleSizePx - edgeMarginPx
        }
        clampToScreen(params)
    }

    private fun applyStateVisuals() {
        val body = bubbleBody ?: return
        stopAnimators()
        when (state) {
            VoiceBubbleState.IDLE -> {
                pulseRing?.visibility = View.GONE
                shimmer?.visibility = View.GONE
                body.setBackgroundResource(R.drawable.voice_bubble_bg)
                rootView?.contentDescription =
                    "Voice dictation bubble. Tap to record. Swipe up for keyboard."
                body.scaleX = 1f
                body.scaleY = 1f
            }
            VoiceBubbleState.RECORDING -> {
                body.setBackgroundResource(R.drawable.voice_bubble_bg_recording)
                pulseRing?.visibility = View.VISIBLE
                shimmer?.visibility = View.VISIBLE
                pulseRing?.alpha = 0.5f
                shimmer?.alpha = 0.7f
                rootView?.contentDescription = "Recording. Tap to stop."
                startRecordingAnimators()
            }
            VoiceBubbleState.PROCESSING -> {
                body.setBackgroundResource(R.drawable.ai_mic_bg_processing)
                pulseRing?.visibility = View.VISIBLE
                shimmer?.visibility = View.VISIBLE
                pulseRing?.alpha = 0.6f
                shimmer?.alpha = 0.85f
                rootView?.contentDescription = "Processing dictation."
                startProcessingAnimators()
            }
        }
    }

    private fun startRecordingAnimators() {
        val body = bubbleBody ?: return
        val pulse = ObjectAnimator.ofPropertyValuesHolder(
            body,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f)
        ).apply {
            duration = 650
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        ringPulseAnimator = ObjectAnimator.ofFloat(pulseRing, View.ALPHA, 0.3f, 1f).apply {
            duration = 650
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        pulseAnimator = AnimatorSet().apply {
            playTogether(pulse)
            start()
        }
        shimmerRotateAnimator = ObjectAnimator.ofFloat(shimmer, View.ROTATION, 0f, 360f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun startProcessingAnimators() {
        val body = bubbleBody ?: return
        val pulse = ObjectAnimator.ofPropertyValuesHolder(
            body,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.1f)
        ).apply {
            duration = 750
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        ringPulseAnimator = ObjectAnimator.ofFloat(pulseRing, View.ALPHA, 0.25f, 1f).apply {
            duration = 750
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        pulseAnimator = AnimatorSet().apply {
            playTogether(pulse)
            start()
        }
        shimmerRotateAnimator = ObjectAnimator.ofFloat(shimmer, View.ROTATION, 0f, 360f).apply {
            duration = 1400
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopAnimators() {
        pulseAnimator?.cancel()
        shimmerRotateAnimator?.cancel()
        ringPulseAnimator?.cancel()
        pulseAnimator = null
        shimmerRotateAnimator = null
        ringPulseAnimator = null
        bubbleBody?.scaleX = 1f
        bubbleBody?.scaleY = 1f
        shimmer?.rotation = 0f
    }
}
