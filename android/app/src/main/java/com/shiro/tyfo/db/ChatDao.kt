package com.shiro.tyfo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentConversations(limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :convId")
    fun getConversation(convId: Long): ConversationEntity?

    @Query("SELECT * FROM chat_messages WHERE conversationId = :convId ORDER BY timestamp ASC, id ASC")
    fun getMessages(convId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :convId ORDER BY timestamp ASC, id ASC")
    fun observeMessages(convId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversationId = :convId AND type = 'AGENT_STATUS'")
    fun getStepCount(convId: Long): Int

    @Insert
    fun insertConversation(conv: ConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSessionHistory(history: SessionHistoryEntity)

    @Query("SELECT length(messages) FROM session_history WHERE conversationId = :convId")
    fun getSessionHistoryLength(convId: Long): Int?

    /** 记录的一段：[start] 从 1 开始，按字符计（SQLite substr）。 */
    @Query("SELECT substr(messages, :start, :length) FROM session_history WHERE conversationId = :convId")
    fun getSessionHistoryChunk(convId: Long, start: Int, length: Int): String?

    @Insert
    fun insertMessage(msg: ChatMessageEntity)

    @Query("UPDATE conversations SET status = :status, updatedAt = :updatedAt WHERE id = :convId")
    fun updateStatus(convId: Long, status: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :convId")
    fun deleteConversation(convId: Long)

    @Query("""
        UPDATE chat_messages SET text = :text, timestamp = :updatedAt
        WHERE id = (
            SELECT id FROM chat_messages
            WHERE conversationId = :convId AND type = 'AGENT_STATUS'
            ORDER BY timestamp DESC LIMIT 1
        )
    """)
    fun updateLastAgentStatus(convId: Long, text: String, updatedAt: Long)

    @Query("""
        UPDATE chat_messages SET type = :newType, timestamp = :updatedAt
        WHERE id = (
            SELECT id FROM chat_messages
            WHERE conversationId = :convId AND type = :oldType
            ORDER BY timestamp DESC LIMIT 1
        )
    """)
    fun updateLastMessageType(convId: Long, oldType: String, newType: String, updatedAt: Long)

    @Query("UPDATE conversations SET status = 'stopped', updatedAt = :updatedAt WHERE status = 'running'")
    fun markStaleRunningAsStopped(updatedAt: Long)
}
