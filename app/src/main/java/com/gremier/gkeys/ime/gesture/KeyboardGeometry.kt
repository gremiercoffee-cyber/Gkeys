package com.gremier.gkeys.ime.gesture

import kotlin.math.hypot

/**
 * Maps each letter key to its centre point in keyboard-container coordinates.
 * Templates are built by connecting key centres in word order (SHARK2).
 */
class KeyboardGeometry {

    private val centers = mutableMapOf<Char, GesturePoint>()
    var averageKeySize: Float = 48f
        private set

    private val sizes = mutableListOf<Float>()

    fun setKeyCenter(letter: Char, x: Float, y: Float, size: Float = averageKeySize) {
        centers[letter.lowercaseChar()] = GesturePoint(x, y)
        sizes.add(size)
        if (sizes.isNotEmpty()) {
            averageKeySize = sizes.sum() / sizes.size
        }
    }

    fun clear() {
        centers.clear()
        sizes.clear()
    }

    fun hasLayout(): Boolean = centers.size >= 20

    fun templateForWord(word: String): List<GesturePoint> {
        return word.lowercase().mapNotNull { centers[it] }
    }

    /** Keys whose centre falls within [radius] of (x, y). Used for SHARK2 pruning. */
    fun keysNear(x: Float, y: Float, radius: Float): Set<Char> {
        val r2 = radius * radius
        return centers.filter { (_, pt) ->
            val dx = pt.x - x
            val dy = pt.y - y
            dx * dx + dy * dy <= r2
        }.keys
    }

    fun nearestKey(x: Float, y: Float): Char? {
        var best: Char? = null
        var bestDist = Float.MAX_VALUE
        for ((c, pt) in centers) {
            val d = hypot(pt.x - x, pt.y - y)
            if (d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }
}
