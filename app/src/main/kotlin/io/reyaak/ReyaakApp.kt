package io.reyaak

import android.app.Application
import androidx.room.Room
import io.reyaak.core.data.ReyaakDatabase
import io.reyaak.core.ReyaakCore
import io.reyaak.data.FilePersistence
import io.reyaak.core.tools.GmailReadTool
import io.reyaak.data.GoogleAuth
import io.reyaak.data.ImapReadTool
import io.reyaak.data.KeystoreConfigPersistence
import io.reyaak.runtime.AgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReyaakApp : Application() {

    lateinit var core: ReyaakCore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Locating the database file is the host's job; :core takes the
        // builder and attaches the driver itself.
        core = ReyaakCore.get(
            databaseBuilder = Room.databaseBuilder<ReyaakDatabase>(
                context = this,
                name = getDatabasePath("reyaak.db").absolutePath,
            ),
            persistence = KeystoreConfigPersistence(this),
            // The persona holds no secrets, so it is a plain file.
            personaPersistence = FilePersistence(this, "persona.json"),
            // The tool config holds a fastCRW key, so it gets the same
            // Keystore treatment as the router config, in its own file.
            toolPersistence = KeystoreConfigPersistence(
                context = this,
                fileName = KeystoreConfigPersistence.TOOLS_FILE,
            ),
            // Skills are instructions, not credentials, so a plain file.
            skillPersistence = FilePersistence(this, "skills.json"),
            // The two mail tools are built here because both need something
            // Android-only: Play Services for the Google sign-in, JavaMail for
            // IMAP. Two paths to the same mailbox on purpose. IMAP is the one
            // that needs nothing registered anywhere, so it reaches every
            // provider; the Google one is there for people who would rather tap
            // Sign in than find an app password.
            extraTools = listOf(
                GmailReadTool(GoogleAuth(this)),
                ImapReadTool(),
            ),
        )
        AgentService.ensureChannel(this)

        // Decrypting the config touches the Keystore and reads a file, so it
        // happens off the main thread. The UI renders with defaults until it
        // lands, which is correct: an empty router is a valid state.
        // Then reconcile the model catalog with what the providers actually
        // serve. Provider ids rot while the app sits idle, and a stored id that
        // was retired upstream burns the first turn's attempts on 404s.
        scope.launch {
            core.start()
            core.syncModels()
        }
    }
}
