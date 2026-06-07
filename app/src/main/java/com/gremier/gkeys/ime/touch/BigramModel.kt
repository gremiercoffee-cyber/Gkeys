package com.gremier.gkeys.ime.touch

/**
 * Common English bigram successors with relative weights (higher = more likely).
 * Used to expand invisible hit zones for statistically likely next keys.
 */
object BigramModel {

    private val successors: Map<Char, Map<Char, Float>> = buildMap {
        fun row(prev: Char, vararg pairs: Pair<Char, Float>) {
            put(prev, pairs.toMap())
        }
        row('t', 'h' to 1f, 'o' to 0.92f, 'e' to 0.85f, 'a' to 0.78f, 'i' to 0.75f,
            'r' to 0.72f, 's' to 0.68f, 'u' to 0.62f, 'y' to 0.55f)
        row('h', 'e' to 1f, 'a' to 0.82f, 'i' to 0.72f, 'o' to 0.68f, 'y' to 0.55f)
        row('a', 'n' to 1f, 't' to 0.95f, 'l' to 0.88f, 'r' to 0.82f, 's' to 0.78f,
            'c' to 0.72f, 'm' to 0.68f, 'b' to 0.62f, 'p' to 0.58f, 'g' to 0.52f)
        row('e', 'r' to 1f, 's' to 0.92f, 'd' to 0.85f, 'n' to 0.82f, 'l' to 0.78f,
            'a' to 0.72f, 'c' to 0.68f, 't' to 0.65f, 'm' to 0.58f)
        row('i', 'n' to 1f, 's' to 0.92f, 't' to 0.88f, 'o' to 0.82f, 'c' to 0.75f,
            'a' to 0.72f, 'l' to 0.68f, 'd' to 0.62f, 'm' to 0.58f)
        row('o', 'n' to 1f, 'u' to 0.92f, 'r' to 0.85f, 'l' to 0.78f, 'f' to 0.72f,
            'm' to 0.68f, 'p' to 0.62f, 's' to 0.58f, 't' to 0.55f)
        row('n', 'd' to 1f, 't' to 0.92f, 'g' to 0.85f, 's' to 0.78f, 'c' to 0.72f,
            'e' to 0.68f, 'i' to 0.62f, 'o' to 0.58f)
        row('s', 't' to 1f, 'e' to 0.92f, 'h' to 0.85f, 'i' to 0.78f, 'o' to 0.72f,
            'a' to 0.68f, 'u' to 0.62f, 'p' to 0.58f)
        row('r', 'e' to 1f, 'i' to 0.88f, 'o' to 0.82f, 'a' to 0.78f, 's' to 0.72f,
            't' to 0.68f, 'm' to 0.62f)
        row('d', 'e' to 1f, 'i' to 0.85f, 'a' to 0.78f, 'o' to 0.72f, 's' to 0.65f)
        row('l', 'e' to 1f, 'y' to 0.88f, 'i' to 0.82f, 'a' to 0.78f, 'o' to 0.72f,
            'l' to 0.55f)
        row('c', 'h' to 1f, 'e' to 0.92f, 'o' to 0.85f, 'a' to 0.78f, 'i' to 0.72f,
            't' to 0.65f)
        row('u', 'r' to 1f, 's' to 0.92f, 'n' to 0.85f, 'l' to 0.78f, 't' to 0.72f,
            'm' to 0.65f)
        row('m', 'e' to 1f, 'a' to 0.92f, 'i' to 0.85f, 'o' to 0.78f, 'p' to 0.72f,
            'b' to 0.65f)
        row('w', 'h' to 1f, 'a' to 0.88f, 'i' to 0.82f, 'e' to 0.78f, 'o' to 0.72f)
        row('f', 'o' to 1f, 'r' to 0.92f, 'e' to 0.85f, 'a' to 0.78f, 'i' to 0.68f)
        row('g', 'h' to 1f, 'e' to 0.92f, 'a' to 0.85f, 'i' to 0.78f, 'o' to 0.72f)
        row('y', 'o' to 1f, 'e' to 0.88f, 'a' to 0.82f, 's' to 0.72f)
        row('p', 'e' to 1f, 'r' to 0.92f, 'o' to 0.85f, 'a' to 0.78f, 'l' to 0.72f,
            'h' to 0.65f)
        row('b', 'e' to 1f, 'l' to 0.92f, 'y' to 0.85f, 'o' to 0.78f, 'u' to 0.72f,
            'a' to 0.68f)
        row('v', 'e' to 1f, 'i' to 0.88f, 'a' to 0.82f, 'o' to 0.72f)
        row('k', 'e' to 1f, 'i' to 0.85f, 's' to 0.78f, 'n' to 0.72f)
        row('j', 'e' to 1f, 'o' to 0.85f, 'u' to 0.78f, 'a' to 0.72f)
        row('x', 't' to 1f, 'i' to 0.85f, 'e' to 0.78f, 'a' to 0.72f)
        row('z', 'e' to 1f, 'a' to 0.85f, 'o' to 0.72f)
        row('q', 'u' to 1f)
        row(' ', 't' to 1f, 'a' to 0.95f, 'i' to 0.92f, 'o' to 0.88f, 's' to 0.85f,
            'w' to 0.82f, 'b' to 0.78f, 'h' to 0.75f, 'c' to 0.72f, 'm' to 0.68f)
    }

    /** High-frequency vowels/consonants get larger base hit zones. */
    private val HIGH_FREQ = setOf('e', 't', 'a', 'o', 'i')
    const val HIGH_FREQ_RADIUS_BOOST = 1.22f

    /** Bigram boost applied to hit radius (not distance). */
    const val BIGRAM_RADIUS_BOOST = 0.38f

    fun bigramMultiplier(previous: Char?, candidate: Char): Float {
        if (previous == null) return 1f
        val map = successors[previous] ?: return 1f
        val weight = map[candidate] ?: return 1f
        return 1f + weight * BIGRAM_RADIUS_BOOST
    }

    fun frequencyMultiplier(char: Char?): Float {
        if (char == null) return 1f
        return if (char.lowercaseChar() in HIGH_FREQ) HIGH_FREQ_RADIUS_BOOST else 1f
    }
}
