package space.sovereigncast.hopp.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SecretStore] on Android: AES-GCM with a key that never leaves the Android
 * Keystore; the ciphertext sits in its own preferences file, which the backup
 * rules leave out — restored on another device it could not be decrypted
 * anyway, the key stays behind.
 *
 * An entry that is there and does not decrypt — the Keystore not answering
 * for the moment, or its key gone — is [SecretUnreadable], not "nothing
 * stored" (security review 2026-10-20): read as nothing, the app offered to
 * make a new Nostr key, and that write replaced the old one for good. Only
 * removing (`put(name, null)`) gets past such an entry.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    override fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecretUnreadable(name, e)
        }
    }

    override fun put(name: String, value: String?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        get(name) // there and unreadable: refused, not replaced
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    companion object {
        const val FILE = "hopp_secrets"
        private const val ALIAS = "hopp-secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
