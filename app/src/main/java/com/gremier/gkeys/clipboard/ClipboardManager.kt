package com.gremier.gkeys.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import com.gremier.gkeys.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class GkeysClipboardManager(
    private val context: Context,
    private val overlayContainer: ViewGroup,
    private val previewTapTarget: View,
    private val previewView: TextView,
    private val previewImage: ImageView,
    private val previewHint: TextView,
    private val onPasteItem: (ClipboardItem) -> Unit,
    private val onVibrate: () -> Unit,
    private val onPanelOpen: () -> Unit = {},
    private val onPanelClose: () -> Unit = {}
) {
    companion object {
        private const val TAG = "GkeysClipboard"
        private const val PREVIEW_MAX_AGE_MS = 15 * 60 * 1000L
        private const val MAX_UNPINNED_ITEMS = 2000
        private const val PREFS = "gkeys_clipboard"
        private const val KEY_BLOCKED = "blocked_texts"
        private const val MAX_BLOCKED = 100
    }

    private val dao = ClipboardDatabase.getInstance(context).clipboardDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayView: View? = null
    private var isListening = false
    private var lastCapturedKey: String? = null
    private var previewItem: ClipboardItem? = null
    private var observeJob: Job? = null

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val blockedKeys: MutableSet<String> by lazy {
        loadBlockedKeys().toMutableSet()
    }

    private val systemClipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val screenshotMonitor = ScreenshotMonitor(context) { uri ->
        scope.launch { addImageItem(uri) }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isListening) return@OnPrimaryClipChangedListener
        try {
            val capture = readSystemClip() ?: return@OnPrimaryClipChangedListener
            val key = captureKey(capture)
            if (key != lastCapturedKey && !isBlockedKey(key)) {
                lastCapturedKey = key
                scope.launch {
                    when (capture) {
                        is ClipCapture.Text -> addTextItem(capture.text)
                        is ClipCapture.Image -> addImageItem(capture.uri)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard listener failed", e)
        }
    }

    fun setupPreviewInteractions() {
        previewTapTarget.setOnClickListener { onPreviewTap() }
        previewTapTarget.setOnLongClickListener {
            showPanel()
            onVibrate()
            true
        }
    }

    fun onPreviewTap() {
        val item = previewItem ?: return
        if (!isPreviewEligible(item)) return
        onPasteItem(item)
        onVibrate()
    }

    fun startListening() {
        if (isListening) return
        isListening = true
        try {
            systemClipboard.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register clipboard listener", e)
        }
        screenshotMonitor.start()
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
                val capture = readSystemClip()
                if (capture != null) {
                    val key = captureKey(capture)
                    if (!isBlockedKey(key)) {
                        lastCapturedKey = key
                        when (capture) {
                            is ClipCapture.Text -> addTextItem(capture.text)
                            is ClipCapture.Image -> addImageItem(capture.uri)
                        }
                    }
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
        screenshotMonitor.stop()
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
                refreshOverlay(withContext(Dispatchers.IO) { dao.getAllOnce() })
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
            refreshOverlay(withContext(Dispatchers.IO) { dao.getAllOnce() })
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

    private sealed class ClipCapture {
        data class Text(val text: String) : ClipCapture()
        data class Image(val uri: Uri) : ClipCapture()
    }

    private suspend fun addTextItem(text: String) {
        if (isBlockedKey(text)) return
        withContext(Dispatchers.IO) {
            val existing = dao.findByText(text)
            if (existing != null) {
                dao.update(existing.copy(timestamp = System.currentTimeMillis()))
            } else {
                dao.insert(ClipboardItem(text = text, itemType = ClipboardItem.TYPE_TEXT))
                trimUnpinnedHistory()
            }
        }
    }

    private suspend fun addImageItem(uri: Uri) {
        val uriStr = uri.toString()
        if (isBlockedKey("img:$uriStr")) return
        withContext(Dispatchers.IO) {
            val existing = dao.findByImageUri(uriStr)
            if (existing != null) {
                dao.update(existing.copy(timestamp = System.currentTimeMillis()))
            } else {
                dao.insert(
                    ClipboardItem(
                        imageUri = uriStr,
                        itemType = ClipboardItem.TYPE_IMAGE
                    )
                )
                trimUnpinnedHistory()
            }
        }
    }

    private suspend fun deleteItem(item: ClipboardItem) {
        withContext(Dispatchers.IO) {
            dao.deleteById(item.id)
            if (item.isImage) {
                dao.deleteByImageUri(item.imageUri.orEmpty())
            } else {
                dao.deleteByText(item.text)
            }
            blockItem(item)
        }
        withContext(Dispatchers.Main) {
            try {
                if (!item.isImage && readSystemClipText() == item.text) {
                    clearSystemClipboard()
                    lastCapturedKey = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to clear system clipboard", e)
            }
            Unit
        }
    }

    private fun blockItem(item: ClipboardItem) {
        blockedKeys.add(itemKey(item))
        while (blockedKeys.size > MAX_BLOCKED) {
            blockedKeys.remove(blockedKeys.first())
        }
        prefs.edit().putStringSet(KEY_BLOCKED, blockedKeys.toSet()).apply()
    }

    private fun isBlockedKey(key: String): Boolean = key in blockedKeys

    private fun isBlocked(item: ClipboardItem): Boolean = isBlockedKey(itemKey(item))

    private fun itemKey(item: ClipboardItem): String =
        if (item.isImage) "img:${item.imageUri}" else item.text

    private fun captureKey(capture: ClipCapture): String = when (capture) {
        is ClipCapture.Text -> capture.text
        is ClipCapture.Image -> "img:${capture.uri}"
    }

    private fun loadBlockedKeys(): Set<String> = try {
        prefs.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()
    } catch (e: Throwable) {
        Log.w(TAG, "Unable to load blocked keys", e)
        emptySet()
    }

    private suspend fun trimUnpinnedHistory() {
        while (dao.countAll() > MAX_UNPINNED_ITEMS) {
            val oldest = dao.getOldestUnpinned() ?: break
            dao.delete(oldest)
        }
    }

    private fun isPreviewEligible(item: ClipboardItem): Boolean =
        System.currentTimeMillis() - item.timestamp <= PREVIEW_MAX_AGE_MS

    private fun readSystemClip(): ClipCapture? {
        return try {
            val clip = systemClipboard.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val item = clip.getItemAt(0)
            val uri = item.uri
            if (uri != null && clip.description.hasMimeType("image/*")) {
                return ClipCapture.Image(uri)
            }
            val text = item.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotBlank() }
            if (text != null) ClipCapture.Text(text) else null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read clipboard", e)
            null
        }
    }

    private fun readSystemClipText(): String? =
        (readSystemClip() as? ClipCapture.Text)?.text

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
        val latest = items
            .filter { isPreviewEligible(it) }
            .maxByOrNull { it.timestamp }

        previewItem = latest
        if (latest == null) {
            previewView.visibility = View.GONE
            previewImage.visibility = View.GONE
            previewHint.visibility = View.VISIBLE
            previewHint.text = "Clipboard"
            previewTapTarget.isClickable = false
            previewTapTarget.isLongClickable = true
            return
        }

        previewTapTarget.isClickable = true
        previewTapTarget.isLongClickable = true
        previewHint.visibility = View.GONE

        if (latest.isImage) {
            previewView.visibility = View.GONE
            previewImage.visibility = View.VISIBLE
            loadThumbnail(previewImage, latest.imageUri)
        } else {
            previewImage.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            val text = latest.text
            previewView.text = if (text.length > 14) text.take(14) + "…" else text
        }
    }

    private fun loadThumbnail(imageView: ImageView, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(uriString)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bmp = context.contentResolver.loadThumbnail(uri, Size(96, 96), null)
                imageView.setImageBitmap(bmp)
            } else {
                @Suppress("DEPRECATION")
                imageView.setImageURI(uri)
            }
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.ic_clipboard_toolbar)
        }
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
        recentHeader.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE

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
        val textView = card.findViewById<TextView>(R.id.tv_clip_text)
        val imageView = card.findViewById<ImageView>(R.id.iv_clip_image)

        if (item.isImage) {
            textView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            loadThumbnail(imageView, item.imageUri)
        } else {
            imageView.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = item.text
        }

        val margin = (4 * context.resources.displayMetrics.density).toInt()
        card.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = (64 * context.resources.displayMetrics.density).toInt()
            columnSpec = GridLayout.spec(index % 2, 1f)
            rowSpec = GridLayout.spec(index / 2)
            setMargins(margin, margin, margin, margin)
        }
        card.setOnClickListener {
            onPasteItem(item)
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
