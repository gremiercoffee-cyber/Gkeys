package com.gremier.gkeys.clipboard

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    @Query("SELECT * FROM clipboard_items ORDER BY isPinned DESC, timestamp DESC")
    fun observeAll(): Flow<List<ClipboardItem>>

    @Query("SELECT * FROM clipboard_items ORDER BY isPinned DESC, timestamp DESC")
    suspend fun getAllOnce(): List<ClipboardItem>

    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): ClipboardItem?

    @Query("SELECT * FROM clipboard_items WHERE text = :text AND itemType = 'text' LIMIT 1")
    suspend fun findByText(text: String): ClipboardItem?

    @Query("SELECT * FROM clipboard_items WHERE imageUri = :uri LIMIT 1")
    suspend fun findByImageUri(uri: String): ClipboardItem?

    @Insert
    suspend fun insert(item: ClipboardItem): Long

    @Update
    suspend fun update(item: ClipboardItem)

    @Delete
    suspend fun delete(item: ClipboardItem)

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM clipboard_items")
    suspend fun countAll(): Int

    @Query("SELECT * FROM clipboard_items WHERE isPinned = 0 ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldestUnpinned(): ClipboardItem?

    @Query("DELETE FROM clipboard_items WHERE text = :text")
    suspend fun deleteByText(text: String)

    @Query("DELETE FROM clipboard_items WHERE imageUri = :uri")
    suspend fun deleteByImageUri(uri: String)
}
