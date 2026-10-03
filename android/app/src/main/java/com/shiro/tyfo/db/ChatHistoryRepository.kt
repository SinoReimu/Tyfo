package com.shiro.tyfo.db

import android.content.Context
import com.shiro.tyfo.ui.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ChatHistoryRepository(context: Context) {
    private companion object {
        /** 每次读的字符数：中文按 UTF-8 三字节算也远小于 2MB。 */
        const val HISTORY_CHUNK_CHARS = 256 * 1024
    }

    private val dao = AppDatabase.getInstance(context).chatDao()

    suspend fun startConversation(instruction: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.insertConversation(
            ConversationEntity(
                title = instruction,
                status = "running",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /** 接着一段历史对话做新指令：指令记进同一个会话，会话重新标成进行中（也就排到最前）。 */
    suspend fun continueConversation(conversationId: Long, instruction: String) =
        withContext(Dispatchers.IO) {
            dao.insertMessage(
                ChatMessageEntity(
                    conversationId = conversationId,
                    type = MessageType.USER_INPUT.name,
                    text = instruction,
                    timestamp = System.currentTimeMillis()
                )
            )
            dao.updateStatus(conversationId, "running", System.currentTimeMillis())
        }

    suspend fun getConversation(conversationId: Long): ConversationEntity? =
        withContext(Dispatchers.IO) { dao.getConversation(conversationId) }

    /** 保存这段会话发给模型的消息记录（JSON），覆盖旧的。 */
    suspend fun saveSessionHistory(conversationId: Long, json: String) =
        withContext(Dispatchers.IO) {
            dao.upsertSessionHistory(SessionHistoryEntity(conversationId, json, System.currentTimeMillis()))
        }

    /** 整段记录不设上限，可能超过单次读取的上限（CursorWindow 约 2MB），所以分段读。 */
    suspend fun getSessionHistory(conversationId: Long): String? = withContext(Dispatchers.IO) {
        val length = dao.getSessionHistoryLength(conversationId) ?: return@withContext null
        buildString {
            var start = 1
            while (start <= length) {
                append(dao.getSessionHistoryChunk(conversationId, start, HISTORY_CHUNK_CHARS).orEmpty())
                start += HISTORY_CHUNK_CHARS
            }
        }
    }

    /** 会话列表，按最近活动排序；有改动（新会话、状态变化、删除）就重新发一次。 */
    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    /** 一个会话的全部消息，写入或更新时重新发一次。 */
    fun observeMessages(conversationId: Long): Flow<List<ChatMessageEntity>> =
        dao.observeMessages(conversationId)

    suspend fun addMessage(conversationId: Long, type: MessageType, text: String) =
        withContext(Dispatchers.IO) {
            dao.insertMessage(
                ChatMessageEntity(
                    conversationId = conversationId,
                    type = type.name,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

    suspend fun updateStatus(conversationId: Long, status: String) =
        withContext(Dispatchers.IO) {
            dao.updateStatus(conversationId, status, System.currentTimeMillis())
        }

    suspend fun getAllConversations(): List<ConversationEntity> =
        withContext(Dispatchers.IO) { dao.getAllConversations() }

    suspend fun getRecentConversations(limit: Int = 3): List<ConversationEntity> =
        withContext(Dispatchers.IO) { dao.getRecentConversations(limit) }

    suspend fun getMessages(conversationId: Long): List<ChatMessageEntity> =
        withContext(Dispatchers.IO) { dao.getMessages(conversationId) }

    suspend fun getStepCount(conversationId: Long): Int =
        withContext(Dispatchers.IO) { dao.getStepCount(conversationId) }

    suspend fun deleteConversation(conversationId: Long) =
        withContext(Dispatchers.IO) { dao.deleteConversation(conversationId) }

    suspend fun updateLastAgentStatus(conversationId: Long, text: String) =
        withContext(Dispatchers.IO) {
            dao.updateLastAgentStatus(conversationId, text, System.currentTimeMillis())
        }

    suspend fun appendInbound(
        conversationId: Long,
        text: String,
        source: String,
        remoteChatId: String
    ) = withContext(Dispatchers.IO) {
        dao.insertMessage(
            ChatMessageEntity(
                conversationId = conversationId,
                type = MessageType.USER_INPUT.name,
                text = text,
                timestamp = System.currentTimeMillis(),
                source = source,
                remoteChatId = remoteChatId
            )
        )
    }

    suspend fun updateLastAgentStatusToResult(conversationId: Long) =
        withContext(Dispatchers.IO) {
            dao.updateLastMessageType(
                conversationId,
                MessageType.AGENT_STATUS.name,
                MessageType.AGENT_RESULT.name,
                System.currentTimeMillis()
            )
        }

    suspend fun markStaleRunningAsStopped() = withContext(Dispatchers.IO) {
        dao.markStaleRunningAsStopped(System.currentTimeMillis())
    }
}
