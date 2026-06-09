package com.gremier.gkeys.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.gremier.gkeys.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

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
        /** Unpinned text and copied images expire after this duration. */
        const val UNPINNED_RETENTION_MS = 15 * 60 * 1000L
        /** Unpinned screenshots expire after this duration. */
        const val SCREENSHOT_RETENTION_MS = 5 * 60 * 1000L
        private const val MAX_UNPINNED_ITEMS = 2000
        private const val PREFS = "gkeys_clipboard"
        private const val KEY_BLOCKED = "blocked_texts"
        private const val MAX_BLOCKED = 100
    }

    private val dao = ClipboardDatabase.getInstance(context).clipboardDao()
    private val folderDao = ClipboardDatabase.getInstance(context).clipboardFolderDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayView: View? = null
    private var isListening = false
    private var lastCapturedKey: String? = null
    private var previewItem: ClipboardItem? = null
    private var observeJob: Job? = null
    private var cachedFolders: List<ClipboardFolder> = emptyList()

    private val dialogContext by lazy {
        ContextThemeWrapper(context, R.style.Theme_Gkeys)
    }

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
        onPanelOpen()
        if (overlayView != null) {
            overlayView?.visibility = View.VISIBLE
            scope.launch { refreshFromStore() }
            return
        }
        val panel = LayoutInflater.from(context).inflate(R.layout.clipboard_overlay, overlayContainer, false)
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
        overlayView?.visibility = View.GONE
        overlayContainer.visibility = View.GONE
        onPanelClose()
    }

    fun isPanelOpen(): Boolean = overlayContainer.visibility == View.VISIBLE

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
            dao.update(
                item.copy(
                    isPinned = true,
                    folderId = folderId
                )
            )
        }
    }

    private suspend fun unpinItem(item: ClipboardItem) {
        withContext(Dispatchers.IO) {
            dao.update(
                item.copy(
                    isPinned = false,
                    folderId = null
                )
            )
        }
    }

    private suspend fun moveItemToFolder(item: ClipboardItem, folderId: Long?) {
        withContext(Dispatchers.IO) {
            dao.update(item.copy(folderId = folderId))
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

    private fun updatePreview(items: List<ClipboardItem>) {
        val latest = resolvePreviewItem(items)

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

    /** Preview matches the system clipboard when possible — the most recently copied item. */
    private fun resolvePreviewItem(items: List<ClipboardItem>): ClipboardItem? {
        val capture = try {
            readSystemClip()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read clipboard for preview", e)
            null
        }
        if (capture != null && !isBlockedKey(captureKey(capture))) {
            findItemForCapture(items, capture)?.let { return it }
            return when (capture) {
                is ClipCapture.Text -> ClipboardItem(text = capture.text)
                is ClipCapture.Image -> ClipboardItem(
                    imageUri = capture.uri.toString(),
                    itemType = ClipboardItem.TYPE_IMAGE
                )
            }
        }
        return items.maxByOrNull { it.timestamp }
    }

    private fun findItemForCapture(items: List<ClipboardItem>, capture: ClipCapture): ClipboardItem? =
        when (capture) {
            is ClipCapture.Text -> items.find { !it.isImage && it.text == capture.text }
            is ClipCapture.Image -> items.find { it.isImage && it.imageUri == capture.uri.toString() }
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
            setTextColor(0xFF4A9EFF.toInt())
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
            } else {
                menu.add("Pin…")
            }
            menu.add("Delete")
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title.toString()) {
                    "Pin…" -> showFolderPickerForPin(item)
                    "Unpin" -> scope.launch { unpinItem(item) }
                    "Move to folder…" -> showFolderPickerForMove(item)
                    "Delete" -> scope.launch { deleteItem(item) }
                }
                onVibrate()
                true
            }
        }.show()
    }

    private fun showFolderPickerForPin(item: ClipboardItem) {
        showFolderPicker(
            title = "Pin to folder",
            includeNone = true,
            noneLabel = "Pinned (no folder)",
            onSelected = { folderId ->
                scope.launch { pinItem(item, folderId) }
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
        val options = mutableListOf<String>()
        val folderIds = mutableListOf<Long?>()
        if (includeNone) {
            options.add(noneLabel)
            folderIds.add(null)
        }
        cachedFolders.forEach { folder ->
            options.add(folder.name)
            folderIds.add(folder.id)
        }
        options.add("New folder…")

        AlertDialog.Builder(dialogContext)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.lastIndex) {
                    showCreateFolderDialog { newFolderId ->
                        onSelected(newFolderId)
                    }
                } else {
                    onSelected(folderIds[which])
                }
            }
            .show()
    }

    private fun showCreateFolderDialog(onCreated: ((Long?) -> Unit)? = null) {
        val input = EditText(dialogContext).apply {
            hint = "Folder name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(dialogContext)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                scope.launch {
                    val id = withContext(Dispatchers.IO) {
                        folderDao.insert(ClipboardFolder(name = name))
                    }
                    onCreated?.invoke(id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderOptionsDialog(folder: ClipboardFolder) {
        AlertDialog.Builder(dialogContext)
            .setTitle(folder.name)
            .setItems(arrayOf("Rename", "Delete folder")) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> confirmDeleteFolder(folder)
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(folder: ClipboardFolder) {
        val input = EditText(dialogContext).apply {
            setText(folder.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setSelection(folder.name.length)
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(dialogContext)
            .setTitle("Rename folder")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name == folder.name) return@setPositiveButton
                scope.launch {
                    withContext(Dispatchers.IO) {
                        folderDao.update(folder.copy(name = name))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteFolder(folder: ClipboardFolder) {
        AlertDialog.Builder(dialogContext)
            .setTitle("Delete folder?")
            .setMessage("Items in this folder stay pinned and move to Pinned.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        folderDao.clearFolderAssignments(folder.id)
                        folderDao.delete(folder)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
