package com.gremier.gkeys.settings

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Expands to the full height of every item so a [RecyclerView] can live inside a [ScrollView]
 * without clipping or stacking rows on top of each other.
 */
class FullyExpandedLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun onMeasure(
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
        widthSpec: Int,
        heightSpec: Int,
    ) {
        val width = View.MeasureSpec.getSize(widthSpec)
        var height = paddingTop + paddingBottom
        val itemCount = state.itemCount
        if (itemCount == 0) {
            setMeasuredDimension(width, height)
            return
        }
        for (i in 0 until itemCount) {
            val view = recycler.getViewForPosition(i)
            measureChildWithMargins(
                view,
                widthSpec,
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            height += getDecoratedMeasuredHeight(view)
            recycler.recycleView(view)
        }
        setMeasuredDimension(width, height)
    }
}
