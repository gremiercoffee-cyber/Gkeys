package com.gremier.gkeys.ime

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/** Subtle moving highlight on the AI toolbar shell. */
object AiBarShimmer {
    private val animators = mutableMapOf<View, ObjectAnimator>()

    fun attach(shimmerView: View) {
        shimmerView.post {
            if (shimmerView.width <= 0) return@post
            stop(shimmerView)
            shimmerView.visibility = View.VISIBLE
            shimmerView.alpha = 0.55f
            val travel = shimmerView.width.toFloat() * 1.4f
            val anim = ObjectAnimator.ofFloat(shimmerView, View.TRANSLATION_X, -travel, travel).apply {
                duration = 2800L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                start()
            }
            animators[shimmerView] = anim
        }
    }

    fun stop(shimmerView: View) {
        animators.remove(shimmerView)?.cancel()
        shimmerView.translationX = 0f
        shimmerView.visibility = View.GONE
    }
}
