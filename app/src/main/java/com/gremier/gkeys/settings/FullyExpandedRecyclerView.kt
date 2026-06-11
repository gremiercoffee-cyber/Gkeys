package com.gremier.gkeys.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Measures to the combined height of every row so it can live inside a [android.widget.ScrollView]
 * without clipping items.
 */
class FullyExpandedRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    init {
        isNestedScrollingEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        layoutManager = FullyExpandedLinearLayoutManager(context)
        itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
            supportsChangeAnimations = false
        }
        setHasFixedSize(false)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val adapter = adapter
        if (adapter == null || adapter.itemCount == 0) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        val width = View.MeasureSpec.getSize(widthSpec)
        var height = paddingTop + paddingBottom
        val viewType = adapter.getItemViewType(0)
        for (i in 0 until adapter.itemCount) {
            val holder = adapter.createViewHolder(this, viewType)
            adapter.onBindViewHolder(holder, i)
            measureChild(holder.itemView, widthSpec)
            height += holder.itemView.measuredHeight
        }
        setMeasuredDimension(width, height)
    }

    private fun measureChild(child: View, parentWidthSpec: Int) {
        val lp = child.layoutParams as LayoutParams
        val childWidthSpec = getChildMeasureSpec(
            parentWidthSpec,
            paddingLeft + paddingRight,
            lp.width,
        )
        val childHeightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        child.measure(childWidthSpec, childHeightSpec)
    }
}
