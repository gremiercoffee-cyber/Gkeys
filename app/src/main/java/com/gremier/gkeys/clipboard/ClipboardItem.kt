package com.gremier.gkeys.clipboard

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_items")
data class ClipboardItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String = "",
    val imageUri: String? = null,
    val itemType: String = TYPE_TEXT,
    val isPinned: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isImage: Boolean get() = itemType == TYPE_IMAGE && !imageUri.isNullOrBlank()

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
    }
}
