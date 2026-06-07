package com.gremier.gkeys.ime.gesture

/**
 * A point on the keyboard plane in container-local coordinates.
 */
data class GesturePoint(val x: Float, val y: Float)

data class GestureSuggestion(
    val word: String,
    val score: Float
)
