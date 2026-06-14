package com.gremier.gkeys.ime.suggestions

import android.content.Context
import com.gremier.gkeys.R
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/**
 * Offline frequency-ranked dictionaries bundled in res/raw (AOSP combined format, gzipped).
 * Loaded once per language and kept in memory for fast prefix lookup.
 */
object DictionaryManager {

    enum class Language(val code: String) {
        EN("en"),
        HE("he"),
    }

    private data class LoadedDict(
        val rankByWord: Map<String, Int>,
        val byFirstChar: Map<Char, List<String>>,
        val rankedWords: List<String>,
    )

    @Volatile
    private var english: LoadedDict? = null
    @Volatile
    private var hebrew: LoadedDict? = null

    fun languageForKeyboard(isHebrew: Boolean): Language =
        if (isHebrew) Language.HE else Language.EN

    fun ensureLoaded(context: Context, language: Language) {
        when (language) {
            Language.EN -> if (english == null) english = load(context, R.raw.en_wordlist_combined_gz, Language.EN)
            Language.HE -> if (hebrew == null) hebrew = load(context, R.raw.he_wordlist_combined_gz, Language.HE)
        }
    }

    fun isLoaded(language: Language): Boolean = when (language) {
        Language.EN -> english != null
        Language.HE -> hebrew != null
    }

    fun isKnown(language: Language, word: String): Boolean {
        val dict = dict(language) ?: return false
        return dict.rankByWord.containsKey(normalize(word, language))
    }

    fun frequencyRank(language: Language, word: String): Int {
        val dict = dict(language) ?: return Int.MAX_VALUE
        return dict.rankByWord[normalize(word, language)] ?: Int.MAX_VALUE
    }

    fun frequencyScore(language: Language, word: String): Double {
        val rank = frequencyRank(language, word)
        if (rank == Int.MAX_VALUE) return 0.0
        val dict = dict(language) ?: return 0.0
        val total = dict.rankByWord.size.coerceAtLeast(1)
        return (100.0 * (1.0 - rank.toDouble() / total)).coerceIn(0.0, 100.0)
    }

    fun prefixMatches(language: Language, prefix: String, limit: Int = 24): List<String> {
        val p = normalize(prefix, language)
        if (p.isEmpty()) return emptyList()
        val dict = dict(language) ?: return emptyList()
        val bucket = dict.byFirstChar[p.firstOrNull()] ?: return emptyList()
        return bucket.asSequence()
            .filter { it.startsWith(p) }
            .take(limit)
            .toList()
    }

    fun topWords(language: Language, limit: Int = 40_000): List<String> {
        val dict = dict(language) ?: return emptyList()
        return dict.rankedWords.take(limit)
    }

    fun correctionCandidates(language: Language, typed: String): List<String> {
        val lower = normalize(typed, language)
        if (lower.length < 2) return emptyList()
        val len = lower.length
        val maxDist = when {
            len <= 4 -> 1
            len <= 8 -> 2
            else -> 2
        }
        val dict = dict(language) ?: return emptyList()
val pool = LinkedHashSet<String>()
        collectBucket(pool, dict, lower.firstOrNull(), len, maxDist)
        // Second-char bucket: catches first-pair transpositions ("hte" -> "the")
        // and a stray leading letter ("hhello" -> "hello"), where the real word's
        // first letter is the typed word's SECOND letter.
        if (lower.length >= 2) collectBucket(pool, dict, lower[1], len, maxDist)
        for (neighbor in KeyboardProximity.neighborKeys(lower[0])) {
            collectBucket(pool, dict, neighbor, len, maxDist)
        }
        if (pool.size < 40) {
            dict.byFirstChar.values.flatten().asSequence()
                .filter { kotlin.math.abs(it.length - len) <= maxDist }
                .take(200)
                .forEach { pool.add(it) }
        }
        return pool.asSequence()
            .filter { candidate ->
                val dist = editDistance(lower, candidate)
                dist in 1..maxDist
            }
            .sortedBy { frequencyRank(language, it) }
            .take(80)
            .toList()
    }

    private fun collectBucket(
        pool: MutableSet<String>,
        dict: LoadedDict,
        first: Char?,
        len: Int,
        maxDist: Int,
    ) {
        if (first == null) return
        val list = dict.byFirstChar[first] ?: return
        for (word in list) {
            if (kotlin.math.abs(word.length - len) <= maxDist) {
                pool.add(word)
                if (pool.size > 500) return
            }
        }
    }

    private fun dict(language: Language): LoadedDict? = when (language) {
        Language.EN -> english
        Language.HE -> hebrew
    }

    private fun load(context: Context, rawResId: Int, language: Language): LoadedDict {
        val text = context.applicationContext.resources.openRawResource(rawResId).use { stream ->
            GZIPInputStream(BufferedInputStream(stream)).bufferedReader().readText()
        }
        val parsed = AospCombinedParser.parse(text)
        val rankByWord = HashMap<String, Int>(parsed.size * 4 / 3)
        val byFirstChar = HashMap<Char, ArrayList<String>>()
        parsed.forEachIndexed { index, entry ->
            val word = normalize(entry.word, language)
            if (rankByWord.containsKey(word)) return@forEachIndexed
            rankByWord[word] = index
            byFirstChar.getOrPut(word.firstOrNull() ?: return@forEachIndexed) { ArrayList(800) }
                .add(word)
        }
        val rankedWords = parsed.asSequence()
            .map { normalize(it.word, language) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return LoadedDict(rankByWord, byFirstChar.mapValues { it.value.toList() }, rankedWords)
    }

    private fun normalize(word: String, language: Language): String = when (language) {
        Language.EN -> word.lowercase()
        Language.HE -> word
    }

/** Damerau-Levenshtein: a swap of two adjacent letters costs 1, not 2,
     *  so common transpositions ("form"/"from") survive the maxDist filter. */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val n = a.length
        val m = b.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, d[i - 2][j - 2] + 1)
                }
                d[i][j] = best
            }
        }
        return d[n][m]
    }
}
