package com.gremier.gkeys.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import com.gremier.gkeys.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class GkeysClipboardManager(
    private val context: Context,
    private val overlayContainer: ViewGroup,
    private val previewView: TextView,
    private val onPaste: (String) -> Unit,
    private val onVibrate: () -> Unit,
    private val onPanelOpen: () -> Unit = {},
    private val onPanelClose: () -> Unit = {}
) {
    companion object {
        private const val TAG = "GkeysClipboard"
        private const val MAX_ITEMS = 20
        private const val MAX_AGE_MS = 60 * 60 * 1000L
        private const val PREFS = "gkeys_clipboard"
        private const val KEY_BLOCKED = "blocked_texts"
        private const val MAX_BLOCKED = 50
    }

    private val dao = ClipboardDatabase.getInstance(context).clipboardDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayView: View? = null
    private var isListening = false
    private var lastCapturedText: String? = null
    private var observeJob: Job? = null
    private val blockedTexts = loadBlockedTexts().toMutableSet()

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val systemClipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isListening) return@OnPrimaryClipChangedListener
        try {
            val text = readSystemClipText() ?: return@OnPrimaryClipChangedListener
            if (text != lastCapturedText && !isBlocked(text)) {
                lastCapturedText = text
                scope.launch { addItem(text) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard listener failed", e)
        }
    }

    fun startListening() {
        if (isListening) return
        isListening = true
        try {
            systemClipboard.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register clipboard listener", e)
        }
        observeJob?.cancel()
        observeJob = scope.launch {
            try {
                dao.observeAll().collectLatest { items ->
                    updatePreview(items)
                    refreshOverlay(items)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Clipboard observe failed", e)
            }
        }
        scope.launch {
            try {
                purgeExpired()
                val text = readSystemClipText()
                if (text != null && !isBlocked(text)) {
                    lastCapturedText = text
                    addItem(text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Clipboard bootstrap failed", e)
            }
        }
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        observeJob?.cancel()
        observeJob = null
        try {
            systemClipboard.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to unregister clipboard listener", e)
        }
    }

    fun showPanel() {
        overlayContainer.visibility = View.VISIBLE
        onPanelOpen()
        if (overlayView != null) {
            overlayView?.visibility = View.VISIBLE
            scope.launch {
                refreshOverlay(withContext(Dispatchers.IO) {
                    purgeExpired()
                    dao.getAllOnce()
                })
            }
            return
        }
        val panel = LayoutInflater.from(context).inflate(R.layout.clipboard_overlay, overlayContainer, false)
        panel.layoutDirection = View.LAYOUT_DIRECTION_LTR
        panel.findViewById<ImageButton>(R.id.btn_close_clipboard).setOnClickListener {
            hidePanel()
            onVibrate()
        }
        overlayContainer.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlayView = panel
        scope.launch {
            refreshOverlay(withContext(Dispatchers.IO) {
                purgeExpired()
                dao.getAllOnce()
            })
        }
    }

    fun hidePanel() {
        overlayView?.visibility = View.GONE
        overlayContainer.visibility = View.GONE
        onPanelClose()
    }

    fun isPanelOpen(): Boolean = overlayContainer.visibility == View.VISIBLE

    fun refreshPreview() {
        scope.launch {
            updatePreview(withContext(Dispatchers.IO) { dao.getAllOnce() })
        }
    }

    fun destroy() {
        stopListening()
        scope.cancel()
        overlayView = null
    }

    private suspend fun addItem(text: String) {
        if (isBlocked(text)) return
        withContext(Dispatchers.IO) {
            purgeExpired()
            val existing = dao.findByText(text)
            if (existing != null) {
                dao.update(existing.copy(timestamp = System.currentTimeMillis()))
            } else {
                dao.insert(ClipboardItem(text = text))
                trimHistory()
            }
        }
    }

    private suspend fun deleteItem(item: ClipboardItem) {
        withContext(Dispatchers.IO) {
            dao.deleteById(item.id)
            dao.deleteByText(item.text)
            blockText(item.text)
        }
        withContext(Dispatchers.Main) {
            try {
                if (readSystemClipText() == item.text) {
                    clearSystemClipboard()
                    lastCapturedText = ""
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to clear system clipboard", e)
            }
            Unit
        }
    }

    private fun blockText(text: String) {
        blockedTexts.add(text)
        while (blockedTexts.size > MAX_BLOCKED) {
            blockedTexts.remove(blockedTexts.first())
        }
        prefs.edit().putStringSet(KEY_BLOCKED, blockedTexts.toSet()).apply()
    }

    private fun isBlocked(text: String): Boolean = text in blockedTexts

    private fun loadBlockedTexts(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    private suspend fun purgeExpired() {
        dao.deleteOlderThan(System.currentTimeMillis() - MAX_AGE_MS)
    }

    private suspend fun trimHistory() {
        while (dao.countAll() > MAX_ITEMS) {
            val oldest = dao.getOldestUnpinned() ?: break
            dao.delete(oldest)
        }
    }

    private fun readSystemClipText(): String? {
        return try {
            val clip = systemClipboard.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read clipboard", e)
            null
        }
    }

    private fun clearSystemClipboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                systemClipboard.clearPrimaryClip()
            } else {
                @Suppress("DEPRECATION")
                systemClipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to clear clipboard", e)
        }
    }

    private fun updatePreview(items: List<ClipboardItem>) {
        val latest = items.firstOrNull { !it.isPinned } ?: items.firstOrNull()
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
        val recent = items.filter { !it.isPinned }
        val pinned = items.filter { it.isPinned }

        val recentContainer = panel.findViewById<GridLayout>(R.id.recent_cards)
        val pinnedContainer = panel.findViewById<GridLayout>(R.id.pinned_cards)
        val recentHeader = panel.findViewById<TextView>(R.id.tv_recent_header)
        val pinnedHeader = panel.findViewById<TextView>(R.id.tv_pinned_header)
        val divider = panel.findViewById<View>(R.id.section_divider)
        val emptyView = panel.findViewById<TextView>(R.id.tv_clipboard_empty)

        recentContainer.removeAllViews()
        pinnedContainer.removeAllViews()

        val isEmpty = items.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recentHeader.visibility = if (isEmpty) View.GONE else View.VISIBLE

        recent.forEachIndexed { index, item ->
            recentContainer.addView(createCardView(recentContainer, item, index))
        }

        val showPinned = pinned.isNotEmpty()
        divider.visibility = if (recent.isNotEmpty() && showPinned) View.VISIBLE else View.GONE
        pinnedHeader.visibility = if (showPinned) View.VISIBLE else View.GONE

        pinned.forEachIndexed { index, item ->
            pinnedContainer.addView(createCardView(pinnedContainer, item, index))
        }
    }

    private fun createCardView(parent: GridLayout, item: ClipboardItem, index: Int): View {
        val card = LayoutInflater.from(context).inflate(R.layout.item_clipboard, parent, false)
        card.findViewById<TextView>(R.id.tv_clip_text).text = item.text

        val margin = (4 * context.resources.displayMetrics.density).toInt()
        card.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = (72 * context.resources.displayMetrics.density).toInt()
            columnSpec = GridLayout.spec(index % 2, 1f)
            rowSpec = GridLayout.spec(index / 2)
            setMargins(margin, margin, margin, margin)
        }
        card.setOnClickListener {
            onPaste(item.text)
            hidePanel()
            onVibrate()
        }
        card.setOnLongClickListener { view ->
            onVibrate()
            PopupMenu(context, view).apply {
                menu.add(if (item.isPinned) "Unpin" else "Pin")
                menu.add("Delete")
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title.toString()) {
                        "Pin", "Unpin" -> scope.launch(Dispatchers.IO) {
                            dao.update(item.copy(
                                isPinned = !item.isPinned,
                                timestamp = System.currentTimeMillis()
                            ))
                        }
                        "Delete" -> scope.launch { deleteItem(item) }
                    }
                    onVibrate()
                    true
                }
            }.show()
            true
        }
        return card
    }
}
