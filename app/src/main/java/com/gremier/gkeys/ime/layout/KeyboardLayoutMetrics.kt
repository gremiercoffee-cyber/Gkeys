package com.gremier.gkeys.ime.layout



/**

 * Gboard-style tile layout: edge-to-edge rounded rectangles in a fixed-height keyboard shell.

 */

object KeyboardLayoutMetrics {



    const val KEY_SIZE_SMALL = "small"

    const val KEY_SIZE_DEFAULT = "default"

    const val KEY_SIZE_LARGE = "large"

    const val KEY_SIZE_EXTRA_LARGE = "extra_large"



    /** Hairline gap between tiles; keyboard background shows through. */

    const val TILE_GAP_DP = 1

    const val NUMPAD_TILE_GAP_DP = 0



    const val DEFAULT_KEYBOARD_HEIGHT_DP = 220

    const val MIN_KEYBOARD_HEIGHT_DP = 160

    const val MAX_KEYBOARD_HEIGHT_DP = 320



    const val AI_STRIP_HEIGHT_DP = 40

    const val SHELL_DIVIDER_DP = 1

    const val SHELL_BOTTOM_PADDING_DP = 6



    const val NUMPAD_COLUMN_COUNT = 5



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



    fun profile(preset: String, rightHanded: Boolean): Profile = Profile(

        keyGapDp = TILE_GAP_DP,

        textScale = scaleForPreset(preset),

        rightHanded = rightHanded

    )



    fun clampKeyboardHeightDp(heightDp: Int): Int =

        heightDp.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)



    /** Total IME key-shell height: toolbar + divider + keys + bottom padding. */

    fun shellHeightDp(keyboardHeightDp: Int): Int =

        AI_STRIP_HEIGHT_DP + SHELL_DIVIDER_DP + clampKeyboardHeightDp(keyboardHeightDp) + SHELL_BOTTOM_PADDING_DP



    /** Bottom row: space dominates; punctuation and globe are clearly secondary. */

    fun bottomRowWeight(label: String, rightHanded: Boolean): Float {

        if (!rightHanded) {

            return when (label) {

                "SPACE" -> 6.8f

                "🌐" -> 0.55f

                ",", ".", "?" -> 0.72f

                "?123", "ABC", "NUMPAD_BACK" -> 0.85f

                "↵" -> 1.2f

                else -> 1f

            }

        }

        return when (label) {

            "SPACE" -> 7.0f

            "🌐" -> 0.5f

            ",", ".", "?" -> 0.68f

            "?123", "ABC", "NUMPAD_BACK" -> 0.82f

            "↵" -> 1.15f

            else -> 1f

        }

    }



    /** Numpad bottom row: wide space bar, compact action keys. */

    fun numpadBottomRowWeight(label: String): Float = when (label) {

        "SPACE" -> 3.6f

        "ABC", "NUMPAD_BACK" -> 0.9f

        "⌫" -> 1.1f

        ".", "?" -> 0.75f

        "↵" -> 1.05f

        else -> 1f

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



    fun isBottomRowSpecialRow(rowIndex: Int, totalRows: Int): Boolean =

        rowIndex == totalRows - 1



    /** Horizontal inset to shift keyboard slightly right for right-thumb arc. */

    fun keyboardShiftRightDp(rightHanded: Boolean): Int = if (rightHanded) 10 else 0



    fun keyboardPaddingStartDp(rightHanded: Boolean): Int = if (rightHanded) 4 else 2

    fun keyboardPaddingEndDp(rightHanded: Boolean): Int = if (rightHanded) 2 else 2



    /** One-handed: key cluster width; background stays full screen. */

    const val ONE_HANDED_KEY_AREA_FRACTION = 0.92f

    const val ONE_HANDED_KEY_GAP_DP = TILE_GAP_DP

    const val EMOJI_COLUMNS = 9
    const val EMOJI_ROW_HEIGHT_DP = 30
    const val EMOJI_HEADER_ROW_DP = 36
    const val EMOJI_CATEGORY_HEADER_DP = 22
    const val EMOJI_TEXT_SP = 15f

}

