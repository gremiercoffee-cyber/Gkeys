package com.gremier.gkeys.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gremier.gkeys.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class GkeysClipboardManager(
    private val context: Context,
    private val overlayContainer: ViewGroup,
    private val previewView: TextView,
    private val onPaste: (String) -> Unit,
    private val onVibrate: () -> Unit
) {
    companion object {
        private const val MAX_ITEMS = 20
    }

    private val dao = ClipboardDatabase.getInstance(context).clipboardDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayView: View? = null
    private var isListening = false
    private var lastCapturedText: String? = null

    private val systemClipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isListening) return@OnPrimaryClipChangedListener
        val clip = systemClipboard.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isNotBlank() && text != lastCapturedText) {
            lastCapturedText = text
            scope.launch { addItem(text) }
        }
    }

    fun startListening() {
        if (isListening) return
        isListening = true
        systemClipboard.addPrimaryClipChangedListener(clipListener)
        scope.launch {
            dao.observeAll().collectLatest { items ->
                updatePreview(items)
                refreshOverlay(items)
            }
        }
        scope.launch {
            val clip = systemClipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) addItem(text)
            }
        }
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        systemClipboard.removePrimaryClipChangedListener(clipListener)
    }

    fun showPanel() {
        overlayContainer.visibility = View.VISIBLE
        if (overlayView != null) {
            overlayView?.visibility = View.VISIBLE
            scope.launch {
                val items = withContext(Dispatchers.IO) { dao.getAllOnce() }
                refreshOverlay(items)
            }
            return
        }
        val panel = LayoutInflater.from(context).inflate(R.layout.clipboard_overlay, overlayContainer, false)
        panel.findViewById<View>(R.id.btn_close_clipboard).setOnClickListener { hidePanel() }
        overlayContainer.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlayView = panel
        overlayContainer.visibility = View.VISIBLE
        scope.launch {
            val items = withContext(Dispatchers.IO) { dao.getAllOnce() }
            refreshOverlay(items)
        }
    }

    fun hidePanel() {
        overlayView?.visibility = View.GONE
        overlayContainer.visibility = View.GONE
    }

    fun refreshPreview() {
        scope.launch {
            val items = withContext(Dispatchers.IO) { dao.getAllOnce() }
            updatePreview(items)
        }
    }

    fun destroy() {
        stopListening()
        scope.cancel()
        overlayView = null
    }

    private suspend fun addItem(text: String) {
        withContext(Dispatchers.IO) {
            val existing = dao.findByText(text)
            if (existing != null) {
                dao.update(existing.copy(timestamp = System.currentTimeMillis()))
            } else {
                dao.insert(ClipboardItem(text = text))
                trimHistory()
            }
        }
    }

    private suspend fun trimHistory() {
        while (dao.countAll() > MAX_ITEMS) {
            val oldest = dao.getOldestUnpinned() ?: break
            dao.delete(oldest)
        }
    }

    private fun updatePreview(items: List<ClipboardItem>) {
        val latest = items.firstOrNull()
        if (latest != null) {
            val text = latest.text
            previewView.text = if (text.length > 30) text.take(30) + "…" else text
            previewView.tag = text
        } else {
            previewView.text = "Tap for clipboard"
            previewView.tag = null
        }
        previewView.visibility = View.VISIBLE
    }

    private fun refreshOverlay(items: List<ClipboardItem>) {
        val panel = overlayView ?: return
        val pinned = items.filter { it.isPinned }
        val recent = items.filter { !it.isPinned }

        val pinnedHeader = panel.findViewById<TextView>(R.id.tv_pinned_header)
        val recentHeader = panel.findViewById<TextView>(R.id.tv_recent_header)
        val emptyView = panel.findViewById<TextView>(R.id.tv_clipboard_empty)
        val rvPinned = panel.findViewById<RecyclerView>(R.id.rv_pinned)
        val rvRecent = panel.findViewById<RecyclerView>(R.id.rv_recent)

        val isEmpty = items.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recentHeader.visibility = if (isEmpty) View.GONE else View.VISIBLE
        rvRecent.visibility = if (isEmpty) View.GONE else View.VISIBLE

        pinnedHeader.visibility = if (pinned.isEmpty()) View.GONE else View.VISIBLE
        rvPinned.visibility = if (pinned.isEmpty()) View.GONE else View.VISIBLE

        bindList(rvPinned, pinned)
        bindList(rvRecent, recent)
    }

    private fun bindList(recyclerView: RecyclerView, items: List<ClipboardItem>) {
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager = LinearLayoutManager(context)
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                override fun onMove(
                    rv: RecyclerView,
                    vh: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ) = false

                override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                    val item = (vh as ItemAdapter.VH).boundItem
                    scope.launch(Dispatchers.IO) { dao.delete(item) }
                    onVibrate()
                }
            }).attachToRecyclerView(recyclerView)
        }
        (recyclerView.adapter as? ItemAdapter)?.update(items)
            ?: run { recyclerView.adapter = ItemAdapter(items) }
    }

    private inner class ItemAdapter(
        private var items: List<ClipboardItem>
    ) : RecyclerView.Adapter<ItemAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            lateinit var boundItem: ClipboardItem
        }

        fun update(newItems: List<ClipboardItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_clipboard, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.boundItem = item
            holder.itemView.findViewById<TextView>(R.id.tv_clip_text).text = item.text
            val pinBtn = holder.itemView.findViewById<ImageButton>(R.id.btn_pin)
            pinBtn.setImageResource(
                if (item.isPinned) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            pinBtn.setColorFilter(
                if (item.isPinned) 0xFF4A9EFF.toInt() else 0xFF9CA3AF.toInt()
            )
            pinBtn.setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    dao.update(item.copy(isPinned = !item.isPinned, timestamp = System.currentTimeMillis()))
                }
                onVibrate()
            }
            holder.itemView.findViewById<ImageButton>(R.id.btn_delete).setOnClickListener {
                scope.launch(Dispatchers.IO) { dao.delete(item) }
                onVibrate()
            }
            holder.itemView.setOnClickListener {
                onPaste(item.text)
                hidePanel()
                onVibrate()
            }
        }

        override fun getItemCount() = items.size
    }
}
