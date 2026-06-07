package com.gremier.gkeys.ime

import android.view.inputmethod.InputConnection

data class PolishTarget(
    val text: String,
    val deleteBefore: Int,
    val deleteAfter: Int,
    val hadSelection: Boolean
)

/**
 * Reads and replaces text in the active field for the polish flow.
 */
object InputTextHelper {

    fun extractForPolish(ic: InputConnection): PolishTarget? {
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrBlank()) {
            return PolishTarget(
                text = selected.toString().trim(),
                deleteBefore = 0,
                deleteAfter = 0,
                hadSelection = true
            )
        }

        val before = ic.getTextBeforeCursor(4000, 0)?.toString() ?: return null
        if (before.isBlank()) return null

        val chunk = extractRecentChunk(before).trim()
        if (chunk.isBlank()) return null

        return PolishTarget(
            text = chunk,
            deleteBefore = chunk.length,
            deleteAfter = 0,
            hadSelection = false
        )
    }

    fun replaceText(ic: InputConnection, target: PolishTarget, newText: String) {
        if (target.hadSelection) {
            ic.commitText(newText, 1)
        } else {
            ic.deleteSurroundingText(target.deleteBefore, target.deleteAfter)
            ic.commitText(newText, 1)
        }
    }

    /**
     * Prefer the current paragraph; fall back to the last sentence or recent words.
     */
    private fun extractRecentChunk(before: String): String {
        val paragraphStart = before.lastIndexOf("\n\n")
        if (paragraphStart >= 0) {
            val paragraph = before.substring(paragraphStart + 2)
            if (paragraph.isNotBlank()) return paragraph
        }

        val lineStart = before.lastIndexOf('\n')
        val line = if (lineStart >= 0) before.substring(lineStart + 1) else before
        if (line.isNotBlank()) {
            val sentence = extractLastSentence(line)
            if (sentence.isNotBlank()) return sentence
            return line
        }

        return before.trim()
    }

    private fun extractLastSentence(line: String): String {
        val terminators = listOf(". ", "! ", "? ", "… ")
        var bestStart = 0
        for (term in terminators) {
            val idx = line.lastIndexOf(term)
            if (idx >= 0) bestStart = maxOf(bestStart, idx + term.length)
        }
        return line.substring(bestStart).trim()
    }
}
