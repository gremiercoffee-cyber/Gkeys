package com.gremier.gkeys.ime

/** IDs for customizable AI toolbar buttons (primary and secondary rows). */
object AiBarLayout {
    const val PAGE = "page"
    const val WAND = "wand"
    const val POLISH = "polish"
    const val RAW_POLISH = "raw_polish"
    const val CLEAR_ALL = "clear_all"
    const val CLIPBOARD = "clipboard"
    const val LIVE = "live"
    const val MIC = "mic"
    const val NUMPAD = "numpad"

    const val BACK = "back"
    const val SETTINGS = "settings"
    const val UNDO = "undo"
    const val DELETE_FORWARD = "delete_forward"
    const val SELECT_ALL = "select_all"
    const val BUBBLE = "bubble"

    const val SPACER_TAG = "ai_bar_spacer"

    val DEFAULT_PRIMARY_ORDER = listOf(
        PAGE, WAND, POLISH, RAW_POLISH, DELETE_FORWARD, CLEAR_ALL, CLIPBOARD, LIVE, MIC, NUMPAD,
    )
    val DEFAULT_SECONDARY_ORDER = listOf(BACK, SETTINGS, UNDO, SELECT_ALL, BUBBLE)

    /** Ensures polish controls sit on the main row and stray items stay valid. */
    fun migrateBarOrders(primary: List<String>, secondary: List<String>): Pair<List<String>, List<String>> {
        val p = if (primary.isEmpty()) DEFAULT_PRIMARY_ORDER.toMutableList() else primary.toMutableList()
        val s = if (secondary.isEmpty()) DEFAULT_SECONDARY_ORDER.toMutableList() else secondary.toMutableList()
        for (id in listOf(POLISH, RAW_POLISH)) {
            if (s.remove(id) && id !in p) {
                val insertAt = (p.indexOf(WAND) + 1).coerceAtMost(p.size)
                p.add(insertAt, id)
            }
        }
        if (s.remove(DELETE_FORWARD) && DELETE_FORWARD !in p) {
            val insertAt = (p.indexOf(PAGE) + 1).coerceAtMost(p.size)
            p.add(insertAt, DELETE_FORWARD)
        }
        return parseOrder(serializeOrder(p), DEFAULT_PRIMARY_ORDER) to
            parseOrder(serializeOrder(s), DEFAULT_SECONDARY_ORDER)
    }

    fun iconRes(id: String): Int = when (id) {
        PAGE -> com.gremier.gkeys.R.drawable.ic_chevron_forward
        WAND -> com.gremier.gkeys.R.drawable.ic_ghostwriter
        POLISH -> com.gremier.gkeys.R.drawable.ic_polish
        RAW_POLISH -> com.gremier.gkeys.R.drawable.ic_polish
        CLEAR_ALL -> com.gremier.gkeys.R.drawable.ic_clear_all
        CLIPBOARD -> com.gremier.gkeys.R.drawable.ic_clipboard_toolbar
        LIVE -> com.gremier.gkeys.R.drawable.ic_live_speech
        MIC -> com.gremier.gkeys.R.drawable.ic_mic_white
        NUMPAD -> com.gremier.gkeys.R.drawable.ic_dialpad
        BACK -> com.gremier.gkeys.R.drawable.ic_back_arrow
        SETTINGS -> com.gremier.gkeys.R.drawable.ic_settings
        UNDO -> com.gremier.gkeys.R.drawable.ic_undo
        DELETE_FORWARD -> com.gremier.gkeys.R.drawable.ic_delete_forward
        SELECT_ALL -> com.gremier.gkeys.R.drawable.ic_select_all
        BUBBLE -> com.gremier.gkeys.R.drawable.ic_voice_bubble
        else -> com.gremier.gkeys.R.drawable.ic_settings
    }

    fun splitOrderRaw(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    val ALL_PRIMARY = DEFAULT_PRIMARY_ORDER
    val ALL_SECONDARY = DEFAULT_SECONDARY_ORDER

    fun label(id: String): String = when (id) {
        PAGE -> "More (→ second row)"
        WAND -> "Ghostwriter (quill)"
        POLISH -> "Polish mode (N/F/R)"
        RAW_POLISH -> "Polish now (Raw mode only)"
        CLEAR_ALL -> "Clear all text"
        CLIPBOARD -> "Clipboard preview"
        LIVE -> "Live transcribe"
        MIC -> "Mic on toolbar"
        NUMPAD -> "Number pad"
        BACK -> "Back (← main row)"
        SETTINGS -> "Settings"
        UNDO -> "Undo"
        DELETE_FORWARD -> "Delete forward"
        SELECT_ALL -> "Select all"
        BUBBLE -> "Voice bubble mode"
        else -> id
    }

    fun parseOrder(raw: String?, defaults: List<String>): List<String> {
        if (raw.isNullOrBlank()) return defaults
        val parsed = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parsed.isEmpty()) return defaults
        val valid = parsed.filter { it in defaults }.distinct()
        return valid + defaults.filter { it !in valid }
    }

    fun serializeOrder(order: List<String>): String = order.joinToString(",")
}
