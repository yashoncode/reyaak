package io.reyaak.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One durable fact.
 *
 * Facts, not procedures. A procedure is a skill: it says how to do something and
 * is loaded when the user switches it on. A fact says what is true and is
 * retrieved when it is relevant. Keeping them in separate stores is what stops
 * the prompt from filling up with instructions nobody asked for.
 *
 * Nothing here is deleted automatically. [archived] is how a memory stops being
 * used, so a wrong curation decision is recoverable and the user's own notes can
 * never be silently thrown away.
 */
@Entity(
    tableName = "memories",
    indices = [Index("kind"), Index("archived")],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [MemoryKind] as its lowercase name, so the table stays readable. */
    val kind: String,
    val content: String,
    /**
     * The conversation this was learned in, when it was learned rather than
     * typed. A memory that turns out to be wrong can then be traced back to
     * what was being discussed when the agent believed it.
     */
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Bumped every time this is retrieved into a prompt. Drives curation. */
    @ColumnInfo(name = "use_count") val useCount: Int = 0,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    /** A pinned memory bypasses every automatic transition, including archiving. */
    val pinned: Boolean = false,
    /** True once curated out. Recoverable, and never a delete. */
    val archived: Boolean = false,
    /** False for anything the user wrote, which the curator must never touch. */
    @ColumnInfo(name = "agent_created") val agentCreated: Boolean = true,
)

/**
 * What a memory is about.
 *
 * The split exists because the two are retrieved differently: the user model is
 * small, always relevant, and goes into every prompt, while facts are many and
 * only the matching ones are worth the tokens.
 */
enum class MemoryKind {
    /** Something about the user: how they work, what they prefer, who they are. */
    PROFILE,

    /** Something about the world, a project, or a decision that was made. */
    FACT;

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): MemoryKind =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: FACT
    }
}

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("SELECT * FROM memories WHERE archived = 0 ORDER BY updated_at DESC")
    fun observeActive(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY archived ASC, updated_at DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE archived = 0 AND kind = :kind ORDER BY pinned DESC, updated_at DESC LIMIT :limit")
    suspend fun ofKind(kind: String, limit: Int): List<MemoryEntity>

    /**
     * Substring search, case-insensitive.
     *
     * ponytail: LIKE scan rather than an FTS virtual table. A phone's memory
     * store is tens to hundreds of rows, where a full scan is microseconds and
     * FTS would cost a second table, a migration, and a portability question
     * across the KMP targets. Move to FTS5 if this ever holds thousands.
     */
    @Query(
        """
        SELECT * FROM memories
        WHERE archived = 0 AND content LIKE '%' || :term || '%'
        ORDER BY pinned DESC, use_count DESC, updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun search(term: String, limit: Int): List<MemoryEntity>

    @Query("UPDATE memories SET content = :content, updated_at = :at WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, at: Long)

    /**
     * The same, but transferring ownership to the user.
     *
     * Correcting a memory is a claim on it. Once the text is the user's, the
     * curator must never touch it again, which is exactly what clearing
     * `agent_created` means everywhere else in this file.
     */
    @Query(
        "UPDATE memories SET content = :content, agent_created = 0, updated_at = :at WHERE id = :id"
    )
    suspend fun updateContentAsUser(id: Long, content: String, at: Long)

    @Query("UPDATE memories SET pinned = :pinned, updated_at = :at WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, at: Long)

    @Query("UPDATE memories SET archived = :archived, updated_at = :at WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, at: Long)

    @Query("UPDATE memories SET use_count = use_count + 1, last_used_at = :at WHERE id IN (:ids)")
    suspend fun markUsed(ids: List<Long>, at: Long)

    /** The only hard delete, and only ever from an explicit user action. */
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM memories WHERE archived = 0")
    suspend fun activeCount(): Int

    /**
     * Archiving candidates: agent-written, unpinned, unused, and old.
     *
     * The cheap deterministic pass. Anything the user wrote is excluded here
     * rather than filtered later, so a bug in the caller cannot reach it.
     */
    @Query(
        """
        SELECT * FROM memories
        WHERE archived = 0
          AND pinned = 0
          AND agent_created = 1
          AND use_count <= :maxUses
          AND updated_at < :before
        ORDER BY updated_at ASC
        LIMIT :limit
        """
    )
    suspend fun staleCandidates(before: Long, maxUses: Int, limit: Int): List<MemoryEntity>
}
