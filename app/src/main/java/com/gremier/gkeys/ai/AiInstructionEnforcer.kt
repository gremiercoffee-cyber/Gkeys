package com.gremier.gkeys.ai

/**
 * Applies user AI instructions locally when the rule is concrete enough to enforce
 * without an API call (e.g. no trailing period on short single-sentence messages).
 */
object AiInstructionEnforcer {

    fun applyLocal(text: String, instructions: String): String {
        if (instructions.isBlank() || text.isBlank()) return text
        var result = text
        if (wantsNoPeriodOnShortMessages(instructions)) {
            result = removeTrailingPeriodOnShortMessages(result)
        }
        return result
    }

    /** Detects instructions like "don't add periods to short one-line messages". */
    private fun wantsNoPeriodOnShortMessages(instructions: String): Boolean {
        val lower = instructions.lowercase()
        val mentionsPeriod = lower.contains("period") || lower.contains("full stop")
        if (!mentionsPeriod) return false
        val restrictive = listOf(
            "don't", "do not", "dont", "never", "no ", "without", "avoid", "skip",
            "short", "one line", "one-line", "one sentence", "one-sentence",
            "single line", "single sentence", "brief",
        )
        return restrictive.any { lower.contains(it) }
    }

    /**
     * Strips a trailing period from short, single-sentence lines/paragraphs.
     * Leaves periods on longer multi-sentence paragraphs.
     */
    fun removeTrailingPeriodOnShortMessages(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text

        val paragraphs = trimmed.split("\n\n")
        if (paragraphs.size == 1) {
            return processParagraph(paragraphs[0].trim())
        }
        return paragraphs.joinToString("\n\n") { processParagraph(it.trim()) }
    }

    private fun processParagraph(paragraph: String): String {
        if (paragraph.isEmpty()) return paragraph
        val lines = paragraph.split('\n')
        if (lines.size == 1) {
            return stripTrailingPeriodIfShortSingleSentence(lines[0].trim())
        }
        return lines.joinToString("\n") { stripTrailingPeriodIfShortSingleSentence(it.trim()) }
    }

    private fun stripTrailingPeriodIfShortSingleSentence(line: String): String {
        if (line.isEmpty() || !line.endsWith('.')) return line
        if (line.endsWith("..")) return line

        val body = line.dropLast(1).trimEnd()
        if (body.isEmpty()) return line

        // Multiple sentences in this line (e.g. "Hi. See you.") — keep periods.
        if (hasMultipleSentences(body)) return line

        // Long paragraph-style block — keep period (user rule targets short messages).
        if (body.length > 280) return line

        return body
    }

    private fun hasMultipleSentences(text: String): Boolean {
        // Sentence boundary: . ! ? followed by space and a letter/number
        if (Regex("""[.!?]\s+\p{L}""").containsMatchIn(text)) return true
        // Two or more terminal punctuations separated by content
        val terminals = Regex("""[.!?]""").findAll(text).count()
        return terminals >= 1 && text.contains('\n')
    }
}
