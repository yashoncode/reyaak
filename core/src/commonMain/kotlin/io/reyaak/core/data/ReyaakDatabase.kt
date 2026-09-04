package io.reyaak.core.data

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * The agent's durable state.
 *
 * Version 1 covered conversations. Version 2 adds memory, which is the half of
 * the agent that survives a conversation ending. Skill versions, runs, and tool
 * errors follow the same way: additively, which is the reason Room is here
 * rather than a hand-rolled helper.
 *
 * The migration is declared rather than written because it only adds a table.
 * Room generates it from the exported schemas, so the one thing that could go
 * wrong, a hand-written ALTER that disagrees with the entity, cannot.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, MemoryEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(ReyaakDatabaseConstructor::class)
abstract class ReyaakDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun memoryDao(): MemoryDao
}

/**
 * Room generates the `actual` for each target, which is what lets the @Database
 * class itself stay in commonMain. No `fallbackToDestructiveMigration` anywhere:
 * this database holds the agent's memory and the user's conversations, so a
 * missing migration must fail loudly in development rather than quietly wipe a
 * user's phone.
 */
@Suppress("KotlinNoActualForExpect")
expect object ReyaakDatabaseConstructor : RoomDatabaseConstructor<ReyaakDatabase> {
    override fun initialize(): ReyaakDatabase
}
