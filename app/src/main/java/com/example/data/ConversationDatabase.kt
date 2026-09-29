package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ConversationMessageEntity::class], version = 1, exportSchema = false)
abstract class ConversationDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: ConversationDatabase? = null

        fun getInstance(context: Context): ConversationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ConversationDatabase::class.java,
                    "nepali_voice_conversation.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
