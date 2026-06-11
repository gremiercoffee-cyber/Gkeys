package com.gremier.gkeys.ime

/** IDs for customizable AI toolbar buttons (single scrollable row). */
object AiBarLayout {
    const val WAND = "wand"
    const val POLISH = "polish"
    const val RAW_POLISH = "raw_polish"
    const val CLEAR_ALL = "clear_all"
    const val CLIPBOARD = "clipboard"
    const val LIVE = "live"
    const val MIC = "mic"
    const val NUMPAD = "numpad"
    const val SETTINGS = "settings"
    const val UNDO = "undo"
    const val DELETE_FORWARD = "delete_forward"
    const val SELECT_ALL = "select_all"
    const val BUBBLE = "bubble"

    /** @deprecated Removed from toolbar — migrated out of saved order */
    const val PAGE = "page"
    /** @deprecated Removed from toolbar — migrated out of saved order */
    const val BACK = "back"

    const val SPACER_TAG = "ai_bar_spacer"
    const val ICON_SIZE_DP = 30
    const val CLIPBOARD_WIDTH_DP = 84

    val DEFAULT_ORDER = listOf(
        WAND, POLISH, RAW_POLISH, DELETE_FORWARD, CLEAR_ALL, CLIPBOARD,
        LIVE, MIC, NUMPAD, SETTINGS, UNDO, SELECT_ALL, BUBBLE,
    )

    val ALL_ITEMS = DEFAULT_ORDER

    /** Reads unified order, or merges legacy two-row prefs. */
    fun resolveOrder(
        unifiedRaw: String?,
        legacyPrimaryRaw: String?,
        legacySecondaryRaw: String?,
    ): List<String> {
        if (!unifiedRaw.isNullOrBlank()) {
            return parseOrder(unifiedRaw, DEFAULT_ORDER)
        }
        return mergeLegacyOrders(
            splitOrderRaw(legacyPrimaryRaw),
            splitOrderRaw(legacySecondaryRaw),
        )
    }

    fun mergeLegacyOrders(primary: List<String>, secondary: List<String>): List<String> {
        val p = if (primary.isEmpty()) emptyList() else primary.toMutableList()
        val s = if (secondary.isEmpty()) emptyList() else secondary.toMutableList()
        for (id in listOf(POLISH, RAW_POLISH)) {
            if (s.remove(id) && id !in p) {
                val insertAt = (p.indexOf(WAND) + 1).coerceAtMost(p.size)
                p.add(insertAt, id)
            }
        }
        if (s.remove(DELETE_FORWARD) && DELETE_FORWARD !in p) {
            val insertAt = (p.indexOf(WAND).coerceAtLeast(0) + 1).coerceAtMost(p.size)
            p.add(insertAt, DELETE_FORWARD)
        }
        val merged = (p + s)
            .filter { it !in setOf(PAGE, BACK) && it in DEFAULT_ORDER }
            .distinct()
            .toMutableList()
        return parseOrder(serializeOrder(merged), DEFAULT_ORDER)
    }

    fun iconRes(id: String): Int = when (id) {
        WAND -> com.gremier.gkeys.R.drawable.ic_ghostwriter_hand
        POLISH -> com.gremier.gkeys.R.drawable.ic_polish
        RAW_POLISH -> com.gremier.gkeys.R.drawable.ic_polish
        CLEAR_ALL -> com.gremier.gkeys.R.drawable.ic_clear_all
        CLIPBOARD -> com.gremier.gkeys.R.drawable.ic_clipboard_toolbar
        LIVE -> com.gremier.gkeys.R.drawable.ic_live_speech
        MIC -> com.gremier.gkeys.R.drawable.ic_mic_white
        NUMPAD -> com.gremier.gkeys.R.drawable.ic_dialpad
        SETTINGS -> com.gremier.gkeys.R.drawable.ic_settings
        UNDO -> com.gremier.gkeys.R.drawable.ic_undo
        DELETE_FORWARD -> com.gremier.gkeys.R.drawable.ic_delete_forward
        SELECT_ALL -> com.gremier.gkeys.R.drawable.ic_select_all
        BUBBLE -> com.gremier.gkeys.R.drawable.ic_voice_bubble
        else -> com.gremier.gkeys.R.drawable.ic_settings
    }

    fun splitOrderRaw(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun label(id: String): String = when (id) {
        WAND -> "Ghostwriter (quill)"
        POLISH -> "Polish mode (N/F/R)"
        RAW_POLISH -> "Polish now (Raw mode only)"
        CLEAR_ALL -> "Clear all text"
        CLIPBOARD -> "Clipboard preview"
        LIVE -> "Live transcribe"
        MIC -> "Mic on toolbar"
        NUMPAD -> "Number pad"
        SETTINGS -> "Settings"
        UNDO -> "Undo"
        DELETE_FORWARD -> "Delete forward"
        SELECT_ALL -> "Select all"
        BUBBLE -> "Voice bubble mode"
        PAGE, BACK -> ""
        else -> id
    }

    fun parseOrder(raw: String?, defaults: List<String>): List<String> {
        if (raw.isNullOrBlank()) return defaults
        val parsed = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parsed.isEmpty()) return defaults
        val valid = parsed.filter { it in defaults && it !in setOf(PAGE, BACK) }.distinct()
        return valid + defaults.filter { it !in valid }
    }

    fun serializeOrder(order: List<String>): String =
        order.filter { it in DEFAULT_ORDER }.joinToString(",")
}
