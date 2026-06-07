package com.gremier.gkeys.ime.touch

/**
 * Per-row touch-position correction inspired by AOSP [TouchPositionCorrection].
 * Shifts the effective touch point for thumb reach and finger occlusion.
 */
object TouchPositionCorrection {

    /** Fraction of key height to shift touch Y upward (negative = up). */
    private val ROW_Y_SHIFT = floatArrayOf(
        -0.04f,
        -0.07f,
        -0.09f,
        -0.11f,
        -0.14f
    )

    /** Extra upward shift when right-handed (thumb from lower-right of key face). */
    private val ROW_Y_SHIFT_RIGHT = floatArrayOf(
        -0.02f,
        -0.03f,
        -0.04f,
        -0.05f,
        -0.06f
    )

    /** Rightward shift as fraction of key width (thumb approaches from bottom-left). */
    private const val RIGHT_HAND_X_SHIFT = 0.055f

    const val BASE_RADIUS_FRACTION = 0.52f
    const val SEARCH_DISTANCE_FACTOR = 1.35f

    fun yShiftForRow(row: Int, keyHeight: Float, rightHanded: Boolean = false): Float {
        if (row < 0) return 0f
        val idx = row.coerceAtMost(ROW_Y_SHIFT.lastIndex)
        var shift = ROW_Y_SHIFT[idx]
        if (rightHanded) shift += ROW_Y_SHIFT_RIGHT[idx]
        return shift * keyHeight
    }

    fun xShiftForKey(keyWidth: Float, rightHanded: Boolean): Float {
        if (!rightHanded) return 0f
        return RIGHT_HAND_X_SHIFT * keyWidth
    }
}
