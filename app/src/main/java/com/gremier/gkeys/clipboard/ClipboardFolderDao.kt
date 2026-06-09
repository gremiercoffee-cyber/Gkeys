package com.gremier.gkeys.clipboard

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardFolderDao {

    @Query("SELECT * FROM clipboard_folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ClipboardFolder>>

    @Query("SELECT * FROM clipboard_folders ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllOnce(): List<ClipboardFolder>

    @Insert
    suspend fun insert(folder: ClipboardFolder): Long

    @Update
    suspend fun update(folder: ClipboardFolder)

    @Delete
    suspend fun delete(folder: ClipboardFolder)

    @Query("UPDATE clipboard_items SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderAssignments(folderId: Long)
}
