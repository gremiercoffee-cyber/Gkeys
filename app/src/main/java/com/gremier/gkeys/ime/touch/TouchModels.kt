package com.gremier.gkeys.ime.touch

/**
 * A tappable key with geometry in keyboard-container coordinates.
 */
data class KeyHitTarget(
    val label: String,
    val char: Char?,
    val row: Int,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val sweetSpotX: Float,
    val sweetSpotY: Float
) {
    val hitRadius: Float
        get() = 0.5f * kotlin.math.hypot(width, height)
}

data class TouchResolution(
    val label: String,
    val score: Float
)
