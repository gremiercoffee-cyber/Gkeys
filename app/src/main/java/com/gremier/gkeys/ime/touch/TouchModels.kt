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

    /** Normalized touch position relative to key center (-1..1 scale). */
    fun relativeOffset(touchX: Float, touchY: Float): Pair<Float, Float> {
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        return ((touchX - centerX) / w) to ((touchY - centerY) / h)
    }
}

data class TouchResolution(
    val label: String,
    val score: Float
)
