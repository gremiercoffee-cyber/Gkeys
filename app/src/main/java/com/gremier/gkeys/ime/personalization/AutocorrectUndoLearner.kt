package com.gremier.gkeys.ime.personalization

import android.content.Context

class AutocorrectUndoLearner(context: Context) {
    private val logger = TypingEventLogger(context)

    fun recordUndo(original: String, corrected: String) {
        logger.logAutocorrectUndo(original, corrected)
    }
}
