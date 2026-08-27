package io.reyaak.router.config

/**
 * Translates provider ids between Reyaak's catalog and the interchange format.
 *
 * These are not cosmetic. freellmapi calls Google's platform `google`, while this
 * catalog calls it `gemini` after the model family. Without the mapping, their
 * documented example config imports with "unknown provider google; entry
 * skipped" and the user silently loses the key they were trying to bring over.
 *
 * The translation runs in both directions, so an export is a file freellmapi
 * accepts rather than one that merely looks similar.
 */
internal object PlatformAlias {

    /** Interchange name -> local catalog id. */
    private val toLocalMap = mapOf(
        "google" to "gemini",
        "googleai" to "gemini",
        "google-ai-studio" to "gemini",
        "gemini" to "gemini",
    )

    /** Local catalog id -> the name the interchange format expects. */
    private val toWireMap = mapOf(
        "gemini" to "google",
    )

    fun toLocal(platform: String): String {
        val trimmed = platform.trim()
        return toLocalMap[trimmed.lowercase()] ?: trimmed
    }

    fun toWire(platform: String): String {
        val trimmed = platform.trim()
        return toWireMap[trimmed.lowercase()] ?: trimmed
    }
}
