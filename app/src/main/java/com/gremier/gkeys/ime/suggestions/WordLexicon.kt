package com.gremier.gkeys.ime.suggestions

import android.content.Context
import kotlin.math.abs

/**
 * ~10k English words ordered by frequency (Google 10k list in assets).
 * Loaded once on first use.
 */
object WordLexicon {

    private const val ASSET_PATH = "suggestions/en_words.txt"

    @Volatile
    private var loaded = false
    private val words = ArrayList<String>(10_000)
    private val rankByWord = HashMap<String, Int>(12_000)
    private val byFirstLetter = HashMap<Char, ArrayList<String>>()
    private val byLength = HashMap<Int, ArrayList<String>>()

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            context.applicationContext.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val word = line.trim().lowercase()
                    if (word.isEmpty() || word.any { !it.isLetter() && it != '\'' }) return@forEachIndexed
                    if (rankByWord.containsKey(word)) return@forEachIndexed
                    words.add(word)
                    rankByWord[word] = index
                    byFirstLetter.getOrPut(word[0]) { ArrayList(400) }.add(word)
                    byLength.getOrPut(word.length) { ArrayList(400) }.add(word)
                }
            }
            loaded = true
        }
    }

    fun isKnown(word: String): Boolean {
        return rankByWord.containsKey(word.lowercase())
    }

    fun rankOf(word: String): Int = rankByWord[word.lowercase()] ?: Int.MAX_VALUE

    /** Higher = more common (0–100). */
    fun frequencyScore(word: String): Double {
        val rank = rankOf(word)
        if (rank == Int.MAX_VALUE) return 0.0
        return (100.0 * (1.0 - rank / 10_000.0)).coerceIn(0.0, 100.0)
    }

    fun completions(prefix: String, limit: Int = 16): List<String> {
        if (prefix.isBlank()) return emptyList()
        val p = prefix.lowercase()
        val bucket = byFirstLetter[p[0]] ?: return emptyList()
        return bucket.asSequence()
            .filter { it.startsWith(p) && it.length > p.length }
            .sortedBy { rankOf(it) }
            .take(limit)
            .toList()
    }

    fun correctionCandidates(typed: String): List<String> {
        val lower = typed.lowercase()
        if (lower.length < 2) return emptyList()
        val len = lower.length
        val maxDist = when {
            len <= 4 -> 1
            len <= 8 -> 2
            else -> 2
        }
        val maxRank = when {
            maxDist == 1 -> 12_000
            len >= 7 -> 4_000
            else -> 8_000
        }

        val pool = LinkedHashSet<String>()
        collectBucket(pool, lower[0], len, maxDist)
        for (neighbor in KeyboardProximity.neighborKeys(lower[0])) {
            collectBucket(pool, neighbor, len, maxDist)
        }
        if (pool.size < 40) {
            byLength[len]?.let { pool.addAll(it.take(120)) }
            if (maxDist >= 2 && len > 2) {
                byLength[len - 1]?.let { pool.addAll(it.take(60)) }
                byLength[len + 1]?.let { pool.addAll(it.take(60)) }
            }
        }

        return pool.asSequence()
            .filter { candidate ->
                val dist = editDistance(lower, candidate)
                dist in 1..maxDist && rankOf(candidate) < maxRank
            }
            .take(120)
            .toList()
    }

    private fun collectBucket(pool: MutableSet<String>, first: Char, len: Int, maxDist: Int) {
        byFirstLetter[first]?.let { list ->
            for (word in list) {
                if (abs(word.length - len) <= maxDist) {
                    pool.add(word)
                    if (pool.size > 500) return
                }
            }
        }
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }
}
