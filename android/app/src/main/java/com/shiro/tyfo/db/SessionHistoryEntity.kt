package com.shiro.tyfo.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 一段会话发给模型的完整消息记录（JSON，见 engine.SessionHistory），继续这段会话时恢复。
 * 和 conversations 分表存：侧边栏列会话时不用把这一大段读出来。
 */
@Entity(
    tableName = "session_history",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SessionHistoryEntity(
    @PrimaryKey val conversationId: Long,
    val messages: String,
    val updatedAt: Long
)
