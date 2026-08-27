package io.reyaak.router.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the config bytes live.
 *
 * Abstract because the bytes contain API keys: on Android the implementation is
 * Keystore-backed, and keeping that behind a port lets all the config logic be
 * tested on the JVM with a plain in-memory implementation.
 */
interface ConfigPersistence {
    suspend fun read(): String?
    suspend fun write(json: String)

    /** For tests and previews. */
    class InMemory(private var value: String? = null) : ConfigPersistence {
        override suspend fun read(): String? = value
        override suspend fun write(json: String) { value = json }
    }
}

/**
 * Holds the live router configuration and persists it.
 *
 * The persisted form IS the interchange format. That is deliberate: it means
 * export is a read of what is already stored rather than a separate
 * serialization path that can drift, and import is the same code that runs at
 * startup. There is no second representation to keep in sync.
 */
class RouterConfigStore(private val persistence: ConfigPersistence) {

    private val _settings = MutableStateFlow(RouterSettings())
    val settings: StateFlow<RouterSettings> = _settings.asStateFlow()

    private val writeLock = Mutex()

    /** Read persisted config at startup. A corrupt file leaves defaults in place. */
    suspend fun load(): List<String> {
        val stored = persistence.read()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            val result = RouterConfigCodec.decode(stored)
            _settings.value = result.settings
            result.warnings
        } catch (e: ConfigParseException) {
            // Do not clobber the file: keeping it lets the user export and repair
            // it rather than silently losing every key they had added.
            listOf("stored configuration could not be read (${e.message}); using defaults")
        }
    }

    suspend fun update(transform: (RouterSettings) -> RouterSettings) {
        writeLock.withLock {
            val next = transform(_settings.value)
            _settings.value = next
            persistence.write(RouterConfigCodec.encode(next, includeSecrets = true))
        }
    }

    /**
     * Apply an imported config.
     *
     * [merge] true keeps existing keys and models and layers the file on top;
     * false replaces the configuration outright. Merge is the safer default for
     * a file someone was handed, since it cannot silently delete the key they
     * are currently using.
     */
    suspend fun import(json: String, merge: Boolean = true): ImportResult {
        val base = if (merge) _settings.value else RouterSettings()
        val result = RouterConfigCodec.decode(json, base)
        update { result.settings }
        return result
    }

    /**
     * The file to hand to the user.
     *
     * Secrets are excluded unless explicitly requested, because an export gets
     * emailed, synced, and pasted into bug reports.
     */
    fun export(includeSecrets: Boolean = false): String =
        RouterConfigCodec.encode(_settings.value, includeSecrets)
}
