package com.gremier.gkeys.ime.suggestions

import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class SuggestionStripController(
    private val strip: LinearLayout,
    private val leftView: TextView,
    private val centerView: TextView,
    private val dismissButton: ImageButton,
    private val dividerLeft: View,
    private val dividerRight: View,
    private val onSuggestionPicked: (String) -> Unit,
    private val onDismiss: () -> Unit,
) {
    init {
        val listener = View.OnClickListener { v ->
            val word = (v as? TextView)?.text?.toString()?.trim().orEmpty()
            if (word.isNotEmpty()) onSuggestionPicked(word)
        }
        leftView.setOnClickListener(listener)
        centerView.setOnClickListener(listener)
        dismissButton.setOnClickListener {
            onDismiss()
        }
    }

    fun setActive(active: Boolean) {
        strip.visibility = if (active) View.VISIBLE else View.GONE
        dismissButton.visibility = if (active) View.VISIBLE else View.GONE
    }

    fun render(model: SuggestionStripModel, primaryColor: Int, secondaryColor: Int) {
        bindChip(leftView, model.left, secondaryColor)
        bindChip(centerView, model.center, primaryColor, primary = true)

        val hasLeft = !leftView.text.isNullOrBlank()
        val hasCenter = !centerView.text.isNullOrBlank()
        dividerLeft.visibility = if (hasLeft && hasCenter) View.VISIBLE else View.GONE
        dividerRight.visibility = if (hasCenter) View.VISIBLE else View.GONE
        dismissButton.visibility = View.VISIBLE
    }

    fun clear() {
        leftView.text = ""
        centerView.text = ""
        leftView.visibility = View.INVISIBLE
        centerView.visibility = View.INVISIBLE
        dividerLeft.visibility = View.GONE
        dividerRight.visibility = View.GONE
        dismissButton.visibility = View.GONE
    }

    private fun bindChip(
        view: TextView,
        chip: SuggestionChip?,
        color: Int,
        primary: Boolean = false,
    ) {
        if (chip == null || chip.text.isBlank()) {
            view.text = ""
            view.visibility = View.INVISIBLE
            return
        }
        view.visibility = View.VISIBLE
        view.setTextColor(color)
        view.paint.isFakeBoldText = primary || chip.isPrimary
        view.textSize = if (primary) 16f else 15f
        view.setTypeface(view.typeface, android.graphics.Typeface.NORMAL)
        if (chip.isCorrection) {
            val span = SpannableString(chip.text)
            span.setSpan(UnderlineSpan(), 0, chip.text.length, 0)
            view.text = span
        } else {
            view.text = chip.text
        }
    }
}
