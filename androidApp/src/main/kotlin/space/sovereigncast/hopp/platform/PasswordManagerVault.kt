package space.sovereigncast.hopp.platform

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import space.sovereigncast.hopp.BuildConfig

/**
 * The Nostr key in the listener's password manager, through Android's
 * Credential Manager: saved as a password under the npub, offered back on a
 * new phone. The provider is the listener's — Google Password Manager,
 * Bitwarden, KeePassDX …; Hopp only hands it over.
 */
class PasswordManagerVault(private val activity: Activity) : KeyVault {
    override val kind = KeyVault.Kind.PasswordManager
    private val manager = CredentialManager.create(activity)

    override suspend fun save(account: String, secret: String): Boolean = try {
        manager.createCredential(activity, CreatePasswordRequest(account, secret))
        true
    } catch (e: CreateCredentialException) {
        false
    }

    override suspend fun load(): String? = try {
        val result = manager.getCredential(activity, GetCredentialRequest(listOf(GetPasswordOption())))
        (result.credential as? PasswordCredential)?.password?.takeIf { it.startsWith("nsec1") }
    } catch (e: GetCredentialException) {
        null
    }

    companion object {
        /** FOSS has no Play services: the system's Credential Manager arrived with Android 14. */
        val available: Boolean get() = BuildConfig.FLAVOR == "play" || Build.VERSION.SDK_INT >= 34
    }
}
