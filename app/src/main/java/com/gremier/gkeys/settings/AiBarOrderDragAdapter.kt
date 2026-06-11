package com.gremier.gkeys.settings

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.gremier.gkeys.R
import com.gremier.gkeys.ime.AiBarLayout
import com.google.android.material.switchmaterial.SwitchMaterial

data class AiBarOrderRow(
    val id: String,
    var enabled: Boolean,
    val canToggle: Boolean,
    val isFixed: Boolean,
)

class AiBarOrderDragAdapter(
    private var rows: MutableList<AiBarOrderRow>,
    private var itemTouchHelper: ItemTouchHelper? = null,
    private val onOrderChanged: (List<String>) -> Unit,
    private val onToggleChanged: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<AiBarOrderDragAdapter.ViewHolder>() {

    private var suppressToggleCallback = false

    fun attachTouchHelper(helper: ItemTouchHelper) {
        itemTouchHelper = helper
    }

    fun updateRows(newRows: List<AiBarOrderRow>) {
        rows = newRows.toMutableList()
        notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in rows.indices || to !in rows.indices) return
        val item = rows.removeAt(from)
        rows.add(to, item)
        notifyItemMoved(from, to)
        onOrderChanged(rows.map { it.id })
    }

    fun isFixedPosition(position: Int): Boolean =
        position in rows.indices && rows[position].isFixed

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_bar_order_drag, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.label.text = AiBarLayout.label(row.id)
        holder.icon.setImageResource(AiBarLayout.iconRes(row.id))
        holder.icon.alpha = if (row.enabled || row.isFixed) 1f else 0.35f

        when {
            !row.canToggle -> {
                holder.toggle.visibility = View.GONE
            }
            else -> {
                holder.toggle.visibility = View.VISIBLE
                suppressToggleCallback = true
                holder.toggle.isEnabled = true
                holder.toggle.isChecked = row.enabled
                suppressToggleCallback = false
                holder.toggle.setOnCheckedChangeListener { _, checked ->
                    if (suppressToggleCallback) return@setOnCheckedChangeListener
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        rows[pos].enabled = checked
                    }
                    holder.icon.alpha = if (checked) 1f else 0.35f
                    onToggleChanged(row.id, checked)
                }
            }
        }

        val draggable = !row.isFixed
        holder.dragHandle.alpha = if (draggable) 1f else 0.25f
        holder.itemView.setOnLongClickListener {
            if (draggable) itemTouchHelper?.startDrag(holder)
            draggable
        }
        holder.dragHandle.setOnTouchListener { _, event ->
            if (draggable && event.actionMasked == MotionEvent.ACTION_DOWN) {
                itemTouchHelper?.startDrag(holder)
            }
            false
        }
        holder.icon.setOnLongClickListener {
            if (draggable) itemTouchHelper?.startDrag(holder)
            draggable
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: ImageView = view.findViewById(R.id.iv_drag_handle)
        val icon: ImageView = view.findViewById(R.id.iv_order_icon)
        val label: TextView = view.findViewById(R.id.tv_order_label)
        val toggle: SwitchMaterial = view.findViewById(R.id.switch_order_enabled)
    }
}
