package com.gremier.gkeys.ime.layout



/**

 * Gboard-style tile layout: edge-to-edge rounded rectangles in a fixed-height keyboard shell.

 */

object KeyboardLayoutMetrics {



    const val KEY_SIZE_SMALL = "small"

    const val KEY_SIZE_DEFAULT = "default"

    const val KEY_SIZE_LARGE = "large"

    const val KEY_SIZE_EXTRA_LARGE = "extra_large"



    /** Inset on each side of a key cell; gap between adjacent keys is 2× this value. */

    const val KEY_TILE_MARGIN_DP = 2

    /** @deprecated Use [KEY_TILE_MARGIN_DP]; kept for callers that reference total inter-key gap. */
    const val TILE_GAP_DP = KEY_TILE_MARGIN_DP * 2

    const val NUMPAD_TILE_GAP_DP = TILE_GAP_DP



    const val DEFAULT_KEYBOARD_HEIGHT_DP = 220

    const val MIN_KEYBOARD_HEIGHT_DP = 160

    const val MAX_KEYBOARD_HEIGHT_DP = 320



    /** Matches [toolbar_slot] height in keyboard_view.xml (was 46dp before the modern AI bar shell). */
    const val AI_STRIP_HEIGHT_DP = 52

    const val SUGGESTION_STRIP_HEIGHT_DP = 40

    const val SHELL_DIVIDER_DP = 1

    const val SHELL_BOTTOM_PADDING_DP = 6



    const val NUMPAD_COLUMN_COUNT = 5

    /** Numpad cluster width as a fraction of screen width (thumb reach). */
    const val NUMPAD_KEY_AREA_FRACTION = 0.68f



    data class Profile(

        val keyGapDp: Int,

        val textScale: Float,

        val rightHanded: Boolean

    )



    fun scaleForPreset(preset: String): Float = when (preset) {

        KEY_SIZE_SMALL -> 0.88f

        KEY_SIZE_LARGE -> 1.06f

        KEY_SIZE_EXTRA_LARGE -> 1.14f

        else -> 1.0f

    }

    fun effectiveKeyboardHeightDp(baseHeightDp: Int, keySizePreset: String): Int =
        clampKeyboardHeightDp(kotlin.math.round(baseHeightDp * scaleForPreset(keySizePreset)).toInt())



    fun profile(preset: String, rightHanded: Boolean): Profile = Profile(

        keyGapDp = KEY_TILE_MARGIN_DP,

        textScale = scaleForPreset(preset),

        rightHanded = rightHanded

    )



    fun clampKeyboardHeightDp(heightDp: Int): Int =

        heightDp.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)



    fun shellHeightDp(keyboardHeightDp: Int): Int =
        AI_STRIP_HEIGHT_DP + SHELL_DIVIDER_DP +
            clampKeyboardHeightDp(keyboardHeightDp) + SHELL_BOTTOM_PADDING_DP



    /** Bottom row: wide space bar; punctuation on the right kept compact to reduce space-bar mis-taps. */

    fun bottomRowWeight(label: String, rightHanded: Boolean): Float {

        if (!rightHanded) {

            return when (label) {

                "SPACE" -> 5.85f

                "🌐" -> 0.78f

                "," -> 0.88f

                "!" -> 0.62f

                "." -> 0.62f

                "?" -> 0.65f

                "?123", "ABC", "NUMPAD_BACK" -> 0.92f

                "↵" -> 0.92f

                else -> 1f

            }

        }

        return when (label) {

            "SPACE" -> 6.05f

            "🌐" -> 0.72f

            "," -> 0.85f

            "!" -> 0.60f

            "." -> 0.60f

            "?" -> 0.62f

            "?123", "ABC", "NUMPAD_BACK" -> 0.88f

            "↵" -> 0.90f

            else -> 1f

        }

    }



    /** Numpad bottom row: wide space bar, compact action keys. */

    fun numpadBottomRowWeight(label: String): Float = when (label) {

        "SPACE" -> 3.45f

        "ABC", "NUMPAD_BACK" -> 0.85f

        "⌫" -> 1.28f

        ".", "?" -> 0.62f

        "↵" -> 0.85f

        else -> 1f

    }

    /** Per-column weight for numpad rows (digits vs operator columns). */
    fun numpadColumnWeight(colIndex: Int, label: String, rowIndex: Int, totalRows: Int): Float {
        if (isBottomRowSpecialRow(rowIndex, totalRows)) {
            return numpadBottomRowWeight(label)
        }
        return when (colIndex) {
            0, 4 -> 0.72f
            else -> 0.88f
        }
    }



    fun rowKeyWeight(

        label: String,

        rowIndex: Int,

        totalRows: Int,

        rightHanded: Boolean,

        isNumpadMode: Boolean

    ): Float {

        if (!isBottomRowSpecialRow(rowIndex, totalRows)) return 1f

        return if (isNumpadMode) numpadBottomRowWeight(label)

        else bottomRowWeight(label, rightHanded)

    }



    /** Letter/symbol rows: backspace and shift get extra width for easier hits. */
    fun standardRowKeyWeight(label: String): Float = when (label) {
        "⌫" -> 1.42f
        "⇧" -> 1.12f
        "v", "b" -> 0.94f
        else -> 1f
    }

    fun isBottomRowSpecialRow(rowIndex: Int, totalRows: Int): Boolean =
        rowIndex == totalRows - 1



    /** Horizontal inset to shift keyboard slightly right for right-thumb arc. */

    fun keyboardShiftRightDp(rightHanded: Boolean): Int = if (rightHanded) 10 else 0



    fun keyboardPaddingStartDp(rightHanded: Boolean): Int = if (rightHanded) 4 else 2

    fun keyboardPaddingEndDp(rightHanded: Boolean): Int = if (rightHanded) 2 else 2



    /** One-handed: key cluster width; background stays full screen. */

    const val DEFAULT_ONE_HANDED_KEY_AREA_FRACTION = 0.61f

    const val MIN_ONE_HANDED_KEY_AREA_FRACTION = 0.45f

    const val MAX_ONE_HANDED_KEY_AREA_FRACTION = 0.78f

    /** @deprecated Use [DEFAULT_ONE_HANDED_KEY_AREA_FRACTION] */
    const val ONE_HANDED_KEY_AREA_FRACTION = DEFAULT_ONE_HANDED_KEY_AREA_FRACTION

    const val ONE_HANDED_KEY_GAP_DP = KEY_TILE_MARGIN_DP

    const val EMOJI_COLUMNS = 9
    const val EMOJI_ROW_HEIGHT_DP = 36
    const val EMOJI_HEADER_ROW_DP = 36
    const val EMOJI_CATEGORY_HEADER_DP = 22
    const val EMOJI_TEXT_SP = 18f

}

