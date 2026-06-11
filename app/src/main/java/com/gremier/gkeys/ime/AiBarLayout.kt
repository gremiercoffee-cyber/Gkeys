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

    val DEFAULT_PRIMARY_ORDER = listOf(PAGE, WAND, POLISH, RAW_POLISH, CLEAR_ALL, CLIPBOARD, LIVE, MIC, NUMPAD)
    val DEFAULT_SECONDARY_ORDER = listOf(BACK, SETTINGS, UNDO, DELETE_FORWARD, SELECT_ALL, BUBBLE)

    val ALL_PRIMARY = DEFAULT_PRIMARY_ORDER
    val ALL_SECONDARY = DEFAULT_SECONDARY_ORDER

    fun label(id: String): String = when (id) {
        PAGE -> "More (→ second row)"
        WAND -> "Ghostwriter (wand)"
        POLISH -> "Polish mode (N/F/R)"
        RAW_POLISH -> "Polish now (Raw mode only)"
        CLEAR_ALL -> "Clear all text"
        CLIPBOARD -> "Clipboard preview"
        LIVE -> "Live transcribe"
        MIC -> "Mic dictation"
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
