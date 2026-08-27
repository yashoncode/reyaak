package io.reyaak.data

import android.content.Context
import io.reyaak.router.config.ConfigPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One file in app-private storage, in the clear.
 *
 * For settings that are preferences rather than credentials: the persona. Not
 * encrypted on purpose, since Keystore only buys something for a secret, and
 * app-private storage is already unreadable by other apps.
 */
class FilePersistence(context: Context, name: String) : ConfigPersistence {

    private val file = File(context.filesDir, name)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    override suspend fun write(json: String) = withContext(Dispatchers.IO) {
        // Temp-and-rename, so an interrupted write cannot leave half a file that
        // reads as corrupt on next launch.
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        Unit
    }
}
