package com.gremier.gkeys.ime.suggestions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserWordsDao {

    @Query("SELECT word, frequency FROM user_words WHERE language = :language ORDER BY frequency DESC LIMIT :limit")
    suspend fun topWords(language: String, limit: Int = 500): List<UserWordRow>

    @Query("SELECT * FROM user_words WHERE word = :word AND language = :language LIMIT 1")
    suspend fun word(word: String, language: String): UserWordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserWordEntity)

    @Query(
        """
        UPDATE user_words SET frequency = frequency + 1, updatedAt = :updatedAt
        WHERE word = :word AND language = :language
        """
    )
    suspend fun incrementFrequency(word: String, language: String, updatedAt: Long)

    data class UserWordRow(val word: String, val frequency: Int)
}
