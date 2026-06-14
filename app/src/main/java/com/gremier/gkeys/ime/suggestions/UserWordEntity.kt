package com.gremier.gkeys.ime.suggestions

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_words")
data class UserWordEntity(
    @PrimaryKey val word: String,
    val language: String,
    val frequency: Int,
    val addedAt: Long,
    val updatedAt: Long = addedAt,
    val confidence: Float = frequency.toFloat(),
    val typedCount: Int = 0,
    val swipeCount: Int = 0,
    val selectedCount: Int = 0,
    val autocorrectAcceptedCount: Int = 0,
    val correctionAcceptedCount: Int = 0,
    val rejectedCount: Int = 0,
)
