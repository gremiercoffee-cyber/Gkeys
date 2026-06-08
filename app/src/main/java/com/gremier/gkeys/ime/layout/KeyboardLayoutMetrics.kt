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

    const val BASE_PEBBLE_DP = 52
    const val BASE_SPECIAL_DP = 56
    const val BASE_KEY_GAP_DP = 4
    const val KEY_CIRCLE_INSET_DP = 3
    const val BASE_ROW_COUNT = 5

    /** @deprecated Use [BASE_PEBBLE_DP] — keys are circles with equal width and height. */
    const val BASE_PEBBLE_HEIGHT_DP = BASE_PEBBLE_DP
    /** @deprecated Use [BASE_PEBBLE_DP] */
    const val BASE_PEBBLE_WIDTH_DP = BASE_PEBBLE_DP
    const val BASE_SPACE_HEIGHT_DP = BASE_PEBBLE_DP
    const val BASE_SPACE_WIDTH_DP = BASE_PEBBLE_DP

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

    fun profile(preset: String, rightHanded: Boolean, rowCount: Int = BASE_ROW_COUNT): Profile {
        val scale = scaleForPreset(preset)
        val pebble = (BASE_PEBBLE_DP * scale).toInt()
        val gap = BASE_KEY_GAP_DP
        val rowHeight = pebble + gap * 2 + 6
        return Profile(
            pebbleWidthDp = pebble,
            pebbleHeightDp = pebble,
            specialDp = (BASE_SPECIAL_DP * scale).toInt(),
            spaceWidthDp = pebble,
            spaceHeightDp = pebble,
            keyGapDp = gap,
            keyboardHeightDp = rowHeight * rowCount,
            rightHanded = rightHanded
        )
    }

    fun heightForRowCount(profile: Profile, rowCount: Int): Int {
        val perRow = profile.keyboardHeightDp / BASE_ROW_COUNT
        return perRow * rowCount
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
            "0" -> 2.2f
            "SPACE" -> 1.08f
            else -> 1f
        }
    }

    /** Bottom row only: bias spacebar right of visual center. */
    fun bottomRowWeight(label: String, rightHanded: Boolean): Float {
        if (!rightHanded) {
            return when (label) {
                "SPACE" -> 4.9f
                "⌫" -> 1.85f
                "0" -> 2.2f
                "↵", "⇧" -> 1.5f
                else -> 1f
            }
        }
        return when (label) {
            "SPACE" -> 5.2f
            "↵" -> 1.65f
            "⌫" -> 1.9f
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

    /** One-handed mode: keyboard width as a fraction of screen width. */
    const val ONE_HANDED_WIDTH_FRACTION = 0.70f
}
