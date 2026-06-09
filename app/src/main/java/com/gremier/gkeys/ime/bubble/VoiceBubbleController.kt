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

        private const val BUBBLE_ALPHA_IDLE = 0.55f
        private const val BUBBLE_ALPHA_RECORDING = 0.82f
        private const val BUBBLE_ALPHA_PROCESSING = 0.72f

        private const val BUBBLE_WIDTH_DP = 40
        private const val BUBBLE_HEIGHT_DP = 104
        private const val EDGE_MARGIN_Y_DP = 12

        private const val DRAG_THRESHOLD_PX = 10

        private const val SWIPE_UP_THRESHOLD_PX = 36

        private const val TRANSLATE_HOLD_MS = 380L

    }



    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density = context.resources.displayMetrics.density

    private val bubbleWidthPx = (BUBBLE_WIDTH_DP * density).roundToInt()
    private val bubbleHeightPx = (BUBBLE_HEIGHT_DP * density).roundToInt()
    private val edgeMarginYPx = (EDGE_MARGIN_Y_DP * density).roundToInt()



    private var rootView: FrameLayout? = null

    private var bubbleBody: FrameLayout? = null

    private var bubbleIcon: ImageView? = null

    private var bubbleAiGlow: View? = null

    private var bubbleAiShimmer: View? = null

    private var layoutParams: WindowManager.LayoutParams? = null

    private var isAttached = false

    private var state = VoiceBubbleState.IDLE



    private var dockedOnRight = true

    private var posX = -1

    private var posY = -1

    private var dragStartX = 0f

    private var dragStartY = 0f

    private var touchStartX = 0f

    private var touchStartY = 0f

    private var isDragging = false

    private var translateHoldActive = false

    private var translateHoldPending = false

    private val translateHoldRunnable = Runnable {

        if (!isAttached) return@Runnable

        translateHoldPending = false

        translateHoldActive = true

        listener.onVibrate()

        listener.onBubbleTranslateHoldStart()

        applyStateVisuals()

    }



    private var pulseAnimator: AnimatorSet? = null

    private var processingAnimator: AnimatorSet? = null

    private var shimmerRotateAnimator: ObjectAnimator? = null



    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    val isShowing: Boolean get() = isAttached



    fun show() {

        if (!canDrawOverlay()) return

        if (isAttached) return



        val view = LayoutInflater.from(context).inflate(R.layout.voice_bubble, null) as FrameLayout

        rootView = view

        bubbleBody = view.findViewById(R.id.bubble_body)

        bubbleIcon = view.findViewById(R.id.bubble_icon)

        bubbleAiGlow = view.findViewById(R.id.bubble_ai_glow)

        bubbleAiShimmer = view.findViewById(R.id.bubble_ai_shimmer)



        val params = WindowManager.LayoutParams(

            bubbleWidthPx,

            bubbleHeightPx,

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

                .alpha(BUBBLE_ALPHA_IDLE)

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



    fun setKeepScreenOn(enabled: Boolean) {
        val view = rootView ?: return
        val params = layoutParams ?: return
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
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

        if (!isAttached) return

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

        bubbleAiGlow = null

        bubbleAiShimmer = null

        layoutParams = null

        state = VoiceBubbleState.IDLE

        translateHoldActive = false

        translateHoldPending = false

        dockedOnRight = true

    }



    private fun placeDefaultPosition(params: WindowManager.LayoutParams) {

        val metrics = context.resources.displayMetrics

        val (insetTop, insetBottom) = systemBarInsets()

        dockedOnRight = true

        params.x = metrics.widthPixels - bubbleWidthPx

        val usableHeight = metrics.heightPixels - insetTop - insetBottom

        params.y = insetTop + ((usableHeight - bubbleHeightPx) / 2f).roundToInt()

        posX = params.x

        posY = params.y

    }



    private fun systemBarInsets(): Pair<Int, Int> {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            return try {

                val insets = windowManager.currentWindowMetrics.windowInsets

                    .getInsets(android.view.WindowInsets.Type.systemBars())

                insets.top to insets.bottom

            } catch (_: Exception) {

                0 to 0

            }

        }

        return 0 to 0

    }



    private fun idleBackgroundRes(): Int =

        if (dockedOnRight) R.drawable.voice_bubble_icon_bg else R.drawable.voice_bubble_icon_bg_left



    private fun processingBackgroundRes(): Int =

        if (dockedOnRight) R.drawable.voice_bubble_processing_bg

        else R.drawable.voice_bubble_processing_bg_left



    private fun handleTouch(event: MotionEvent): Boolean {

        val params = layoutParams ?: return true

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                isDragging = false

                translateHoldActive = false

                translateHoldPending = true

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

                if (translateHoldPending) {

                    // Allow small finger movement while waiting for translate hold.

                    if (abs(dx) > DRAG_THRESHOLD_PX * 4 || abs(dy) > DRAG_THRESHOLD_PX * 4) {

                        rootView?.removeCallbacks(translateHoldRunnable)

                        translateHoldPending = false

                    } else {

                        return true

                    }

                }

                if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {

                    isDragging = true

                    rootView?.removeCallbacks(translateHoldRunnable)

                    translateHoldPending = false

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

                translateHoldPending = false

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

        val (insetTop, insetBottom) = systemBarInsets()

        val maxX = metrics.widthPixels - bubbleWidthPx

        val maxY = metrics.heightPixels - bubbleHeightPx - insetBottom - edgeMarginYPx

        params.x = params.x.coerceIn(0, maxX)

        params.y = params.y.coerceIn(insetTop + edgeMarginYPx, maxY.coerceAtLeast(insetTop + edgeMarginYPx))

    }



    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {

        val metrics = context.resources.displayMetrics

        val centerX = params.x + bubbleWidthPx / 2

        val midScreen = metrics.widthPixels / 2

        dockedOnRight = centerX >= midScreen

        params.x = if (dockedOnRight) {

            metrics.widthPixels - bubbleWidthPx

        } else {

            0

        }

        clampToScreen(params)

        applyStateVisuals()

    }



    private fun applyStateVisuals() {

        if (!isAttached) return

        val body = bubbleBody ?: return

        val icon = bubbleIcon

        stopAnimators()

        icon?.alpha = 1f

        icon?.rotation = 0f

        body.setBackgroundResource(idleBackgroundRes())

        body.scaleX = 1f

        body.scaleY = 1f

        body.alpha = 1f

        hideProcessingLayers()

        when (state) {

            VoiceBubbleState.IDLE -> {

                rootView?.contentDescription =

                    "Gkeys voice bubble. Tap to dictate. Hold to translate. Tap the text field for keyboard."

                rootView?.alpha = BUBBLE_ALPHA_IDLE

            }

            VoiceBubbleState.RECORDING -> {

                rootView?.contentDescription = if (translateHoldActive) {

                    "Translating. Release to finish."

                } else {

                    "Listening. Tap to stop."

                }

                rootView?.alpha = BUBBLE_ALPHA_RECORDING

                startPulseAnimators()

            }

            VoiceBubbleState.PROCESSING -> {

                rootView?.contentDescription = "Processing dictation."

                rootView?.alpha = BUBBLE_ALPHA_PROCESSING

                startProcessingAnimators()

            }

        }

    }



    private fun hideProcessingLayers() {

        bubbleAiGlow?.visibility = View.GONE

        bubbleAiShimmer?.visibility = View.GONE

        bubbleAiShimmer?.rotation = 0f

    }



    private fun startProcessingAnimators() {

        val body = bubbleBody ?: return

        val glow = bubbleAiGlow ?: return

        val shimmer = bubbleAiShimmer ?: return

        val icon = bubbleIcon ?: return

        body.setBackgroundResource(processingBackgroundRes())

        glow.visibility = View.VISIBLE

        shimmer.visibility = View.VISIBLE

        glow.alpha = 0.4f

        shimmer.alpha = 0.55f

        val glowPulse = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.25f, 1f).apply {

            duration = 750

            repeatMode = ObjectAnimator.REVERSE

            repeatCount = ObjectAnimator.INFINITE

            interpolator = AccelerateDecelerateInterpolator()

        }

        val containerPulse = ObjectAnimator.ofPropertyValuesHolder(

            body,

            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),

            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f)

        ).apply {

            duration = 750

            repeatMode = ObjectAnimator.REVERSE

            repeatCount = ObjectAnimator.INFINITE

            interpolator = AccelerateDecelerateInterpolator()

        }

        processingAnimator = AnimatorSet().apply {

            playTogether(glowPulse, containerPulse)

            start()

        }

        shimmerRotateAnimator = ObjectAnimator.ofFloat(shimmer, View.ROTATION, 0f, 360f).apply {

            duration = 1400

            repeatCount = ObjectAnimator.INFINITE

            interpolator = LinearInterpolator()

            start()

        }

        ObjectAnimator.ofFloat(icon, View.ALPHA, 0.65f, 1f).apply {

            duration = 600

            repeatMode = ObjectAnimator.REVERSE

            repeatCount = ObjectAnimator.INFINITE

            interpolator = AccelerateDecelerateInterpolator()

            start()

        }

    }



    private fun startPulseAnimators(slower: Boolean = false) {

        val body = bubbleBody ?: return

        val duration = if (slower) 900L else 700L

        val bodyPulse = ObjectAnimator.ofPropertyValuesHolder(

            body,

            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),

            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f),

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

        processingAnimator?.cancel()

        processingAnimator = null

        shimmerRotateAnimator?.cancel()

        shimmerRotateAnimator = null

        bubbleBody?.scaleX = 1f

        bubbleBody?.scaleY = 1f

        bubbleBody?.alpha = 1f

        hideProcessingLayers()

    }

}

