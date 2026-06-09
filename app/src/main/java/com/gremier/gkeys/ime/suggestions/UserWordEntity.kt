package com.gremier.gkeys.ime.suggestions

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_words")
data class UserWordEntity(
    @PrimaryKey val word: String,
    val language: String,
    val frequency: Int,
    val addedAt: Long,
)
