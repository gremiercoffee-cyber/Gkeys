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

import android.widget.FrameLayout

import android.widget.ImageView

import com.gremier.gkeys.R

import kotlin.math.abs

import kotlin.math.roundToInt



interface VoiceBubbleListener {

    fun onBubbleTap()

    fun onBubbleSwipeUp()

    fun onBubbleTranslateHoldStart()

    fun onBubbleTranslateHoldEnd(cancelled: Boolean)

    fun onVibrate()

}



enum class VoiceBubbleState { IDLE, RECORDING, PROCESSING }



class VoiceBubbleController(

    private val context: Context,

    private val listener: VoiceBubbleListener

) {

    companion object {

        private const val BUBBLE_SIZE_DP = 72

        private const val EDGE_MARGIN_DP = 16

        private const val DRAG_THRESHOLD_PX = 10

        private const val SWIPE_UP_THRESHOLD_PX = 36

        private const val TRANSLATE_HOLD_MS = 380L

    }



    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density = context.resources.displayMetrics.density

    private val bubbleSizePx = (BUBBLE_SIZE_DP * density).roundToInt()

    private val edgeMarginPx = (EDGE_MARGIN_DP * density).roundToInt()



    private var rootView: FrameLayout? = null

    private var bubbleBody: FrameLayout? = null

    private var bubbleIcon: ImageView? = null

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

    private var translateHoldActive = false

    private val translateHoldRunnable = Runnable {

        if (!isAttached) return@Runnable

        translateHoldActive = true

        listener.onVibrate()

        listener.onBubbleTranslateHoldStart()

        applyStateVisuals()

    }



    private var pulseAnimator: AnimatorSet? = null



    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    val isShowing: Boolean get() = isAttached



    fun show() {

        if (!canDrawOverlay()) return

        if (isAttached) return



        val view = LayoutInflater.from(context).inflate(R.layout.voice_bubble, null) as FrameLayout

        rootView = view

        bubbleBody = view.findViewById(R.id.bubble_body)

        bubbleIcon = view.findViewById(R.id.bubble_icon)



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

            isAttached = false

        }

    }



    fun hide(animate: Boolean = true, onEnd: (() -> Unit)? = null) {

        val view = rootView

        if (view == null || !isAttached) {

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

        if (!isAttached || state == newState) return

        state = newState

        applyStateVisuals()

    }



    fun animateExpandHandoff(onMidpoint: () -> Unit, onEnd: () -> Unit) {

        val view = rootView

        if (view == null || !isAttached) {

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

        stopAnimators()

        try {

            windowManager.removeView(view)

        } catch (_: Exception) {

        }

        isAttached = false

        rootView = null

        bubbleBody = null

        bubbleIcon = null

        layoutParams = null

        state = VoiceBubbleState.IDLE

        translateHoldActive = false

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

                translateHoldActive = false

                touchStartX = event.rawX

                touchStartY = event.rawY

                dragStartX = params.x.toFloat()

                dragStartY = params.y.toFloat()

                rootView?.removeCallbacks(translateHoldRunnable)

                rootView?.postDelayed(translateHoldRunnable, TRANSLATE_HOLD_MS)

                return true

            }

            MotionEvent.ACTION_MOVE -> {

                if (translateHoldActive || state == VoiceBubbleState.RECORDING) {

                    return true

                }

                val dx = event.rawX - touchStartX

                val dy = event.rawY - touchStartY

                if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {

                    isDragging = true

                    rootView?.removeCallbacks(translateHoldRunnable)

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

                rootView?.removeCallbacks(translateHoldRunnable)

                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL

                if (translateHoldActive) {

                    translateHoldActive = false

                    listener.onBubbleTranslateHoldEnd(cancelled)

                    applyStateVisuals()

                    return true

                }

                if (isDragging) {

                    snapToNearestEdge(params)

                    posX = params.x

                    posY = params.y

                    try {

                        windowManager.updateViewLayout(rootView, params)

                    } catch (_: Exception) {

                    }

                    isDragging = false

                    return true

                }

                val dx = event.rawX - touchStartX

                val dy = event.rawY - touchStartY

                if (dy < -SWIPE_UP_THRESHOLD_PX && abs(dy) > abs(dx) * 1.2f) {

                    listener.onVibrate()

                    listener.onBubbleSwipeUp()

                    return true

                }

                if (abs(dx) < DRAG_THRESHOLD_PX * 2 && abs(dy) < DRAG_THRESHOLD_PX * 2) {

                    listener.onVibrate()

                    listener.onBubbleTap()

                }

                return true

            }

        }

        return true

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

        if (!isAttached) return

        val body = bubbleBody ?: return

        stopAnimators()

        bubbleIcon?.alpha = 1f

        body.setBackgroundResource(R.drawable.voice_bubble_icon_bg)

        body.scaleX = 1f

        body.scaleY = 1f

        when (state) {

            VoiceBubbleState.IDLE -> {

                rootView?.contentDescription =

                    "Gkeys voice bubble. Tap to dictate. Hold to translate. Tap the text field for keyboard."

            }

            VoiceBubbleState.RECORDING -> {

                rootView?.contentDescription = if (translateHoldActive) {

                    "Translating. Release to finish."

                } else {

                    "Listening. Tap to stop."

                }

                startPulseAnimators()

            }

            VoiceBubbleState.PROCESSING -> {

                rootView?.contentDescription = "Processing dictation."

                startPulseAnimators(slower = true)

            }

        }

    }



    private fun startPulseAnimators(slower: Boolean = false) {

        val body = bubbleBody ?: return

        val duration = if (slower) 900L else 700L

        val bodyPulse = ObjectAnimator.ofPropertyValuesHolder(

            body,

            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.1f),

            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.1f),

            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.82f)

        ).apply {

            this.duration = duration

            repeatMode = ObjectAnimator.REVERSE

            repeatCount = ObjectAnimator.INFINITE

            interpolator = AccelerateDecelerateInterpolator()

        }

        pulseAnimator = AnimatorSet().apply {

            playTogether(bodyPulse)

            start()

        }

    }



    private fun stopAnimators() {

        pulseAnimator?.cancel()

        pulseAnimator = null

        bubbleBody?.scaleX = 1f

        bubbleBody?.scaleY = 1f

        bubbleBody?.alpha = 1f

    }

}

