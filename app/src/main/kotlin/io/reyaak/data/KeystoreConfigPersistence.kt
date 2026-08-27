package io.reyaak.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.reyaak.router.config.ConfigPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Router config, encrypted at rest with a hardware-backed key.
 *
 * The file holds provider API keys, so it is encrypted with AES-GCM under a key
 * generated in the Android Keystore. The key material never enters the app
 * process: only handles to it do, so a filesystem read cannot recover the
 * secrets, and on devices with a secure element the key cannot leave it at all.
 *
 * Written against the Keystore directly rather than via `androidx.security:
 * security-crypto`. That library would be a handful of lines here, but it pulls
 * Tink transitively for several megabytes, and AES-GCM over a Keystore key is a
 * platform primitive: there is nothing to abstract.
 *
 * Layout on disk: `[1-byte IV length][IV][ciphertext]`. The IV is generated per
 * write and stored in the clear, which is correct: GCM requires a unique IV per
 * encryption, not a secret one, and reusing one under the same key would be the
 * actual vulnerability.
 */
class KeystoreConfigPersistence(
    context: Context,
    /**
     * Which file to encrypt. Router config and tool credentials are separate
     * files under the same Keystore key: they are written by different screens
     * at different times, and one blob would mean a tool edit rewrites the file
     * holding every provider key.
     */
    private val fileName: String = ROUTER_FILE,
) : ConfigPersistence {

    private val file = File(context.filesDir, fileName)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            val blob = file.readBytes()
            if (blob.size < 2) return@withContext null
            val ivLength = blob[0].toInt() and 0xFF
            if (blob.size < 1 + ivLength) return@withContext null
            val iv = blob.copyOfRange(1, 1 + ivLength)
            val payload = blob.copyOfRange(1 + ivLength, blob.size)

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (e: Exception) {
            // A failure here means the key is gone (app data cleared, device
            // restored to new hardware) or the file is truncated. Report nothing
            // rather than throwing: the caller falls back to defaults, and the
            // user re-enters keys. Deleting the file would remove the only
            // evidence of what happened.
            null
        }
    }

    override suspend fun write(json: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val iv = cipher.iv
        val payload = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        // Write to a temp file and rename, so an interrupted write cannot leave a
        // half-encrypted config that reads as corrupt on next launch.
        val temp = File(file.parentFile, "$fileName.tmp")
        temp.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(payload)
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        Unit
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Deliberately NOT requiring user authentication: the agent
                    // runs in the background on a schedule, and a key that needs
                    // an unlocked screen would make it fail whenever the phone is
                    // idle, which is exactly when it is meant to work.
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        const val ROUTER_FILE = "router-config.enc"
        const val TOOLS_FILE = "tools-config.enc"

        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "reyaak.router.config"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
