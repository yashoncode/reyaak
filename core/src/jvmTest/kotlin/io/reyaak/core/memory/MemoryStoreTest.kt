package io.reyaak.core.memory

import io.reyaak.core.data.MemoryDao
import io.reyaak.core.data.MemoryEntity
import io.reyaak.core.data.MemoryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four curation invariants, as tests.
 *
 * An agent that edits its own memory is an agent that can lose your data, so
 * these are requirements rather than behaviours: never touch what the user
 * wrote, never delete, let pinned bypass everything, and keep the cheap pass
 * deterministic.
 */
class MemoryStoreTest {

    private var clock = 1_000_000L
    private val dao = FakeMemoryDao()
    private val store = MemoryStore(dao) { clock }

    @Test
    fun `remembering the same fact twice keeps one entry`() = runTest {
        store.remember("Prefers Kotlin")
        store.remember("prefers kotlin.")
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `the more specific statement replaces the vaguer one`() = runTest {
        store.remember("Prefers Kotlin")
        store.remember("Prefers Kotlin and KMP over Java")
        assertEquals(1, dao.rows.size)
        assertEquals("Prefers Kotlin and KMP over Java", dao.rows.single().content)
    }

    @Test
    fun `a vaguer restatement does not overwrite the specific one`() = runTest {
        store.remember("Prefers Kotlin and KMP over Java")
        store.remember("Prefers Kotlin")
        assertEquals("Prefers Kotlin and KMP over Java", dao.rows.single().content)
    }

    @Test
    fun `blank content is not a memory`() = runTest {
        assertNull(store.remember("   "))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `facts and profile entries do not collide`() = runTest {
        store.remember("Ships on Fridays", MemoryKind.FACT)
        store.remember("Ships on Fridays", MemoryKind.PROFILE)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `recall records that the memory was used`() = runTest {
        store.remember("The staging cluster is in Frankfurt")
        clock += 500
        val hits = store.recall("Frankfurt")
        assertEquals(1, hits.size)
        assertEquals(1, dao.rows.single().useCount)
        assertEquals(1_000_500L, dao.rows.single().lastUsedAt)
    }

    @Test
    fun `recall on a blank term touches nothing`() = runTest {
        store.remember("anything")
        assertTrue(store.recall("  ").isEmpty())
        assertEquals(0, dao.rows.single().useCount)
    }

    @Test
    fun `the prompt section is absent when there is no user model`() = runTest {
        store.remember("A fact, not a profile entry", MemoryKind.FACT)
        assertNull(store.promptSection())
    }

    @Test
    fun `the prompt section lists the user model`() = runTest {
        store.remember("Android dev", MemoryKind.PROFILE)
        store.remember("Prefers short answers", MemoryKind.PROFILE)
        val section = store.promptSection().orEmpty()
        assertTrue(section.contains("Android dev"))
        assertTrue(section.contains("Prefers short answers"))
    }

    // ── Curation invariants ─────────────────────────────────────────────────

    @Test
    fun `curation never touches what the user wrote`() = runTest {
        repeat(12) { store.remember("filler $it") }
        store.remember("The user typed this", agentCreated = false)
        clock += FORTY_DAYS

        store.archiveStale()

        val mine = dao.rows.single { it.content == "The user typed this" }
        assertFalse(mine.archived)
    }

    @Test
    fun `curation never touches a pinned memory`() = runTest {
        repeat(12) { store.remember("filler $it") }
        val id = store.remember("Pinned and ancient")!!
        store.setPinned(id, true)
        clock += FORTY_DAYS

        store.archiveStale()

        assertFalse(dao.rows.single { it.id == id }.archived)
    }

    @Test
    fun `curation archives rather than deletes`() = runTest {
        repeat(12) { store.remember("filler $it") }
        val before = dao.rows.size
        clock += FORTY_DAYS

        val archived = store.archiveStale()

        assertTrue(archived.isNotEmpty())
        // Every row is still there: archiving is a flag, not a delete.
        assertEquals(before, dao.rows.size)
        assertTrue(archived.all { row -> dao.rows.single { it.id == row.id }.archived })
    }

    @Test
    fun `curation leaves a floor of memories`() = runTest {
        repeat(4) { store.remember("only a few $it") }
        clock += FORTY_DAYS
        assertTrue(store.archiveStale().isEmpty())
        assertTrue(dao.rows.none { it.archived })
    }

    @Test
    fun `curation spares a memory that has been used`() = runTest {
        repeat(12) { store.remember("filler $it") }
        store.remember("The staging cluster is in Frankfurt")
        store.recall("Frankfurt")
        clock += FORTY_DAYS

        store.archiveStale()

        val used = dao.rows.single { it.content.contains("Frankfurt") }
        assertFalse(used.archived)
    }

    @Test
    fun `curation spares a recent memory`() = runTest {
        repeat(12) { store.remember("filler $it") }
        clock += 60_000
        assertTrue(store.archiveStale().isEmpty())
    }

    @Test
    fun `correcting a memory takes it out of the curator's reach`() = runTest {
        repeat(12) { store.remember("filler $it") }
        val id = store.remember("The cluster is in Dublin")!!
        store.edit(id, "The cluster is in Frankfurt")
        clock += FORTY_DAYS

        store.archiveStale()

        val corrected = dao.rows.single { it.id == id }
        assertEquals("The cluster is in Frankfurt", corrected.content)
        assertFalse(corrected.agentCreated)
        assertFalse(corrected.archived)
    }

    @Test
    fun `the agent's own deduplicating update does not claim ownership`() = runTest {
        val id = store.remember("Prefers Kotlin")!!
        store.remember("Prefers Kotlin and KMP")
        assertTrue(dao.rows.single { it.id == id }.agentCreated)
    }

    @Test
    fun `archiving is reversible`() = runTest {
        val id = store.remember("Wrongly curated away")!!
        store.setArchived(id, true)
        assertTrue(dao.rows.single().archived)
        store.setArchived(id, false)
        assertFalse(dao.rows.single().archived)
    }

    private companion object {
        const val FORTY_DAYS = 40L * 24 * 60 * 60 * 1000
    }
}

/** An in-memory MemoryDao. Mirrors the SQL semantics the queries rely on. */
private class FakeMemoryDao : MemoryDao {

    val rows = mutableListOf<MemoryEntity>()
    private var nextId = 1L

    override suspend fun insert(memory: MemoryEntity): Long {
        val id = nextId++
        rows += memory.copy(id = id)
        return id
    }

    override fun observeActive(): Flow<List<MemoryEntity>> =
        flowOf(rows.filterNot { it.archived })

    override fun observeAll(): Flow<List<MemoryEntity>> = flowOf(rows.toList())

    override suspend fun byId(id: Long): MemoryEntity? = rows.firstOrNull { it.id == id }

    override suspend fun ofKind(kind: String, limit: Int): List<MemoryEntity> =
        rows.filter { !it.archived && it.kind == kind }
            .sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.updatedAt })
            .take(limit)

    override suspend fun search(term: String, limit: Int): List<MemoryEntity> =
        rows.filter { !it.archived && it.content.contains(term, ignoreCase = true) }
            .sortedWith(
                compareByDescending<MemoryEntity> { it.pinned }
                    .thenByDescending { it.useCount }
                    .thenByDescending { it.updatedAt }
            )
            .take(limit)

    override suspend fun updateContent(id: Long, content: String, at: Long) =
        mutate(id) { it.copy(content = content, updatedAt = at) }

    override suspend fun updateContentAsUser(id: Long, content: String, at: Long) =
        mutate(id) { it.copy(content = content, agentCreated = false, updatedAt = at) }

    override suspend fun setPinned(id: Long, pinned: Boolean, at: Long) =
        mutate(id) { it.copy(pinned = pinned, updatedAt = at) }

    override suspend fun setArchived(id: Long, archived: Boolean, at: Long) =
        mutate(id) { it.copy(archived = archived, updatedAt = at) }

    override suspend fun markUsed(ids: List<Long>, at: Long) {
        ids.forEach { id -> mutate(id) { it.copy(useCount = it.useCount + 1, lastUsedAt = at) } }
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun activeCount(): Int = rows.count { !it.archived }

    override suspend fun staleCandidates(
        before: Long,
        maxUses: Int,
        limit: Int,
    ): List<MemoryEntity> = rows
        .filter {
            !it.archived && !it.pinned && it.agentCreated &&
                it.useCount <= maxUses && it.updatedAt < before
        }
        .sortedBy { it.updatedAt }
        .take(limit)

    private fun mutate(id: Long, transform: (MemoryEntity) -> MemoryEntity) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = transform(rows[index])
    }
}
