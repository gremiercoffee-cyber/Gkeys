package com.gremier.gkeys.ime.suggestions

data class SuggestionChip(
    val text: String,
    val isPrimary: Boolean = false,
    val isCorrection: Boolean = false,
)

data class SuggestionStripModel(
    val left: SuggestionChip?,
    val center: SuggestionChip?,
    val right: SuggestionChip?,
)

object SuggestionEngine {

    fun build(
        prefix: String,
        lastWord: String,
        personalVocab: Map<String, Int>,
    ): SuggestionStripModel {
        val p = prefix.lowercase()
        return if (p.isEmpty()) {
            buildNextWord(lastWord, personalVocab)
        } else {
            buildCompletions(p, personalVocab)
        }
    }

    fun autocorrectOnSpace(prefix: String, personalVocab: Map<String, Int>): String? {
        if (prefix.length < 2) return null
        return CommonWords.autocorrect(prefix, personalVocab)
    }

    private fun buildCompletions(prefix: String, personalVocab: Map<String, Int>): SuggestionStripModel {
        val completions = CommonWords.completions(prefix, personalVocab)
        val correction = CommonWords.autocorrect(prefix, personalVocab)
        val typedIsWord = CommonWords.isKnown(prefix)

        val centerText = when {
            correction != null && !typedIsWord -> correction
            completions.isNotEmpty() -> completions.first()
            prefix.length >= 1 -> prefix
            else -> null
        } ?: return SuggestionStripModel(null, null, null)

        val centerIsCorrection = correction != null && correction != prefix && centerText == correction
        val center = SuggestionChip(centerText, isPrimary = true, isCorrection = centerIsCorrection)

        val alts = completions
            .filter { it != centerText }
            .distinct()
        val left = alts.getOrNull(0)?.let { SuggestionChip(it) }
        val right = alts.getOrNull(1)?.let { SuggestionChip(it) }

        return SuggestionStripModel(left, center, right)
    }

    private fun buildNextWord(lastWord: String, personalVocab: Map<String, Int>): SuggestionStripModel {
        val candidates = CommonWords.nextWordCandidates(lastWord, personalVocab)
        if (candidates.isEmpty()) return SuggestionStripModel(null, null, null)

        val center = SuggestionChip(candidates.first(), isPrimary = true)
        val left = candidates.getOrNull(1)?.let { SuggestionChip(it) }
        val right = candidates.getOrNull(2)?.let { SuggestionChip(it) }
        return SuggestionStripModel(left, center, right)
    }
}
