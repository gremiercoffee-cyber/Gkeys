package com.gremier.gkeys.ime.suggestions

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UserWordEntity::class],
    version = 2,
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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_words ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_words ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                database.execSQL("ALTER TABLE user_words ADD COLUMN typedCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_words ADD COLUMN swipeCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_words ADD COLUMN selectedCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_words ADD COLUMN autocorrectAcceptedCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_words ADD COLUMN correctionAcceptedCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_words ADD COLUMN rejectedCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE user_words SET updatedAt = addedAt, confidence = frequency")
            }
        }
    }
}
