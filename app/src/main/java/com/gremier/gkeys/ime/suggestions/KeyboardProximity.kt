package com.gremier.gkeys.ime.suggestions

/** QWERTY adjacency for typo scoring (Gboard-style). */
object KeyboardProximity {

    private val NEIGHBORS: Map<Char, String> = mapOf(
        'q' to "wa", 'w' to "qase", 'e' to "wsdr", 'r' to "edft", 't' to "rfgy", 'y' to "tghu",
        'u' to "yhji", 'i' to "ujko", 'o' to "iklp", 'p' to "ol",
        'a' to "qwsz", 's' to "awedxz", 'd' to "serfcx", 'f' to "drtgvc", 'g' to "ftyhbv",
        'h' to "gyujnb", 'j' to "huikmn", 'k' to "jiolm", 'l' to "kop",
        'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
        'n' to "bhjm", 'm' to "njk",
    )

    fun neighborKeys(c: Char): Set<Char> = NEIGHBORS[c.lowercaseChar()]?.toSet().orEmpty()

    /** 0–1 likelihood that [typed] was meant to be [expected] on QWERTY. */
    fun substitutionScore(typed: Char, expected: Char): Float {
        val t = typed.lowercaseChar()
        val e = expected.lowercaseChar()
        if (t == e) return 1f
        if (e in neighborKeys(t)) return 0.75f
        return 0f
    }

    /** Average key proximity across aligned chars (handles insert/delete roughly). */
    fun wordProximityScore(typed: String, candidate: String): Float {
        if (typed.isEmpty() || candidate.isEmpty()) return 0f
        var sum = 0f
        var count = 0
        val minLen = minOf(typed.length, candidate.length)
        for (i in 0 until minLen) {
            sum += substitutionScore(typed[i], candidate[i])
            count++
        }
        return if (count == 0) 0f else sum / count
    }
}
