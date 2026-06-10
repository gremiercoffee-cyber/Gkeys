package com.gremier.gkeys.ime.suggestions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local user-learned vocabulary with priority over the bundled dictionary. */
class UserWordsRepository(context: Context) {

    private val dao = UserWordsDatabase.getInstance(context).userWordsDao()

    @Volatile
    private var cache: Map<String, Map<String, Int>> = emptyMap()

    suspend fun ensureCache(language: DictionaryManager.Language) {
        if (cache.containsKey(language.code)) return
        reload(language)
    }

    suspend fun reload(language: DictionaryManager.Language) {
        val rows = withContext(Dispatchers.IO) {
            dao.topWords(language.code, 1500)
        }
        cache = cache + (language.code to rows.associate { it.word to it.frequency })
    }

    fun words(language: DictionaryManager.Language): Map<String, Int> =
        cache[language.code].orEmpty()

    suspend fun recordWord(language: DictionaryManager.Language, word: String) {
        val normalized = normalize(word, language)
        if (normalized.length < 2) return
        withContext(Dispatchers.IO) {
            val existing = dao.frequency(normalized, language.code)
            if (existing != null) {
                dao.incrementFrequency(normalized, language.code)
            } else {
                dao.upsert(
                    UserWordEntity(
                        word = normalized,
                        language = language.code,
                        frequency = 1,
                        addedAt = System.currentTimeMillis(),
                    )
                )
            }
        }
        bumpCache(language, normalized)
    }

    /** Keep in-memory vocabulary hot so new words surface in suggestions immediately. */
    private fun bumpCache(language: DictionaryManager.Language, word: String) {
        val code = language.code
        val updated = cache[code].orEmpty().toMutableMap()
        updated[word] = (updated[word] ?: 0) + 1
        cache = cache + (code to updated)
    }

    fun prefixMatches(language: DictionaryManager.Language, prefix: String, limit: Int = 12): List<String> {
        val p = normalize(prefix, language)
        if (p.isEmpty()) return emptyList()
        return words(language).entries
            .asSequence()
            .filter { it.key.startsWith(p) && it.key.length > p.length }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
            .toList()
    }

    private fun normalize(word: String, language: DictionaryManager.Language): String = when (language) {
        DictionaryManager.Language.EN -> word.lowercase()
        DictionaryManager.Language.HE -> word
    }
}
