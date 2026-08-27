package io.reyaak.core.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

/**
 * The agent's durable state.
 *
 * Version 1 covers conversations only. Memory, skills, skill versions, runs, and
 * tool errors arrive in later phases as additive migrations, which is the
 * reason Room is here rather than a hand-rolled helper: the self-learning engine
 * versions skills, so the schema is expected to churn.
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(ReyaakDatabaseConstructor::class)
abstract class ReyaakDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
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
