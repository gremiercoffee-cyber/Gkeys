package com.gremier.gkeys.ime.gesture

import android.content.Context

/**
 * English word list ordered by approximate frequency (most common first).
 * Indexed by first/last character for SHARK2-style search-space pruning.
 */
class WordDictionary(context: Context) {

    data class Entry(val word: String, val rank: Int)

    private val allEntries = ArrayList<Entry>(4096)
    private val byFirstLast = HashMap<Long, MutableList<Entry>>()

    init {
        val seen = HashSet<String>(4096)
        context.assets.open("en_words.txt").bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                val w = line.trim().lowercase()
                if (w.length < 2 || !w.all { it.isLetter() }) return@forEachIndexed
                if (!seen.add(w)) return@forEachIndexed
                val entry = Entry(w, index)
                allEntries.add(entry)
                val key = bucketKey(w.first(), w.last())
                byFirstLast.getOrPut(key) { ArrayList() }.add(entry)
            }
        }
    }

    fun size(): Int = allEntries.size

    /**
     * Returns candidate words whose first/last letters fall in the pruned key sets.
     * Expands search if the pruned set is too small.
     */
    fun candidates(startKeys: Set<Char>, endKeys: Set<Char>): List<Entry> {
        if (startKeys.isEmpty() || endKeys.isEmpty()) return allEntries.take(500)
        val result = LinkedHashSet<Entry>()
        for (s in startKeys) {
            for (e in endKeys) {
                byFirstLast[bucketKey(s, e)]?.let { result.addAll(it) }
            }
        }
        if (result.size >= 30) return result.toList()
        for (s in startKeys) {
            allEntries.asSequence()
                .filter { it.word.first() == s }
                .take(400)
                .forEach { result.add(it) }
        }
        if (result.isNotEmpty()) return result.toList()
        return allEntries.take(500)
    }

    private fun bucketKey(first: Char, last: Char): Long =
        (first.code.toLong() shl 16) or last.code.toLong()
}
