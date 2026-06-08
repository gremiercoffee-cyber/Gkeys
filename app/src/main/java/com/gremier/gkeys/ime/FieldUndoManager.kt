package com.gremier.gkeys.ime

import android.view.inputmethod.InputConnection

/** Tracks recent field edits so the toolbar undo button can reverse them. */
class FieldUndoManager(
    private val maxEntries: Int = 50
) {
    private sealed class UndoOp {
        data class Inserted(val text: String) : UndoOp()
        data class Deleted(val text: String) : UndoOp()
        data class Cleared(val text: String) : UndoOp()
    }

    private val stack = ArrayDeque<UndoOp>()

    fun clear() {
        stack.clear()
    }

    fun canUndo(): Boolean = stack.isNotEmpty()

    fun recordInsert(text: String) {
        if (text.isEmpty()) return
        stack.addLast(UndoOp.Inserted(text))
        trim()
    }

    fun recordDelete(deleted: String) {
        if (deleted.isEmpty()) return
        stack.addLast(UndoOp.Deleted(deleted))
        trim()
    }

    fun recordClear(fullText: String) {
        if (fullText.isEmpty()) return
        stack.addLast(UndoOp.Cleared(fullText))
        trim()
    }

    fun undo(ic: InputConnection): Boolean {
        return when (val op = stack.removeLastOrNull()) {
            is UndoOp.Inserted -> {
                ic.deleteSurroundingText(op.text.length, 0)
                true
            }
            is UndoOp.Deleted -> {
                ic.commitText(op.text, 1)
                true
            }
            is UndoOp.Cleared -> {
                ic.commitText(op.text, 1)
                true
            }
            null -> false
        }
    }

    private fun trim() {
        while (stack.size > maxEntries) {
            stack.removeFirst()
        }
    }
}
