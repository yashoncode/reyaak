package io.reyaak.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversation_id")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    /** Stored as the lowercase wire name, so the DB stays readable. */
    val role: String,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,

    // Provenance, so the UI can show which provider actually answered and the
    // usage screen can attribute cost without a second table.
    val platform: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int = 0,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int = 0,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long = 0,
    val attempts: Int = 0,
    /** Set when the turn failed, so a failure is part of the transcript. */
    val error: String? = null,
)

/** A conversation plus what the list screen needs, without loading its messages. */
data class ConversationSummary(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "last_message") val lastMessage: String?,
)

@Dao
interface ChatDao {

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE conversations SET updated_at = :at WHERE id = :id")
    suspend fun touchConversation(id: Long, at: Long)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun renameConversation(id: Long, title: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("SELECT * FROM messages WHERE conversation_id = :id ORDER BY id ASC")
    fun observeMessages(id: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :id ORDER BY id ASC")
    suspend fun messagesOf(id: Long): List<MessageEntity>

    @Query(
        """
        SELECT c.id, c.title, c.updated_at,
               (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS message_count,
               (SELECT m.content FROM messages m WHERE m.conversation_id = c.id
                 ORDER BY m.id DESC LIMIT 1) AS last_message
          FROM conversations c
         ORDER BY c.updated_at DESC
        """
    )
    fun observeConversations(): Flow<List<ConversationSummary>>

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC LIMIT 1")
    suspend fun mostRecentConversation(): ConversationEntity?

    @Transaction
    suspend fun startConversation(title: String, at: Long): Long =
        insertConversation(ConversationEntity(title = title, createdAt = at, updatedAt = at))

    // ── Usage, aggregated for the router screen ─────────────────────────────

    @Query(
        """
        SELECT platform AS platform, model_id AS modelId,
               COUNT(*) AS turns,
               SUM(prompt_tokens) AS promptTokens,
               SUM(completion_tokens) AS completionTokens,
               AVG(latency_ms) AS avgLatencyMs
          FROM messages
         WHERE platform IS NOT NULL AND error IS NULL
         GROUP BY platform, model_id
         ORDER BY turns DESC
        """
    )
    fun observeUsage(): Flow<List<UsageRow>>
}

data class UsageRow(
    val platform: String,
    val modelId: String?,
    val turns: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val avgLatencyMs: Double,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}
