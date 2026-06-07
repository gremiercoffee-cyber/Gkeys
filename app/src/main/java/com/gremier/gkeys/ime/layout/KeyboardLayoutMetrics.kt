package com.gremier.gkeys.ime.layout

/**
 * Key sizing and right-handed one-thumb layout tuning.
 * Baseline pebble height 52dp (~20–22mm thumb tip); widths slightly wider than tall.
 */
object KeyboardLayoutMetrics {

    const val KEY_SIZE_SMALL = "small"
    const val KEY_SIZE_DEFAULT = "default"
    const val KEY_SIZE_LARGE = "large"
    const val KEY_SIZE_EXTRA_LARGE = "extra_large"

    /** Default row pebble height in dp at 1.0 scale. */
    const val BASE_PEBBLE_HEIGHT_DP = 52
    const val BASE_PEBBLE_WIDTH_DP = 54
    const val BASE_SPECIAL_DP = 56
    const val BASE_SPACE_HEIGHT_DP = 44
    const val BASE_SPACE_WIDTH_DP = 132
    const val BASE_KEY_GAP_DP = 2
    const val BASE_ROW_COUNT = 5

    data class Profile(
        val pebbleWidthDp: Int,
        val pebbleHeightDp: Int,
        val specialDp: Int,
        val spaceWidthDp: Int,
        val spaceHeightDp: Int,
        val keyGapDp: Int,
        val keyboardHeightDp: Int,
        val rightHanded: Boolean
    )

    fun scaleForPreset(preset: String): Float = when (preset) {
        KEY_SIZE_SMALL -> 0.90f
        KEY_SIZE_LARGE -> 1.06f
        KEY_SIZE_EXTRA_LARGE -> 1.12f
        else -> 1.0f
    }

    fun profile(preset: String, rightHanded: Boolean): Profile {
        val scale = scaleForPreset(preset)
        val pebbleH = (BASE_PEBBLE_HEIGHT_DP * scale).toInt()
        val pebbleW = (BASE_PEBBLE_WIDTH_DP * scale).toInt()
        val gap = if (rightHanded) BASE_KEY_GAP_DP + 1 else BASE_KEY_GAP_DP
        val rowHeight = pebbleH + gap * 2 + 4
        return Profile(
            pebbleWidthDp = pebbleW,
            pebbleHeightDp = pebbleH,
            specialDp = (BASE_SPECIAL_DP * scale).toInt(),
            spaceWidthDp = (BASE_SPACE_WIDTH_DP * scale).toInt(),
            spaceHeightDp = (BASE_SPACE_HEIGHT_DP * scale).toInt(),
            keyGapDp = gap,
            keyboardHeightDp = rowHeight * BASE_ROW_COUNT,
            rightHanded = rightHanded
        )
    }

    /** Horizontal cell weight multiplier for right-handed reach. */
    fun weightMultiplier(label: String, rightHanded: Boolean): Float {
        if (!rightHanded) return 1f
        return when (label) {
            "p", "l", "⌫", "↵" -> 1.14f
            "o", "i", "m", "n", "." -> 1.06f
            "q", "a", "z", "⇧" -> 0.86f
            "w", "s", "x" -> 0.92f
            "?123", "ABC", "🌐", "," -> 0.88f
            "SPACE" -> 1.08f
            else -> 1f
        }
    }

    /** Bottom row only: bias spacebar right of visual center. */
    fun bottomRowWeight(label: String, rightHanded: Boolean): Float {
        if (!rightHanded) {
            return when (label) {
                "SPACE" -> 4f
                "⌫", "↵", "⇧" -> 1.5f
                else -> 1f
            }
        }
        return when (label) {
            "SPACE" -> 4.35f
            "↵" -> 1.65f
            "⌫" -> 1.55f
            "." -> 1.08f
            "?123", "ABC", "🌐" -> 0.82f
            "," -> 0.90f
            else -> 1f
        }
    }

    fun isBottomRowSpecialRow(rowIndex: Int, totalRows: Int): Boolean =
        rowIndex == totalRows - 1

    /** Horizontal inset to shift keyboard slightly right for right-thumb arc. */
    fun keyboardShiftRightDp(rightHanded: Boolean): Int = if (rightHanded) 10 else 0

    fun keyboardPaddingStartDp(rightHanded: Boolean): Int = if (rightHanded) 6 else 4
    fun keyboardPaddingEndDp(rightHanded: Boolean): Int = if (rightHanded) 2 else 4
}
