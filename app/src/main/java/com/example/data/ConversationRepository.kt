package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ConversationRepository(private val dao: ConversationDao) {

    val allMessages: Flow<List<ConversationMessageEntity>> = dao.getAllMessages()
    val bookmarkedMessages: Flow<List<ConversationMessageEntity>> = dao.getBookmarkedMessages()

    suspend fun addMessage(role: String, text: String, audioDurationMs: Long = 0): Long =
        withContext(Dispatchers.IO) {
            val entity = ConversationMessageEntity(
                role = role,
                text = text,
                audioDurationMs = audioDurationMs
            )
            dao.insertMessage(entity)
        }

    suspend fun toggleBookmark(id: Long, isBookmarked: Boolean) = withContext(Dispatchers.IO) {
        dao.setBookmark(id, isBookmarked)
    }

    suspend fun deleteMessage(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }
}
