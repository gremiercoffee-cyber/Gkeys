package com.gremier.gkeys.clipboard

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_items")
data class ClipboardItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val isPinned: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
