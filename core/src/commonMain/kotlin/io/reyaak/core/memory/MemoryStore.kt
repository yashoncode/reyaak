package io.reyaak.core.memory

import io.reyaak.core.data.MemoryDao
import io.reyaak.core.data.MemoryEntity
import io.reyaak.core.data.MemoryKind
import io.reyaak.router.time.epochMillis
import kotlinx.coroutines.flow.Flow

/**
 * What the agent knows, kept between conversations.
 *
 * Memory is facts; skills are procedures. The distinction is load-bearing rather
 * than tidy: a skill is text the user switches on and which then applies to
 * every turn, while a memory is retrieved only when it matches what is being
 * discussed. Merging them would mean either loading every fact into every prompt
 * or letting a procedure be forgotten for being unused.
 *
 * Four invariants hold everywhere in this class, because an agent that curates
 * its own memory is an agent that can lose your data:
 *
 *  1. Nothing the user wrote is ever touched automatically. [remember] marks
 *     agent-written entries and the curator only ever sees those.
 *  2. Nothing is auto-deleted. Curation archives, and archiving is reversible.
 *  3. A pinned memory bypasses every automatic transition.
 *  4. The expensive pass is opt-in behind a cheap one. [staleCandidates] is
 *     deterministic and free; asking a model which memories are worth keeping
 *     is a separate, explicit step.
 */
class MemoryStore(
    private val dao: MemoryDao,
    private val now: () -> Long = ::epochMillis,
) {

    fun observeActive(): Flow<List<MemoryEntity>> = dao.observeActive()

    fun observeAll(): Flow<List<MemoryEntity>> = dao.observeAll()

    suspend fun activeCount(): Int = dao.activeCount()

    /**
     * Write a fact down.
     *
     * Near-duplicates are updated rather than appended: an agent told the same
     * thing twice in two conversations would otherwise accumulate a memory per
     * mention, and the prompt section would fill with restatements.
     */
    suspend fun remember(
        content: String,
        kind: MemoryKind = MemoryKind.FACT,
        conversationId: Long? = null,
        agentCreated: Boolean = true,
    ): Long? {
        val text = content.trim()
        if (text.isBlank()) return null
        val at = now()

        existingMatch(text, kind)?.let { existing ->
            // Longer wins: the later statement is usually the more specific one,
            // and replacing a specific memory with a vaguer restatement loses
            // information the agent already had.
            if (text.length > existing.content.length) {
                dao.updateContent(existing.id, text, at)
            }
            return existing.id
        }

        return dao.insert(
            MemoryEntity(
                kind = kind.wireName,
                content = text,
                sourceConversationId = conversationId,
                createdAt = at,
                updatedAt = at,
                agentCreated = agentCreated,
            )
        )
    }

    /**
     * An active memory of the same kind that already says this.
     *
     * Containment either way rather than equality, so "prefers Kotlin" and
     * "prefers Kotlin and KMP" collapse into the longer one instead of both
     * being kept.
     */
    private suspend fun existingMatch(text: String, kind: MemoryKind): MemoryEntity? {
        val normalized = text.normalizeForMatch()
        return dao.ofKind(kind.wireName, MATCH_SCAN_LIMIT).firstOrNull { candidate ->
            val other = candidate.content.normalizeForMatch()
            other == normalized || other.contains(normalized) || normalized.contains(other)
        }
    }

    /**
     * Recall by substring, and count the recall.
     *
     * The use count is what later tells the curator which memories earn their
     * tokens, so reading has to record that it happened. A search that did not
     * would leave every memory looking equally unused.
     */
    suspend fun recall(term: String, limit: Int = RECALL_LIMIT): List<MemoryEntity> {
        val query = term.trim()
        if (query.isBlank()) return emptyList()
        val hits = dao.search(query, limit)
        if (hits.isNotEmpty()) dao.markUsed(hits.map { it.id }, now())
        return hits
    }

    /** The user model, which is small enough to belong in every prompt. */
    suspend fun profile(limit: Int = PROFILE_LIMIT): List<MemoryEntity> =
        dao.ofKind(MemoryKind.PROFILE.wireName, limit)

    /**
     * The memory section for a prompt, or null when there is nothing to say.
     *
     * Only the user model goes in unconditionally. Facts are left to the recall
     * tool, because a store of two hundred facts would otherwise cost more
     * tokens than the question.
     */
    suspend fun promptSection(): String? {
        val entries = profile()
        if (entries.isEmpty()) return null
        dao.markUsed(entries.map { it.id }, now())
        return buildString {
            append(HEADER)
            entries.forEach { append("\n- ").append(it.content) }
        }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned, now())

    suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived, now())

    /**
     * Correct a memory, which also transfers it to the user.
     *
     * Rewriting a fact is a stronger signal of ownership than pinning it, so the
     * entry stops being agent-created and the curator can no longer reach it.
     */
    suspend fun edit(id: Long, content: String) {
        val text = content.trim()
        if (text.isNotBlank()) dao.updateContentAsUser(id, text, now())
    }

    /** The only hard delete. Reachable from the user, never from the curator. */
    suspend fun forget(id: Long) = dao.delete(id)

    /**
     * The cheap deterministic curation pass.
     *
     * Returns what it archived rather than a count, so the caller can say which
     * memories went away. Bounded per run: a pass that archived four hundred
     * entries at once would be indistinguishable from a bug.
     */
    suspend fun archiveStale(
        olderThanMs: Long = STALE_AFTER_MS,
        maxUses: Int = STALE_MAX_USES,
        limit: Int = ARCHIVE_PER_RUN,
    ): List<MemoryEntity> {
        val at = now()
        // Keeping a floor of memories means a quiet month cannot empty the store.
        if (dao.activeCount() <= KEEP_AT_LEAST) return emptyList()
        val candidates = dao.staleCandidates(
            before = at - olderThanMs,
            maxUses = maxUses,
            limit = limit,
        )
        candidates.forEach { dao.setArchived(it.id, true, at) }
        return candidates
    }

    private companion object {
        const val HEADER = "What you know about the person you are talking to:"

        /** How many rows the duplicate check looks at. Enough for a phone. */
        const val MATCH_SCAN_LIMIT = 200
        const val RECALL_LIMIT = 8
        const val PROFILE_LIMIT = 24

        /** Thirty days unused is the point where a fact is probably noise. */
        const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000
        const val STALE_MAX_USES = 0
        const val ARCHIVE_PER_RUN = 20
        const val KEEP_AT_LEAST = 10
    }
}

/** Lowercase, collapse whitespace, drop trailing punctuation. */
private fun String.normalizeForMatch(): String =
    trim().lowercase().replace(WHITESPACE, " ").trimEnd('.', '!', ',', ';', ':')

private val WHITESPACE = Regex("\\s+")
