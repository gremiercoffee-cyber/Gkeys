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
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.gremier.gkeys.R
import com.gremier.gkeys.ui.GkeysTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

class GkeysClipboardManager(
    private val context: Context,
    themeContext: Context,
    private val overlayContainer: ViewGroup,
    private val keyboardPanelHost: ViewGroup,
    private val previewTapTarget: View,
    private val previewView: TextView,
    private val previewImage: ImageView,
    private val previewHint: TextView,
    private val onPasteItem: (ClipboardItem) -> Unit,
    private val onVibrate: () -> Unit,
    private val onPanelOpen: () -> Unit = {},
    private val onPanelClose: () -> Unit = {},
    private val onTextPromptOpen: () -> Unit = {},
    private val onTextPromptClose: () -> Unit = {},
    private val shouldPreservePreviewHint: () -> Boolean = { false }
) {
    companion object {
        private const val TAG = "GkeysClipboard"
        /** Unpinned text and copied images expire after this duration. */
        const val UNPINNED_RETENTION_MS = 15 * 60 * 1000L
        /** Unpinned screenshots expire after this duration. */
        const val SCREENSHOT_RETENTION_MS = 5 * 60 * 1000L
        /** AI bar preview only shows recent unpinned copies/screenshots within this window. */
        const val PREVIEW_MAX_AGE_MS = 5 * 60 * 1000L
        private const val MAX_UNPINNED_ITEMS = 2000
        private const val PREFS = "gkeys_clipboard"
        private const val KEY_BLOCKED = "blocked_texts"
        private const val MAX_BLOCKED = 100
    }

    private val dao = ClipboardDatabase.getInstance(context).clipboardDao()
    private val folderDao = ClipboardDatabase.getInstance(context).clipboardFolderDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var themeContext: Context = themeContext
    private var overlayView: View? = null
    private var isListening = false
    private var lastCapturedKey: String? = null
    private var previewItem: ClipboardItem? = null
    private var observeJob: Job? = null
    private var cachedFolders: List<ClipboardFolder> = emptyList()
    private var modalOverlay: View? = null
    private var textPromptView: View? = null
    private var textPromptBuffer = StringBuilder()
    private var textPromptHint: String = ""
    private var textPromptDisplay: TextView? = null
    private var clipboardPanelOpen = false

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val blockedKeys: MutableSet<String> by lazy {
        loadBlockedKeys().toMutableSet()
    }

    private val systemClipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val screenshotMonitor = ScreenshotMonitor(context) { uri ->
        scope.launch { addScreenshotItem(uri) }
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

    fun updateTheme(dark: Boolean) {
        themeContext = GkeysTheme.wrap(context, dark)
        applyPreviewTheme()
        if (isPanelOpen()) {
            hidePanel()
            showPanel()
        }
    }

    private fun applyPreviewTheme() {
        previewView.setTextColor(themeContext.getColor(R.color.gkeys_text_primary))
        previewHint.setTextColor(themeContext.getColor(R.color.gkeys_text_secondary))
    }

    private fun themedInflater(): LayoutInflater = LayoutInflater.from(themeContext)

    fun setupPreviewInteractions() {
        applyPreviewTheme()
        previewTapTarget.setOnClickListener { onPreviewTap() }
        previewTapTarget.setOnLongClickListener {
            showPanel()
            onVibrate()
            true
        }
    }

    fun onPreviewTap() {
        val item = previewItem ?: return
        if (item.id != 0L && !isRetained(item)) return
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
        scope.launch { purgeExpiredUnpinned() }
        observeJob?.cancel()
        observeJob = scope.launch {
            try {
                combine(dao.observeAll(), folderDao.observeAll()) { items, folders ->
                    items to folders
                }.collectLatest { (items, folders) ->
                    purgeExpiredUnpinned()
                    cachedFolders = folders
                    val visible = items.filter { isRetained(it) }
                    updatePreview(visible)
                    refreshOverlay(visible, folders)
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
        clipboardPanelOpen = true
        onPanelOpen()
        if (overlayView != null) {
            overlayView?.visibility = View.VISIBLE
            scope.launch { refreshFromStore() }
            return
        }
        val panel = themedInflater().inflate(R.layout.clipboard_overlay, overlayContainer, false)
        panel.layoutDirection = View.LAYOUT_DIRECTION_LTR
        panel.findViewById<View>(R.id.btn_close_clipboard).setOnClickListener {
            hidePanel()
            onVibrate()
        }
        panel.findViewById<View>(R.id.btn_new_folder).setOnClickListener {
            onVibrate()
            showCreateFolderDialog()
        }
        overlayContainer.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        overlayView = panel
        scope.launch { refreshFromStore() }
    }

    fun hidePanel() {
        dismissModalOverlay()
        dismissTextPrompt()
        overlayView?.visibility = View.GONE
        overlayContainer.visibility = View.GONE
        clipboardPanelOpen = false
        onPanelClose()
    }

    fun isPanelOpen(): Boolean = clipboardPanelOpen

    fun isTextPromptActive(): Boolean = textPromptView != null

    fun textPromptValue(): String = textPromptBuffer.toString().trim()

    fun insertTextPromptText(text: String) {
        if (text.isEmpty() || textPromptView == null) return
        textPromptBuffer.append(text)
        refreshTextPromptDisplay()
    }

    fun deleteTextPromptChar() {
        if (textPromptBuffer.isEmpty() || textPromptView == null) return
        textPromptBuffer.deleteCharAt(textPromptBuffer.length - 1)
        refreshTextPromptDisplay()
    }

    private fun refreshTextPromptDisplay() {
        val display = textPromptDisplay ?: return
        if (textPromptBuffer.isEmpty()) {
            display.text = textPromptHint
            display.setTextColor(themeContext.getColor(R.color.gkeys_text_muted))
        } else {
            display.text = textPromptBuffer.toString()
            display.setTextColor(themeContext.getColor(R.color.gkeys_text_primary))
        }
    }

    fun refreshPreview() {
        scope.launch { refreshFromStore() }
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

    private suspend fun refreshFromStore() {
        val items = visibleItems()
        val folders = withContext(Dispatchers.IO) { folderDao.getAllOnce() }
        cachedFolders = folders
        updatePreview(items)
        refreshOverlay(items, folders)
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

    private suspend fun addScreenshotItem(uri: Uri) {
        val uriStr = uri.toString()
        if (isBlockedKey("img:$uriStr")) return
        withContext(Dispatchers.IO) {
            val existing = dao.findByImageUri(uriStr)
            if (existing != null) {
                dao.update(
                    existing.copy(
                        itemType = ClipboardItem.TYPE_SCREENSHOT,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } else {
                dao.insert(
                    ClipboardItem(
                        imageUri = uriStr,
                        itemType = ClipboardItem.TYPE_SCREENSHOT
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

    private suspend fun pinItem(item: ClipboardItem, folderId: Long?) {
        withContext(Dispatchers.IO) {
            try {
                dao.pinById(item.id, folderId)
            } catch (e: Exception) {
                Log.e(TAG, "pinItem failed for id=${item.id}", e)
                throw e
            }
        }
    }

    private suspend fun unpinItem(item: ClipboardItem) {
        withContext(Dispatchers.IO) {
            try {
                dao.unpinById(item.id)
            } catch (e: Exception) {
                Log.e(TAG, "unpinItem failed for id=${item.id}", e)
                throw e
            }
        }
    }

    private suspend fun moveItemToFolder(item: ClipboardItem, folderId: Long?) {
        withContext(Dispatchers.IO) {
            try {
                dao.setFolderById(item.id, folderId)
            } catch (e: Exception) {
                Log.e(TAG, "moveItemToFolder failed for id=${item.id}", e)
                throw e
            }
        }
    }

    private suspend fun setPinLabel(item: ClipboardItem, label: String?) {
        withContext(Dispatchers.IO) {
            try {
                dao.setPinLabelById(item.id, label?.trim()?.takeIf { it.isNotEmpty() })
            } catch (e: Exception) {
                Log.e(TAG, "setPinLabel failed for id=${item.id}", e)
                throw e
            }
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

    private suspend fun purgeExpiredUnpinned() {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.deleteExpiredScreenshots(now - SCREENSHOT_RETENTION_MS)
            dao.deleteExpiredRegular(now - UNPINNED_RETENTION_MS)
        }
    }

    private suspend fun visibleItems(): List<ClipboardItem> =
        withContext(Dispatchers.IO) {
            dao.getAllOnce().filter { isRetained(it) }
        }

    private fun isRetained(item: ClipboardItem): Boolean {
        if (item.isPinned) return true
        val age = System.currentTimeMillis() - item.timestamp
        val limit = if (item.isScreenshot) SCREENSHOT_RETENTION_MS else UNPINNED_RETENTION_MS
        return age <= limit
    }

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

    /** Preview shows only unpinned items copied or screenshotted within [PREVIEW_MAX_AGE_MS]. */
    private fun isPreviewEligible(item: ClipboardItem): Boolean {
        if (item.isPinned) return false
        return System.currentTimeMillis() - item.timestamp <= PREVIEW_MAX_AGE_MS
    }

    private fun updatePreview(items: List<ClipboardItem>) {
        val latest = resolvePreviewItem(items)

        previewItem = latest
        if (shouldPreservePreviewHint()) return
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

    /** Most recent unpinned copy or screenshot from the last five minutes. */
    private fun resolvePreviewItem(items: List<ClipboardItem>): ClipboardItem? =
        items.filter { isPreviewEligible(it) }.maxByOrNull { it.timestamp }

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

    private fun refreshOverlay(items: List<ClipboardItem>, folders: List<ClipboardFolder>) {
        val panel = overlayView ?: return

        val recent = items.filter { !it.isPinned && !it.isScreenshot }
            .sortedByDescending { it.timestamp }
        val screenshots = items.filter { !it.isPinned && it.isScreenshot }
        val pinnedRoot = items.filter { it.isPinned && it.folderId == null }
        val pinnedByFolder = folders.associateWith { folder ->
            items.filter { it.isPinned && it.folderId == folder.id }
        }

        val recentContainer = panel.findViewById<GridLayout>(R.id.recent_cards)
        val screenshotContainer = panel.findViewById<GridLayout>(R.id.screenshot_cards)
        val pinnedContainer = panel.findViewById<GridLayout>(R.id.pinned_cards)
        val folderSections = panel.findViewById<LinearLayout>(R.id.folder_sections)

        val recentHeader = panel.findViewById<TextView>(R.id.tv_recent_header)
        val screenshotsHeader = panel.findViewById<TextView>(R.id.tv_screenshots_header)
        val screenshotsHint = panel.findViewById<TextView>(R.id.tv_screenshots_hint)
        val dividerScreenshots = panel.findViewById<View>(R.id.divider_screenshots)
        val pinnedHeader = panel.findViewById<TextView>(R.id.tv_pinned_header)
        val dividerPinned = panel.findViewById<View>(R.id.section_divider)
        val emptyView = panel.findViewById<TextView>(R.id.tv_clipboard_empty)

        recentContainer.removeAllViews()
        screenshotContainer.removeAllViews()
        pinnedContainer.removeAllViews()
        folderSections.removeAllViews()

        recent.forEachIndexed { index, item ->
            recentContainer.addView(createCardView(recentContainer, item, index))
        }
        recentHeader.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE

        val screenshotColumns = 5
        screenshotContainer.columnCount = screenshotColumns
        screenshots.forEachIndexed { index, item ->
            screenshotContainer.addView(
                createCardView(
                    screenshotContainer,
                    item,
                    index,
                    columns = screenshotColumns,
                    portraitThumbnail = true
                )
            )
        }
        val showScreenshots = screenshots.isNotEmpty()
        dividerScreenshots.visibility =
            if (recent.isNotEmpty() && showScreenshots) View.VISIBLE else View.GONE
        screenshotsHeader.visibility = if (showScreenshots) View.VISIBLE else View.GONE
        screenshotsHint.visibility = if (showScreenshots) View.VISIBLE else View.GONE

        pinnedRoot.forEachIndexed { index, item ->
            pinnedContainer.addView(createCardView(pinnedContainer, item, index))
        }

        folders.forEach { folder ->
            val folderItems = pinnedByFolder[folder].orEmpty()
            if (folderItems.isEmpty()) return@forEach
            folderSections.addView(createFolderSection(folder, folderItems))
        }

        val hasPinnedRoot = pinnedRoot.isNotEmpty()
        val hasFolderItems = pinnedByFolder.values.any { it.isNotEmpty() }
        val hasPinned = hasPinnedRoot || hasFolderItems
        val hasAbovePinned = recent.isNotEmpty() || showScreenshots
        dividerPinned.visibility = if (hasAbovePinned && hasPinned) View.VISIBLE else View.GONE
        pinnedHeader.visibility = if (hasPinnedRoot) View.VISIBLE else View.GONE

        val isEmpty = items.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun createFolderSection(
        folder: ClipboardFolder,
        items: List<ClipboardItem>
    ): View {
        val density = context.resources.displayMetrics.density
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val header = TextView(context).apply {
            text = folder.name
            setTextColor(themeContext.getColor(R.color.gkeys_accent))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnLongClickListener {
                onVibrate()
                showFolderOptionsDialog(folder)
                true
            }
        }
        section.addView(header)

        val grid = GridLayout(context).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        items.forEachIndexed { index, item ->
            grid.addView(createCardView(grid, item, index))
        }
        section.addView(grid)
        return section
    }

    private fun createCardView(
        parent: GridLayout,
        item: ClipboardItem,
        index: Int,
        columns: Int = 2,
        portraitThumbnail: Boolean = false
    ): View {
        val card = themedInflater().inflate(R.layout.item_clipboard, parent, false)
        val textView = card.findViewById<TextView>(R.id.tv_clip_text)
        val imageView = card.findViewById<ImageView>(R.id.iv_clip_image)
        val labelView = card.findViewById<TextView>(R.id.tv_pin_label)

        if (item.isImage) {
            textView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
            loadThumbnail(imageView, item.imageUri)
        } else {
            imageView.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = item.text
        }

        val pinLabel = item.pinLabel?.trim().orEmpty()
        if (item.isPinned && pinLabel.isNotEmpty()) {
            labelView.visibility = View.VISIBLE
            labelView.text = pinLabel
        } else {
            labelView.visibility = View.GONE
        }

        val density = context.resources.displayMetrics.density
        val margin = (4 * density).toInt()
        card.layoutParams = GridLayout.LayoutParams().apply {
            if (portraitThumbnail) {
                // Small portrait thumbnail (e.g. screenshots) instead of a full-width card.
                width = (52 * density).toInt()
                height = (78 * density).toInt()
                columnSpec = GridLayout.spec(index % columns)
            } else {
                width = 0
                height = (64 * density).toInt()
                columnSpec = GridLayout.spec(index % columns, 1f)
            }
            rowSpec = GridLayout.spec(index / columns)
            setMargins(margin, margin, margin, margin)
        }
        card.setOnClickListener {
            onPasteItem(item)
            hidePanel()
            onVibrate()
        }
        card.setOnLongClickListener { view ->
            onVibrate()
            showItemContextMenu(view, item)
            true
        }
        return card
    }

    private fun showItemContextMenu(anchor: View, item: ClipboardItem) {
        PopupMenu(context, anchor).apply {
            if (item.isPinned) {
                menu.add("Unpin")
                menu.add("Move to folder…")
                if (item.pinLabel.isNullOrBlank()) {
                    menu.add("Add label…")
                } else {
                    menu.add("Edit label…")
                    menu.add("Remove label")
                }
            } else {
                menu.add("Pin")
                menu.add("Pin to folder…")
            }
            menu.add("Delete")
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title.toString()) {
                    "Pin" -> scope.launch {
                        try {
                            pinItem(item, folderId = null)
                        } catch (_: Exception) {
                            showClipboardError("Couldn't pin item")
                        }
                    }
                    "Pin…", "Pin to folder…" -> showFolderPickerForPin(item)
                    "Unpin" -> scope.launch {
                        try {
                            unpinItem(item)
                        } catch (_: Exception) {
                            showClipboardError("Couldn't unpin item")
                        }
                    }
                    "Move to folder…" -> showFolderPickerForMove(item)
                    "Add label…", "Edit label…" -> showPinLabelDialog(item)
                    "Remove label" -> scope.launch {
                        try {
                            setPinLabel(item, null)
                        } catch (_: Exception) {
                            showClipboardError("Couldn't remove label")
                        }
                    }
                    "Delete" -> scope.launch { deleteItem(item) }
                }
                onVibrate()
                true
            }
        }.show()
    }

    private fun showPinLabelDialog(item: ClipboardItem) {
        showInlineTextPrompt(
            title = if (item.pinLabel.isNullOrBlank()) "Add label" else "Edit label",
            confirmLabel = "Save",
            initialText = item.pinLabel.orEmpty(),
            inputHint = "e.g. Address, WiFi password"
        ) { label ->
            scope.launch {
                try {
                    setPinLabel(item, label)
                } catch (_: Exception) {
                    showClipboardError("Couldn't save label")
                }
            }
        }
    }

    private fun showFolderPickerForPin(item: ClipboardItem) {
        showFolderPicker(
            title = "Pin to folder",
            includeNone = true,
            noneLabel = "Pinned (no folder)",
            onSelected = { folderId ->
                scope.launch {
                    try {
                        pinItem(item, folderId)
                    } catch (_: Exception) {
                        showClipboardError("Couldn't pin item")
                    }
                }
            }
        )
    }

    private fun showFolderPickerForMove(item: ClipboardItem) {
        showFolderPicker(
            title = "Move to folder",
            includeNone = true,
            noneLabel = "Pinned (no folder)",
            onSelected = { folderId ->
                scope.launch { moveItemToFolder(item, folderId) }
            }
        )
    }

    private fun showFolderPicker(
        title: String,
        includeNone: Boolean,
        noneLabel: String,
        onSelected: (folderId: Long?) -> Unit
    ) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (includeNone) {
            options.add(noneLabel to { onSelected(null) })
        }
        cachedFolders.forEach { folder ->
            options.add(folder.name to { onSelected(folder.id) })
        }
        options.add("New folder…" to {
            showCreateFolderDialog { newFolderId -> onSelected(newFolderId) }
        })
        showInlineOptionPicker(title, options)
    }

    private fun modalHost(): ViewGroup {
        if (overlayView == null) {
            showPanel()
        }
        return overlayView as? ViewGroup ?: overlayContainer
    }

    private fun clearTextPromptState() {
        if (textPromptView != null) {
            textPromptView = null
            textPromptDisplay = null
            textPromptBuffer.clear()
            textPromptHint = ""
            onTextPromptClose()
        }
    }

    private fun dismissTextPrompt() {
        textPromptView?.let { prompt ->
            (prompt.parent as? ViewGroup)?.removeView(prompt)
        }
        clearTextPromptState()
    }

    private fun dismissModalOverlay() {
        modalOverlay?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        modalOverlay = null
    }

    private fun showModalOverlay(view: View) {
        dismissModalOverlay()
        modalOverlay = view
        modalHost().addView(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun showInlineOptionPicker(title: String, options: List<Pair<String, () -> Unit>>) {
        val picker = themedInflater()
            .inflate(R.layout.clipboard_folder_picker, modalHost(), false)
        picker.findViewById<TextView>(R.id.tv_picker_title).text = title
        val optionsHost = picker.findViewById<LinearLayout>(R.id.picker_options)
        options.forEach { (label, action) ->
            val row = TextView(context).apply {
                text = label
                setTextColor(themeContext.getColor(R.color.gkeys_text_primary))
                textSize = 15f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onVibrate()
                    dismissModalOverlay()
                    action()
                }
            }
            optionsHost.addView(row)
        }
        picker.findViewById<View>(R.id.btn_picker_cancel).setOnClickListener {
            onVibrate()
            dismissModalOverlay()
        }
        picker.findViewById<View>(R.id.picker_scrim).setOnClickListener {
            dismissModalOverlay()
        }
        picker.findViewById<View>(R.id.picker_card).setOnClickListener { }
        showModalOverlay(picker)
    }

    private fun showInlineTextPrompt(
        title: String,
        confirmLabel: String,
        initialText: String = "",
        inputHint: String = "Folder name",
        onConfirm: (String) -> Unit
    ) {
        dismissTextPrompt()
        val prompt = themedInflater()
            .inflate(R.layout.clipboard_text_prompt, keyboardPanelHost, false)
        prompt.findViewById<TextView>(R.id.tv_prompt_title).text = title
        textPromptHint = inputHint
        textPromptBuffer.clear()
        if (initialText.isNotEmpty()) {
            textPromptBuffer.append(initialText)
        }
        textPromptDisplay = prompt.findViewById(R.id.tv_prompt_input)
        refreshTextPromptDisplay()
        prompt.findViewById<TextView>(R.id.btn_prompt_confirm).text = confirmLabel
        prompt.findViewById<View>(R.id.btn_prompt_cancel).setOnClickListener {
            onVibrate()
            dismissTextPrompt()
        }
        prompt.findViewById<View>(R.id.btn_prompt_confirm).setOnClickListener {
            val value = textPromptBuffer.toString().trim()
            if (value.isBlank()) return@setOnClickListener
            onVibrate()
            dismissTextPrompt()
            onConfirm(value)
        }
        textPromptView = prompt
        onTextPromptOpen()
        keyboardPanelHost.addView(
            prompt,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP,
            ),
        )
    }

    private fun showCreateFolderDialog(onCreated: ((Long?) -> Unit)? = null) {
        showInlineTextPrompt(title = "New folder", confirmLabel = "Create") { name ->
            scope.launch {
                val id = withContext(Dispatchers.IO) {
                    folderDao.insert(ClipboardFolder(name = name))
                }
                onCreated?.invoke(id)
            }
        }
    }

    private fun showFolderOptionsDialog(folder: ClipboardFolder) {
        showInlineOptionPicker(
            title = folder.name,
            options = listOf(
                "Rename" to { showRenameFolderDialog(folder) },
                "Delete folder" to { confirmDeleteFolder(folder) }
            )
        )
    }

    private fun showRenameFolderDialog(folder: ClipboardFolder) {
        showInlineTextPrompt(
            title = "Rename folder",
            confirmLabel = "Save",
            initialText = folder.name
        ) { name ->
            if (name == folder.name) return@showInlineTextPrompt
            scope.launch {
                withContext(Dispatchers.IO) {
                    folderDao.update(folder.copy(name = name))
                }
            }
        }
    }

    private fun confirmDeleteFolder(folder: ClipboardFolder) {
        showInlineOptionPicker(
            title = "Delete \"${folder.name}\"?",
            options = listOf(
                "Delete — items stay pinned" to {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            folderDao.clearFolderAssignments(folder.id)
                            folderDao.delete(folder)
                        }
                    }
                }
            )
        )
    }

    private fun showClipboardError(message: String) {
        try {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}
