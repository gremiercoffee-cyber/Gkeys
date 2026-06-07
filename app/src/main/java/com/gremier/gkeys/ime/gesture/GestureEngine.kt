package com.gremier.gkeys.ime.gesture

import android.content.Context

/**
 * Facade for SHARK2-style gesture typing used by [com.gremier.gkeys.ime.SwipeTyper].
 */
class GestureEngine(context: Context) {

    val geometry = KeyboardGeometry()
    private val dictionary = WordDictionary(context)
    private val matcher = GestureMatcher(dictionary, geometry)

    fun suggest(path: List<GesturePoint>, topN: Int = 3): List<GestureSuggestion> =
        matcher.match(path, topN)

    fun bestWord(path: List<GesturePoint>): String? =
        suggest(path, 1).firstOrNull()?.word
}
