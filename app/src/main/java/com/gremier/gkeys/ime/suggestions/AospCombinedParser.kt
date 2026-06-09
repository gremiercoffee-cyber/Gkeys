package com.gremier.gkeys.ime.suggestions

/**
 * Parses AOSP LatinIME `.combined` word lists (LineageOS / AnySoftKeyboard sources).
 * Format: header line, then `word=example,f=200` entries with optional leading whitespace.
 */
internal object AospCombinedParser {

    data class ParsedWord(val word: String, val frequency: Int)

    fun parse(text: String): List<ParsedWord> {
        val out = ArrayList<ParsedWord>(80_000)
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("dictionary=")) return@forEach
            if (!line.startsWith("word=")) return@forEach

            val wordPart = line.substringAfter("word=").substringBefore(",")
            if (wordPart.isEmpty()) return@forEach

            val freq = parseFrequency(line) ?: return@forEach
            if (freq <= 0) return@forEach
            if (wordPart.any { !it.isLetter() && it != '\'' && it != '-' }) return@forEach

            out.add(ParsedWord(wordPart, freq))
        }
        return out
    }

    private fun parseFrequency(line: String): Int? {
        val match = FREQ_REGEX.find(line) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private val FREQ_REGEX = Regex("""\bf=(\d+)""")
}
