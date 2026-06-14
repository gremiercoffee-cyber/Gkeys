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

    fun recordWordInMemory(language: DictionaryManager.Language, word: String, weight: Int = 1) {
        val normalized = normalize(word, language)
        if (normalized.length < 2) return
        bumpCache(language, normalized, weight)
    }

    suspend fun recordWord(
        language: DictionaryManager.Language,
        word: String,
        source: LearningSource = LearningSource.TYPED,
    ) {
        val normalized = normalize(word, language)
        if (normalized.length < 2) return
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val existing = dao.word(normalized, language.code)
            if (existing != null) {
                dao.upsert(existing.learned(source, now))
            } else {
                dao.upsert(
                    UserWordEntity(
                        word = normalized,
                        language = language.code,
                        frequency = source.frequencyDelta,
                        addedAt = now,
                        updatedAt = now,
                        confidence = source.confidenceDelta,
                        typedCount = if (source == LearningSource.TYPED) 1 else 0,
                        swipeCount = if (source == LearningSource.SWIPE_ACCEPTED) 1 else 0,
                        selectedCount = if (source == LearningSource.SELECTED) 1 else 0,
                        autocorrectAcceptedCount = if (source == LearningSource.AUTOCORRECT_ACCEPTED) 1 else 0,
                        correctionAcceptedCount = if (source == LearningSource.CORRECTION_ACCEPTED) 1 else 0,
                    )
                )
            }
        }
    }

    suspend fun recordRejected(language: DictionaryManager.Language, word: String) {
        val normalized = normalize(word, language)
        if (normalized.length < 2) return
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val existing = dao.word(normalized, language.code) ?: return@withContext
            dao.upsert(
                existing.copy(
                    updatedAt = now,
                    confidence = (existing.confidence - 0.8f).coerceAtLeast(0f),
                    rejectedCount = existing.rejectedCount + 1,
                )
            )
        }
    }

    /** Keep in-memory vocabulary hot so new words surface in suggestions immediately. */
    private fun bumpCache(language: DictionaryManager.Language, word: String, amount: Int = 1) {
        val code = language.code
        val updated = cache[code].orEmpty().toMutableMap()
        updated[word] = (updated[word] ?: 0) + amount
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

    enum class LearningSource(
        val frequencyDelta: Int,
        val confidenceDelta: Float,
        val cacheWeight: Int = frequencyDelta,
    ) {
        TYPED(1, 1.0f),
        SWIPE_ACCEPTED(2, 1.6f),
        SELECTED(3, 2.0f),
        AUTOCORRECT_ACCEPTED(2, 1.4f),
        CORRECTION_ACCEPTED(4, 3.0f),
    }

    private fun UserWordEntity.learned(source: LearningSource, now: Long): UserWordEntity =
        copy(
            frequency = frequency + source.frequencyDelta,
            updatedAt = now,
            confidence = (confidence + source.confidenceDelta).coerceAtMost(250f),
            typedCount = typedCount + if (source == LearningSource.TYPED) 1 else 0,
            swipeCount = swipeCount + if (source == LearningSource.SWIPE_ACCEPTED) 1 else 0,
            selectedCount = selectedCount + if (source == LearningSource.SELECTED) 1 else 0,
            autocorrectAcceptedCount = autocorrectAcceptedCount +
                if (source == LearningSource.AUTOCORRECT_ACCEPTED) 1 else 0,
            correctionAcceptedCount = correctionAcceptedCount +
                if (source == LearningSource.CORRECTION_ACCEPTED) 1 else 0,
        )
}
