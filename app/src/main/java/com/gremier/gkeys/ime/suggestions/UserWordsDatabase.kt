package com.gremier.gkeys.ime.suggestions

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UserWordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class UserWordsDatabase : RoomDatabase() {
    abstract fun userWordsDao(): UserWordsDao

    companion object {
        @Volatile
        private var instance: UserWordsDatabase? = null

        fun getInstance(context: Context): UserWordsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UserWordsDatabase::class.java,
                    "user_words.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
