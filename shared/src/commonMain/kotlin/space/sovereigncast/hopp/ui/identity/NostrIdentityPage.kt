package space.sovereigncast.hopp.ui.identity

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.Box

import androidx.compose.material3.HorizontalDivider

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.identity.NostrIdentity
import space.sovereigncast.hopp.nostr.SignerRejected
import space.sovereigncast.hopp.resources.nostr_bunker_qr
import space.sovereigncast.hopp.resources.nostr_extension
import space.sovereigncast.hopp.resources.nostr_section_advanced
import space.sovereigncast.hopp.resources.nostr_section_public
import space.sovereigncast.hopp.resources.nostr_section_sync
import space.sovereigncast.hopp.resources.nostr_extension_failed
import space.sovereigncast.hopp.resources.nostr_extension_rejected
import space.sovereigncast.hopp.resources.nostr_hero_text_web
import space.sovereigncast.hopp.resources.nostr_sign_out_text_web
import space.sovereigncast.hopp.resources.nostr_tab_only
import space.sovereigncast.hopp.resources.nostr_tab_only_note
import space.sovereigncast.hopp.resources.nostr_tab_only_on
import space.sovereigncast.hopp.resources.nostr_via_extension
import space.sovereigncast.hopp.resources.nostr_web_note
import space.sovereigncast.hopp.resources.stats_nostr_link
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.nostr_restore_hint
import space.sovereigncast.hopp.resources.sync_failed
import space.sovereigncast.hopp.resources.sync_last_hours
import space.sovereigncast.hopp.resources.sync_last_minutes
import space.sovereigncast.hopp.resources.sync_last_now
import space.sovereigncast.hopp.resources.sync_newer
import space.sovereigncast.hopp.resources.sync_note
import space.sovereigncast.hopp.resources.sync_now
import space.sovereigncast.hopp.resources.sync_renew
import space.sovereigncast.hopp.resources.sync_renew_confirm
import space.sovereigncast.hopp.resources.sync_renew_failed
import space.sovereigncast.hopp.resources.sync_renew_text
import space.sovereigncast.hopp.resources.sync_renewed
import space.sovereigncast.hopp.resources.sync_no_relay
import space.sovereigncast.hopp.resources.sync_too_few
import space.sovereigncast.hopp.resources.sync_not_taken
import space.sovereigncast.hopp.resources.sync_renew_unfinished
import space.sovereigncast.hopp.resources.sync_running
import space.sovereigncast.hopp.resources.sync_title
import space.sovereigncast.hopp.resources.sync_waits
import space.sovereigncast.hopp.resources.sync_restore_button
import space.sovereigncast.hopp.resources.sync_restore_counts
import space.sovereigncast.hopp.resources.sync_restore_later
import space.sovereigncast.hopp.resources.sync_restore_text
import space.sovereigncast.hopp.resources.sync_restore_title
import space.sovereigncast.hopp.resources.nostr_anonymous
import space.sovereigncast.hopp.resources.nostr_bunker_failed
import space.sovereigncast.hopp.resources.nostr_bunker_auth_open
import space.sovereigncast.hopp.resources.nostr_bunker_auth
import space.sovereigncast.hopp.resources.nostr_bunker_waiting
import space.sovereigncast.hopp.resources.nostr_bunker_open
import space.sovereigncast.hopp.resources.nostr_bunker_offer_note
import space.sovereigncast.hopp.resources.nostr_bunker_offer
import space.sovereigncast.hopp.resources.nostr_bunker_connecting
import space.sovereigncast.hopp.resources.nostr_bunker_connect
import space.sovereigncast.hopp.resources.nostr_bunker_scan
import space.sovereigncast.hopp.resources.nostr_bunker_hint
import space.sovereigncast.hopp.resources.nostr_bunker_note
import space.sovereigncast.hopp.resources.nostr_bunker_title
import space.sovereigncast.hopp.resources.nostr_profile_loading
import space.sovereigncast.hopp.resources.nostr_sign_out_text_amber
import space.sovereigncast.hopp.resources.nostr_via_amber
import space.sovereigncast.hopp.resources.nostr_amber_failed
import space.sovereigncast.hopp.resources.nostr_amber_rejected
import space.sovereigncast.hopp.resources.nostr_amber
import space.sovereigncast.hopp.resources.nostr_anonymous_note
import space.sovereigncast.hopp.resources.*
import org.koin.compose.koinInject
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import space.sovereigncast.hopp.resources.nostr_boosts
import space.sovereigncast.hopp.resources.nostr_boosts_note
import space.sovereigncast.hopp.resources.nostr_backup
import space.sovereigncast.hopp.resources.nostr_backup_button
import space.sovereigncast.hopp.resources.nostr_backup_mismatch
import space.sovereigncast.hopp.resources.nostr_copy
import space.sovereigncast.hopp.resources.nostr_name
import space.sovereigncast.hopp.resources.nostr_password
import space.sovereigncast.hopp.resources.nostr_password_again
import space.sovereigncast.hopp.resources.nostr_publish_again
import space.sovereigncast.hopp.resources.nostr_publish_failed
import space.sovereigncast.hopp.resources.nostr_share
import space.sovereigncast.hopp.resources.nostr_sign_out
import space.sovereigncast.hopp.resources.nostr_relay_not_read
import space.sovereigncast.hopp.resources.secrets_unreadable
import space.sovereigncast.hopp.resources.nostr_sign_out_confirm
import space.sovereigncast.hopp.resources.nostr_sign_out_text
import space.sovereigncast.hopp.ui.LocalPlatformActions

/** Asks the Nostr page to open the ways in with an existing account at once —
 *  from the tour's "Restore from Nostr". Taken once. */
object NostrSignInRequest {
    private var pending = false
    fun request() { pending = true }
    fun take(): Boolean = pending.also { pending = false }
}

class NostrIdentityViewModel(
    private val identities: IdentityRepository,
    private val settings: SettingsRepository,
    private val sync: space.sovereigncast.hopp.sync.SyncManager? = null,
    private val contacts: space.sovereigncast.hopp.contacts.ContactsRepository? = null,
    /** Where a profile picture goes (maintainer 2026-11-03); null: none is uploaded. */
    private val blossom: space.sovereigncast.hopp.nostr.Blossom? = null,
) : ViewModel() {
    /** What the listener's Nostr contacts listen to (ADR 0024): the switches and the contacts read. */
    val contactsState: StateFlow<space.sovereigncast.hopp.contacts.ContactsState> =
        contacts?.state ?: MutableStateFlow(space.sovereigncast.hopp.contacts.ContactsState())
    /** On opening the page: what is older than five minutes is read again. [force]: now, the signer asked if needed. */
    fun refreshContacts(force: Boolean = false) { val c = contacts ?: return; viewModelScope.launch { if (force) c.refresh(force = true, interactive = true, maxAge = 300) else c.readToTheEnd(maxAge = 300) } }
    fun setContactsSharing(on: Boolean) { val c = contacts ?: return; viewModelScope.launch { c.setSharing(on) } }
    fun setContactsSeeing(on: Boolean) { val c = contacts ?: return; viewModelScope.launch { c.setSeeing(on) } }
    val identity: StateFlow<NostrIdentity?> = identities.identity
    /** A stored identity the key store could not give up — shown, never made anew (security review 2026-10-20). */
    val unreadable: StateFlow<Boolean> = identities.unreadable

    /** A library the listener's other devices left on the relays, while sync is off here. */
    private val _restore = MutableStateFlow<space.sovereigncast.hopp.sync.RestoreOffer?>(null)
    val restore: StateFlow<space.sovereigncast.hopp.sync.RestoreOffer?> = _restore.asStateFlow()
    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    /** Looked for on opening the page and after each sign-in with an existing identity. */
    private fun lookForRestore() {
        val sync = sync ?: return
        if (identities.identity.value == null) return
        viewModelScope.launch { _restore.value = runCatching { sync.restoreOffer() }.getOrNull() }
    }

    /** Sync on: what this device has and the relays' merged — on a new device, a restore.
     *  It runs on in the app, not in this page: leaving the page does not stop it. */
    fun restore() {
        val sync = sync ?: return
        if (_restoring.value) return
        _restoring.value = true
        viewModelScope.launch {
            try { sync.enableAndWait() } finally { _restoring.value = false; _restore.value = null }
        }
    }

    fun dismissRestore() { _restore.value = null }

    /** How sync stands, for the line under its switch. */
    val syncStatus: StateFlow<space.sovereigncast.hopp.sync.SyncStatus> = sync?.status ?: MutableStateFlow(space.sovereigncast.hopp.sync.SyncStatus())

    /** The switch: on merges and may restore (the signer may ask once); off forgets this device's notes. */
    fun setSync(on: Boolean) {
        val sync = sync ?: return
        if (on) restore() else viewModelScope.launch { sync.disable(); lookForRestore() }
    }

    /** A new sync key (ADR 0022, 3): null not asked. */
    private val _renewed = MutableStateFlow<space.sovereigncast.hopp.sync.Renewal?>(null)
    val renewed: StateFlow<space.sovereigncast.hopp.sync.Renewal?> = _renewed.asStateFlow()
    private val _renewing = MutableStateFlow(false)
    val renewing: StateFlow<Boolean> = _renewing.asStateFlow()

    fun renewSyncKey() {
        val sync = sync ?: return
        if (_renewing.value) return
        _renewing.value = true; _renewed.value = null
        viewModelScope.launch { try { _renewed.value = sync.renewKey() } finally { _renewing.value = false } }
    }

    /** A round now, the signer app allowed to ask — after it said it needs the listener. */
    fun syncNow() { val sync = sync ?: return; viewModelScope.launch { sync.syncNow(interactive = true) } }

    /** While the profile is looked up on the relays (device feedback 2026-10-16: nothing said so). */
    private val _loadingProfile = MutableStateFlow(false)
    val loadingProfile: StateFlow<Boolean> = _loadingProfile.asStateFlow()

    init {
        // A profile from a signer app can change elsewhere — and an older Hopp did not find it at all.
        if (identities.identity.value?.holdsKey == false) viewModelScope.launch {
            _loadingProfile.value = true
            try { runCatching { identities.refreshProfile() } } finally { _loadingProfile.value = false }
        }
        lookForRestore()
    }
    val current: StateFlow<Settings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    /** Relays that took the profile; null = not tried in this visit. */
    private val _published = MutableStateFlow<Int?>(null)
    val published: StateFlow<Int?> = _published.asStateFlow()
    private val _exported = MutableStateFlow<String?>(null)
    val exported: StateFlow<String?> = _exported.asStateFlow()
    /** Whether a signer app (Amber) is on the device — then signing in with it comes first. */
    val signerApp: Boolean = runCatching { identities.externalSigner?.available() == true }.getOrDefault(false)
    /** The signer is a browser extension (NIP-07), not an app. */
    val signerIsExtension: Boolean = identities.externalSigner?.browserExtension == true
    /** false in the browser: no key is made or pasted here, only signers that keep it (ADR 0022, 2). */
    val keysInApp: Boolean = identities.keysInApp
    /** What went wrong with the signer app, for one line under its button. */
    private val _signerError = MutableStateFlow<SignerError?>(null)
    val signerError: StateFlow<SignerError?> = _signerError.asStateFlow()
    enum class SignerError { Rejected, Failed }

    private companion object {
        /** A bunker address the signer will not take (again): Amber's and nsec.app's answers to `connect`. */
        val USED_UP = listOf("already connected", "invalid secret", "secret not in use", "no secret")
    }

    /** Signs in with the signer app; its profile name becomes the boost name when none is set. */
    fun signInWithSigner() = work {
        _signerError.value = null
        _loadingProfile.value = true
        try {
            val me = identities.signInWithSigner()
            val name = me.name
            if (name != null && settings.current().senderName.isBlank()) settings.update { it.copy(senderName = name) }
            lookForRestore()
        } catch (e: SignerRejected) {
            _signerError.value = SignerError.Rejected
        } catch (e: Exception) {
            _signerError.value = SignerError.Failed
        } finally {
            _loadingProfile.value = false
        }
    }

    /** The name also becomes the boost name when there is none yet (ADR 0021, 4). With [picture]
     *  (maintainer 2026-11-03), uploaded once the key is there and put into the new profile. */
    fun create(name: String, picture: ByteArray? = null) = work {
        if (settings.current().senderName.isBlank() && name.isNotBlank()) settings.update { it.copy(senderName = name.trim()) }
        _published.value = identities.create(name).size
        if (picture != null) {
            val url = runCatching { upload(picture) }.getOrNull()
            if (url == null) _pictureFailed.value = true
            else _published.value = identities.saveProfile(name, url, kotlinx.serialization.json.JsonObject(emptyMap())).size
        }
    }

    /** The picture chosen at the start could not be uploaded: said once on the page, with the way to try again. */
    private val _pictureFailed = MutableStateFlow(false)
    val pictureFailed: StateFlow<Boolean> = _pictureFailed.asStateFlow()

    /** Onto the listener's own Blossom servers in their order, else Hopp's choice; the first that takes it. */
    private suspend fun upload(bytes: ByteArray): String {
        val blossom = blossom ?: throw space.sovereigncast.hopp.nostr.BlossomFailed(null)
        val signer = identities.signer() ?: throw space.sovereigncast.hopp.nostr.BlossomFailed(null)
        var last: Exception? = null
        for (server in identities.blossomServers().take(3)) {
            try {
                return blossom.upload(server, bytes, "image/jpeg", signer, kotlin.time.Clock.System.now().epochSeconds)
            } catch (e: SignerRejected) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: space.sovereigncast.hopp.nostr.BlossomFailed(null)
    }

    /** The profile window (maintainer 2026-11-03): what the relays hold, then saving name and picture into it. */
    data class ProfileEditor(
        val reading: Boolean = true,
        /** The profile as read — kept whole but name and picture; null while reading or when not known. */
        val base: kotlinx.serialization.json.JsonObject? = null,
        /** Not found and not known to be empty: nothing is saved. */
        val unknown: Boolean = false,
        val step: Step? = null,
        val error: Error? = null,
        val errorDetail: String? = null,
        val done: Boolean = false,
    ) {
        enum class Step { Uploading, Signing }
        enum class Error { Upload, Relays, Signer }
        /** The picture in the profile as read. */
        val picture: String? get() = (base?.get("picture") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.startsWith("https://") }
    }

    /** What becomes of the picture on saving. */
    sealed interface PictureChange {
        data object Keep : PictureChange
        data object Remove : PictureChange
        data class Address(val url: String) : PictureChange
        data class Upload(val bytes: ByteArray) : PictureChange
    }

    private val _editor = MutableStateFlow<ProfileEditor?>(null)
    val editor: StateFlow<ProfileEditor?> = _editor.asStateFlow()

    fun openProfileEditor() {
        _editor.value = ProfileEditor()
        viewModelScope.launch {
            val read = runCatching { identities.readProfile() }.getOrDefault(space.sovereigncast.hopp.identity.ProfileRead.Unknown)
            _editor.value = when (read) {
                is space.sovereigncast.hopp.identity.ProfileRead.Found -> ProfileEditor(reading = false, base = read.content)
                space.sovereigncast.hopp.identity.ProfileRead.Empty -> ProfileEditor(reading = false, base = kotlinx.serialization.json.JsonObject(emptyMap()))
                space.sovereigncast.hopp.identity.ProfileRead.Unknown -> ProfileEditor(reading = false, unknown = true)
            }
        }
    }

    fun closeProfileEditor() { _editor.value = null }

    fun saveProfile(name: String, picture: PictureChange, asBoostName: Boolean) {
        val st = _editor.value ?: return
        val base = st.base ?: return
        if (st.step != null) return
        viewModelScope.launch {
            try {
                val url = when (picture) {
                    PictureChange.Keep -> st.picture
                    PictureChange.Remove -> null
                    is PictureChange.Address -> picture.url
                    is PictureChange.Upload -> {
                        _editor.value = st.copy(step = ProfileEditor.Step.Uploading, error = null)
                        upload(picture.bytes)
                    }
                }
                _editor.value = st.copy(step = ProfileEditor.Step.Signing, error = null)
                val took = identities.saveProfile(name, url, base)
                if (took.isEmpty()) { _editor.value = st.copy(error = ProfileEditor.Error.Relays); return@launch }
                if (asBoostName && name.isNotBlank()) settings.update { it.copy(senderName = name.trim()) }
                _pictureFailed.value = false
                _editor.value = st.copy(done = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignerRejected) {
                _editor.value = st.copy(error = ProfileEditor.Error.Signer)
            } catch (e: Exception) {
                val uploading = _editor.value?.step == ProfileEditor.Step.Uploading
                _editor.value = st.copy(
                    error = if (uploading) ProfileEditor.Error.Upload else ProfileEditor.Error.Relays,
                    errorDetail = (e as? space.sovereigncast.hopp.nostr.BlossomFailed)?.reason ?: e.message,
                )
            }
        }
    }

    /** A remote signer (NIP-46): connecting, a code shown for it, or what went wrong. */
    sealed interface Bunker {
        data object Idle : Bunker
        data object Connecting : Bunker
        data class Offer(val uri: String) : Bunker
        /** [usedUp]: the signer refused the address itself — taken before, or not its own. */
        data class Failed(val reason: String, val usedUp: Boolean = false) : Bunker
    }
    private val _bunker = MutableStateFlow<Bunker>(Bunker.Idle)
    val bunker: StateFlow<Bunker> = _bunker.asStateFlow()
    /** The signer wants the listener to confirm on one of its pages first. */
    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()
    /** The same, asked while signed in — opening the sync key, say. */
    val signerAuthUrl: StateFlow<String?> = identities.signerAuthUrl
    private var waiting: kotlinx.coroutines.Job? = null

    fun connectBunker(address: String) {
        if (_bunker.value == Bunker.Connecting) return
        _bunker.value = Bunker.Connecting
        waiting = viewModelScope.launch { finishBunker { identities.signInWithBunker(address) { _authUrl.value = it } } }
    }

    fun showConnectCode() {
        val offer = identities.nostrConnectOffer()
        _bunker.value = Bunker.Offer(offer.uri)
        waiting?.cancel()
        waiting = viewModelScope.launch { finishBunker { identities.signInWithNostrConnect(offer) } }
    }

    fun cancelBunker() { waiting?.cancel(); waiting = null; _authUrl.value = null; _bunker.value = Bunker.Idle }

    private suspend fun finishBunker(signIn: suspend () -> NostrIdentity) {
        _loadingProfile.value = true
        try {
            val me = signIn()
            me.name?.let { name -> if (settings.current().senderName.isBlank()) settings.update { it.copy(senderName = name) } }
            _bunker.value = Bunker.Idle
            lookForRestore()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e::class.simpleName.orEmpty()
            // Amber's words for an address it will not take again (device feedback 2026-10-21).
            val usedUp = e is space.sovereigncast.hopp.nostr.Nip46Exception && USED_UP.any { reason.contains(it, ignoreCase = true) }
            _bunker.value = Bunker.Failed(reason, usedUp)
        } finally {
            _authUrl.value = null
            _loadingProfile.value = false
        }
    }

    /** Saving to or getting from the password manager / iCloud Keychain. */
    enum class Vault { Idle, Working, Saved, Failed, NotFound }
    private val _vault = MutableStateFlow(Vault.Idle)
    val vault: StateFlow<Vault> = _vault.asStateFlow()
    /** Every key in the vault, the latest first, when it holds more than one; empty once one is chosen. */
    private val _vaultKeys = MutableStateFlow<List<String>>(emptyList())
    val vaultKeys: StateFlow<List<String>> = _vaultKeys.asStateFlow()
    private val _importError = MutableStateFlow<space.sovereigncast.hopp.identity.KeyImportException.Reason?>(null)
    val importError: StateFlow<space.sovereigncast.hopp.identity.KeyImportException.Reason?> = _importError.asStateFlow()

    /** The key into the password manager (Android) or the iCloud Keychain (iOS), under the npub. */
    fun saveToVault(vault: space.sovereigncast.hopp.platform.KeyVault) {
        val me = identities.identity.value ?: return
        val nsec = identities.nsec() ?: return
        _vault.value = Vault.Working
        viewModelScope.launch {
            val ok = runCatching { vault.save(me.npub, nsec) }.getOrDefault(false)
            if (ok) identities.markBackedUp()
            _vault.value = if (ok) Vault.Saved else Vault.Failed
        }
    }

    /** The nsec, copied after the warning: the listener has it now. */
    fun nsecForCopy(): String? = identities.nsec()?.also { identities.markBackedUp() }

    fun restoreFromVault(vault: space.sovereigncast.hopp.platform.KeyVault) {
        _vault.value = Vault.Working
        _vaultKeys.value = emptyList()
        viewModelScope.launch {
            val keys = runCatching { vault.saved() }.getOrDefault(emptyList())
            when {
                keys.isEmpty() -> _vault.value = Vault.NotFound
                keys.size == 1 -> { useKey(keys.single(), null); _vault.value = Vault.Idle }
                else -> { _vaultKeys.value = keys; _vault.value = Vault.Idle }
            }
        }
    }

    /** One of the keys [restoreFromVault] found beside the latest. */
    fun restoreVaultKey(nsec: String) {
        _vaultKeys.value = emptyList()
        viewModelScope.launch { useKey(nsec, null) }
    }

    fun importKey(text: String, password: String?) = viewModelScope.launch { useKey(text, password) }

    private suspend fun useKey(text: String, password: String?) {
        _importError.value = null
        _loadingProfile.value = true
        try {
            val me = identities.importKey(text, password)
            me.name?.let { name -> if (settings.current().senderName.isBlank()) settings.update { it.copy(senderName = name) } }
            lookForRestore()
        } catch (e: space.sovereigncast.hopp.identity.KeyImportException) {
            _importError.value = e.reason
        } finally {
            _loadingProfile.value = false
        }
    }

    fun publishAgain() = work { _published.value = identities.publishProfile().size }
    fun export(password: String) = work { _exported.value = identities.export(password) }
    fun setAnonymous(on: Boolean) = viewModelScope.launch { settings.update { it.copy(scAnonymous = on) } }

    /** The listener's relays (NIP-65), to look at and change (device feedback 2026-10-19). */
    sealed interface Relays {
        data object Loading : Relays
        data class Shown(val list: List<space.sovereigncast.hopp.identity.RelayEntry>, val working: Boolean = false, val problem: RelayProblem? = null, val pending: String? = null) : Relays
    }
    /** [Unreachable]: offered anyway; [NotRead]: the list was found nowhere and a relay did not answer, so nothing was written;
     *  [NotPublished]: no relay took the new list. */
    enum class RelayProblem { BadAddress, Unreachable, NotRead, NotPublished }
    private val _relays = MutableStateFlow<Relays>(Relays.Loading)
    val relays: StateFlow<Relays> = _relays.asStateFlow()

    fun loadRelays() = viewModelScope.launch {
        _relays.value = Relays.Shown(runCatching { identities.relays() }.getOrDefault(emptyList()))
    }

    /** Checked first: an address that does not answer is added only when asked again ([force]). */
    fun addRelay(text: String, force: Boolean = false) = changeRelays(text) {
        val url = space.sovereigncast.hopp.identity.IdentityRepository.relayAddress(text) ?: throw space.sovereigncast.hopp.identity.BadRelayAddress()
        if (!force && !identities.reachable(url)) throw Unreachable()
        identities.addRelay(url)
    }

    fun removeRelay(url: String) = changeRelays(null) { identities.removeRelay(url) }

    private class Unreachable : Exception()

    private fun changeRelays(pending: String?, change: suspend () -> List<space.sovereigncast.hopp.identity.RelayEntry>) {
        val shown = _relays.value as? Relays.Shown ?: return
        if (shown.working) return
        _relays.value = shown.copy(working = true, problem = null, pending = pending)
        viewModelScope.launch {
            _relays.value = runCatching { change() }.fold(
                { Relays.Shown(it) },
                { e ->
                    shown.copy(working = false, pending = pending, problem = when (e) {
                        is space.sovereigncast.hopp.identity.BadRelayAddress -> RelayProblem.BadAddress
                        is Unreachable -> RelayProblem.Unreachable
                        is space.sovereigncast.hopp.comments.ListNotRead -> RelayProblem.NotRead
                        else -> RelayProblem.NotPublished
                    })
                },
            )
        }
    }
    fun setNostrBoosts(on: Boolean) = viewModelScope.launch { settings.update { it.copy(nostrBoosts = on) } }
    fun signOut() {
        _exported.value = null; _published.value = null; _restore.value = null
        // Without a key there is nothing to sync with; the relays keep what they have.
        sync?.let { viewModelScope.launch { it.disable() } }
        contacts?.let { viewModelScope.launch { it.forget() } }
        // A bunker hears that the session is over — a courtesy, not waited on long.
        viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { runCatching { identities.endRemoteSession() } }
            identities.signOutCompletely()
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch { try { block() } finally { _busy.value = false } }
    }
}

/**
 * The optional Nostr identity (ADR 0021, stage 1): made here, named, backed up
 * with a password (NIP-49). Says before anything is published that it stays
 * public — on Nostr nothing can be taken back.
 */
/** Opens the listening statistics — set by the app around its pages. */
val LocalOpenStats = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun NostrIdentityPage(onSharedShows: () -> Unit = {}, vm: NostrIdentityViewModel = koinViewModel()) {
    val identity by vm.identity.collectAsState()
    val unreadable by vm.unreadable.collectAsState()
    val settings by vm.current.collectAsState()
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val me = identity
        // Device feedback 2026-10-16: too much text, too many ways at once. One way in for
        // the newcomer — a name and "Get started" —, everything for an existing account
        // behind one link; once in, the profile, one backup card, two switches.
        if (me == null && unreadable) Unreadable(vm) else if (me == null) Welcome(settings?.senderName.orEmpty(), busy, vm) else Signed(me, settings, busy, vm, onSharedShows)
    }
}

/** An identity is stored and cannot be read right now: said so, with the one way past it. */
@Composable
private fun Unreadable(vm: NostrIdentityViewModel) {
    Text(stringResource(Res.string.secrets_unreadable), color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.nostr_sign_out)) }
}

/** No identity yet: what it is, in a line, and one button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Welcome(boostName: String, busy: Boolean, vm: NostrIdentityViewModel) {
    var name by rememberSaveable(boostName) { mutableStateOf(boostName) }
    val toRestore = rememberSaveable { NostrSignInRequest.take() }
    var signIn by rememberSaveable { mutableStateOf(toRestore) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val actions = LocalPlatformActions.current
    // A picture for the new profile, optional (maintainer 2026-11-03) — where a key is made here and a picture can be picked.
    var picture by remember { mutableStateOf<ByteArray?>(null) }
    val withPicture = vm.keysInApp && actions.canPickImage
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (withPicture) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ProfilePicture(80.dp, null, picture, name.ifBlank { "+" }) { actions.pickProfileImage { picture = it } }
            Text(stringResource(Res.string.profile_picture_optional), color = muted, style = MaterialTheme.typography.bodySmall)
        } else Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(space.sovereigncast.hopp.ui.theme.Hopp.colors.let { it.nostr?.fill ?: it.value }), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(space.sovereigncast.hopp.ui.components.HoppIcons.Hop, null, Modifier.size(34.dp), tint = space.sovereigncast.hopp.ui.theme.Hopp.colors.let { it.nostr?.onFill ?: it.onValue })
        }
        Text(stringResource(Res.string.nostr_hero_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(stringResource(if (vm.keysInApp) Res.string.nostr_hero_text else Res.string.nostr_hero_text_web), style = MaterialTheme.typography.bodyLarge, color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (!vm.keysInApp) {
        // The browser holds no key (ADR 0022, 2): the ways in with a signer, right here — as cards (maintainer 2026-11-03).
        WebWays(busy, vm)
        return
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(Res.string.nostr_name)) }, singleLine = true)
    Button(onClick = { vm.create(name, picture) }, enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), colors = space.sovereigncast.hopp.ui.theme.nostrButtonColors()) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.nostr_start), fontWeight = FontWeight.Bold)
    }
    Text(stringResource(Res.string.nostr_public_line), Modifier.fillMaxWidth(), color = muted, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    TextButton(onClick = { signIn = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.nostr_have_account)) }
    if (signIn) ModalBottomSheet(onDismissRequest = { signIn = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.nostr_have_account), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            // From the tour's "Restore": what the key is for here.
            if (toRestore) Text(stringResource(Res.string.nostr_restore_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SignIn(busy, vm)
        }
    }
}

/** The ways in with an existing account, easiest first: a signer app, the password manager, the key, a bunker. */
@Composable
private fun SignIn(busy: Boolean, vm: NostrIdentityViewModel) {
    val actions = LocalPlatformActions.current
    val vault = actions.keyVault
    val state by vm.vault.collectAsState()
    val error by vm.importError.collectAsState()
    val signerError by vm.signerError.collectAsState()
    // Not saveable: a pasted key stays out of the saved state, as the wallet's
    // connection string does (code review 2026-10-17, H9).
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // In the browser a signer elsewhere is one of the two ways in: open from the start.
    var bunker by rememberSaveable { mutableStateOf(!vm.keysInApp) }
    val locked = text.trim().removePrefix("nostr:").startsWith("ncryptsec1")
    if (vm.signerApp) {
        val ext = vm.signerIsExtension
        Button(onClick = vm::signInWithSigner, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = space.sovereigncast.hopp.ui.theme.nostrButtonColors()) { Text(stringResource(if (ext) Res.string.nostr_extension else Res.string.nostr_amber)) }
        signerError?.let {
            Text(stringResource(when {
                it == NostrIdentityViewModel.SignerError.Rejected -> if (ext) Res.string.nostr_extension_rejected else Res.string.nostr_amber_rejected
                else -> if (ext) Res.string.nostr_extension_failed else Res.string.nostr_amber_failed
            }),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (vault != null && vm.keysInApp) {
        val iCloud = vault.kind == space.sovereigncast.hopp.platform.KeyVault.Kind.ICloudKeychain
        val saved by vm.vaultKeys.collectAsState()
        OutlinedButton(onClick = { vm.restoreFromVault(vault) }, enabled = state != NostrIdentityViewModel.Vault.Working, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (iCloud) Res.string.nostr_vault_load_ios else Res.string.nostr_vault_load_android))
        }
        // The npub is worked out once per list, not at every recomposition.
        val labelled = remember(saved) { saved.map { it to vaultKeyLabel(it) } }
        labelled.forEach { (nsec, label) ->
            OutlinedButton(onClick = { vm.restoreVaultKey(nsec) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
        if (state == NostrIdentityViewModel.Vault.NotFound) Text(stringResource(Res.string.nostr_vault_none), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (vm.keysInApp) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(Res.string.nostr_import_hint)) },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    if (locked) OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(Res.string.nostr_import_password)) }, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    error?.let {
        Text(stringResource(when (it) {
            space.sovereigncast.hopp.identity.KeyImportException.Reason.WrongPassword -> Res.string.nostr_import_wrong_password
            space.sovereigncast.hopp.identity.KeyImportException.Reason.NeedsPassword -> Res.string.nostr_import_password
            space.sovereigncast.hopp.identity.KeyImportException.Reason.Unreadable -> Res.string.nostr_import_unreadable
        }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (text.isNotBlank()) OutlinedButton(onClick = { vm.importKey(text, password.takeIf { locked }) }, enabled = !busy && (!locked || password.isNotEmpty()), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.nostr_import_button))
    }
    // A remote signer: for those who keep their key on a server or device (and on iOS the way to an outside signer).
    TextButton(onClick = { bunker = !bunker }) { Text(stringResource(Res.string.nostr_bunker_row) + if (bunker) " ▴" else " ▾") }
    if (bunker) BunkerLogin(vm)
}

/**
 * Signing in in a browser (maintainer 2026-11-03): three ways as cards, the easiest first — an extension (on a
 * computer; on a phone, where none can be installed, only when one is there all the same), a signer app by its
 * code, a bunker address — a line under them that the browser keeps no key, and "Not my device" at the foot.
 */
@Composable
private fun WebWays(busy: Boolean, vm: NostrIdentityViewModel) {
    val actions = LocalPlatformActions.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val state by vm.bunker.collectAsState()
    val signerError by vm.signerError.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val extension = vm.signerApp && vm.signerIsExtension
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (extension) {
            WayCard(Res.string.web_way_extension, stringResource(Res.string.web_way_extension_note), enabled = !busy) { vm.signInWithSigner() }
            signerError?.let {
                Text(stringResource(if (it == NostrIdentityViewModel.SignerError.Rejected) Res.string.nostr_extension_rejected else Res.string.nostr_extension_failed),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        } else if (!actions.mobileBrowser) {
            WayCard(Res.string.web_way_extension, stringResource(Res.string.web_way_extension_none), enabled = true, dimmed = true) {
                runCatching { uri.openUri("https://getalby.com/") }
            }
        }
        WayCard(Res.string.web_way_signer, stringResource(Res.string.web_way_signer_note), enabled = true, expanded = open == "code") {
            if (open == "code") { open = null; vm.cancelBunker() } else { open = "code"; vm.showConnectCode() }
        }
        if (open == "code") when (val s = state) {
            is NostrIdentityViewModel.Bunker.Offer -> {
                space.sovereigncast.hopp.ui.components.QrImage(s.uri, stringResource(Res.string.nostr_bunker_qr),
                    Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 220.dp).padding(vertical = 4.dp))
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    OutlinedButton(onClick = { runCatching { uri.openUri(s.uri) } }) { Text(stringResource(Res.string.nostr_bunker_open)) }
                    OutlinedButton(onClick = { actions.copyText(s.uri, "nostrconnect") }) { Text(stringResource(Res.string.nostr_copy)) }
                }
                Waiting(stringResource(Res.string.nostr_bunker_waiting), vm)
            }
            NostrIdentityViewModel.Bunker.Connecting -> Waiting(stringResource(Res.string.nostr_bunker_connecting), vm)
            is NostrIdentityViewModel.Bunker.Failed -> BunkerFailed(s)
            else -> Unit
        }
        WayCard(Res.string.web_way_bunker, stringResource(Res.string.web_way_bunker_note), enabled = true, expanded = open == "bunker") {
            if (open == "bunker") open = null else { open = "bunker"; vm.cancelBunker() }
        }
        if (open == "bunker") BunkerAddress(vm, state)
        vm.authUrl.collectAsState().value?.let { url ->
            Text(stringResource(Res.string.nostr_bunker_auth), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(Res.string.nostr_bunker_auth_open)) }
        }
        Text(stringResource(Res.string.web_no_key), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        actions.tabOnly?.let { HorizontalDivider(Modifier.padding(vertical = 4.dp)); TabOnlyRow(it) }
    }
}

/** One way in, as a card: its name, a line, and where it leads; [expanded] while its part is open below it. */
@Composable
private fun WayCard(title: org.jetbrains.compose.resources.StringResource, note: String, enabled: Boolean, expanded: Boolean = false, dimmed: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, if (expanded) colors.onSurface else colors.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)
            .then(if (dimmed) Modifier.alpha(0.6f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text(if (expanded) "⌃" else "›", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
    }
}

/** A bunker:// address pasted (or scanned) and connected. */
@Composable
private fun BunkerAddress(vm: NostrIdentityViewModel, state: NostrIdentityViewModel.Bunker) {
    val actions = LocalPlatformActions.current
    var address by remember { mutableStateOf("") } // may carry the bunker's secret (H9)
    if (state == NostrIdentityViewModel.Bunker.Connecting) { Waiting(stringResource(Res.string.nostr_bunker_connecting), vm); return }
    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(Res.string.nostr_bunker_hint)) })
    (state as? NostrIdentityViewModel.Bunker.Failed)?.let { BunkerFailed(it) }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.connectBunker(address) }, enabled = address.trim().startsWith("bunker://")) { Text(stringResource(Res.string.nostr_bunker_connect)) }
        if (actions.canScanQr) OutlinedButton(onClick = { actions.scanQr { text -> address = text.trim(); vm.connectBunker(address) } }) {
            Text(stringResource(Res.string.nostr_bunker_scan))
        }
    }
}

@Composable
private fun BunkerFailed(f: NostrIdentityViewModel.Bunker.Failed) = Text(
    if (f.usedUp) stringResource(Res.string.nostr_bunker_used_up) else stringResource(Res.string.nostr_bunker_failed, f.reason),
    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
)

@Composable
private fun BunkerLogin(vm: NostrIdentityViewModel) {
    val state by vm.bunker.collectAsState()
    val authUrl by vm.authUrl.collectAsState()
    val actions = LocalPlatformActions.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var address by remember { mutableStateOf("") } // may carry the bunker's secret (H9)
    Text(stringResource(Res.string.nostr_bunker_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text(stringResource(Res.string.nostr_bunker_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (val s = state) {
        is NostrIdentityViewModel.Bunker.Offer -> {
            Text(stringResource(Res.string.nostr_bunker_offer_note), style = MaterialTheme.typography.bodySmall)
            // Scanned by a signer on the phone — the easy way from a computer (maintainer 2026-10-28).
            space.sovereigncast.hopp.ui.components.QrImage(s.uri, stringResource(Res.string.nostr_bunker_qr),
                Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 240.dp).padding(vertical = 4.dp))
            SelectionContainer { Text(s.uri, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { uri.openUri(s.uri) } }) { Text(stringResource(Res.string.nostr_bunker_open)) }
                OutlinedButton(onClick = { actions.copyText(s.uri, "nostrconnect") }) { Text(stringResource(Res.string.nostr_copy)) }
                OutlinedButton(onClick = { actions.shareText(s.uri, "Nostr") }) { Text(stringResource(Res.string.nostr_share)) }
            }
            Waiting(stringResource(Res.string.nostr_bunker_waiting), vm)
        }
        NostrIdentityViewModel.Bunker.Connecting -> Waiting(stringResource(Res.string.nostr_bunker_connecting), vm)
        else -> {
            OutlinedTextField(
                address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(Res.string.nostr_bunker_hint)) },
            )
            (state as? NostrIdentityViewModel.Bunker.Failed)?.let {
                Text(
                    if (it.usedUp) stringResource(Res.string.nostr_bunker_used_up) else stringResource(Res.string.nostr_bunker_failed, it.reason),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.connectBunker(address) }, enabled = address.trim().startsWith("bunker://")) { Text(stringResource(Res.string.nostr_bunker_connect)) }
                // Scanned, it connects at once — as the wallet's code does.
                if (actions.canScanQr) OutlinedButton(onClick = { actions.scanQr { text -> address = text.trim(); vm.connectBunker(address) } }) {
                    Text(stringResource(Res.string.nostr_bunker_scan))
                }
            }
            TextButton(onClick = vm::showConnectCode) { Text(stringResource(Res.string.nostr_bunker_offer)) }
        }
    }
    authUrl?.let { url ->
        Text(stringResource(Res.string.nostr_bunker_auth), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(Res.string.nostr_bunker_auth_open)) }
    }
}

@Composable
private fun Waiting(text: String, vm: NostrIdentityViewModel) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = vm::cancelBunker) { Text(stringResource(Res.string.cancel)) }
}

@Composable
private fun Signed(me: NostrIdentity, settings: Settings?, busy: Boolean, vm: NostrIdentityViewModel, onSharedShows: () -> Unit) {
    val actions = LocalPlatformActions.current
    val published by vm.published.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmSignOut by remember { mutableStateOf(false) }

    val loading by vm.loadingProfile.collectAsState()
    // The profile window (maintainer 2026-11-03): from the picture or the name, from the profile menu, or an empty profile's card.
    var editing by remember { mutableStateOf(ProfileEditRequest.take()) }
    if (editing) ProfileSheet(me, settings?.senderName.orEmpty(), vm) { editing = false }
    if (!loading && me.name == null && me.picture == null) EmptyProfileCard { editing = true }
    // Who: picture, name, the npub short with a copy button.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfilePicture(56.dp, me.picture, null, me.name ?: "N") { editing = true }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = stringResource(Res.string.profile_edit)) { editing = true }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(me.name ?: stringResource(Res.string.nostr_profile_loading).takeIf { loading } ?: "Nostr", style = MaterialTheme.typography.titleLarge)
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(me.npub.take(12) + "…" + me.npub.takeLast(6), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        TextButton(onClick = { actions.copyText(me.npub, "npub") }) { Text(stringResource(Res.string.nostr_copy)) }
    }
    // The profile's own numbers: what was heard and given (maintainer 2026-10-26) — a link under the name, not a
    // button of its own (2026-11-08).
    val openStats = LocalOpenStats.current
    Text(
        stringResource(Res.string.stats_nostr_link) + " ›",
        Modifier.padding(start = 68.dp).clip(RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = openStats)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
    )
    val pictureFailed by vm.pictureFailed.collectAsState()
    if (pictureFailed) Text(stringResource(Res.string.profile_welcome_failed), Modifier.clickable { editing = true }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (!me.holdsKey) Text(stringResource(if (me.signerPackage == space.sovereigncast.hopp.nostr.ExternalSigner.NIP07_PACKAGE) Res.string.nostr_via_extension else Res.string.nostr_via_amber), color = muted, style = MaterialTheme.typography.bodySmall)
    actions.tabOnly?.let { tabOnly ->
        val active by tabOnly.active.collectAsState()
        if (active) Text(stringResource(Res.string.nostr_tab_only_on), color = muted, style = MaterialTheme.typography.bodySmall)
    }
    // Only when something is wrong: the profile reached no relay.
    if (published == 0) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.nostr_publish_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::publishAgain, enabled = !busy) { Text(stringResource(Res.string.nostr_publish_again)) }
    }

    val offer by vm.restore.collectAsState()
    offer?.let { RestoreCard(it, vm) }

    // A key Hopp holds lives only on this device and in no backup (ADR 0015).
    if (me.holdsKey) Backup(me, busy, vm)

    // Each part under a heading, the rare things folded away (maintainer 2026-11-08).
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(Res.string.nostr_section_sync), style = MaterialTheme.typography.titleMedium)
    val restoring by vm.restoring.collectAsState()
    SwitchRow(stringResource(Res.string.sync_title), stringResource(Res.string.sync_note), settings?.nostrSync == true || restoring) { if (!restoring) vm.setSync(it) }
    if (settings?.nostrSync == true) {
        SyncStatusLine(vm)
        SyncActions(vm, now = true)
    }
    ContactsSection(vm, onSharedShows)
    // What goes out in public, apart from the contacts (maintainer 2026-11-08).
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(Res.string.nostr_section_public), style = MaterialTheme.typography.titleMedium)
    SwitchRow(stringResource(Res.string.nostr_boosts), stringResource(Res.string.nostr_boosts_note), settings?.nostrBoosts == true, onChange = vm::setNostrBoosts)
    SwitchRow(stringResource(Res.string.nostr_anonymous), stringResource(Res.string.nostr_anonymous_note), settings?.scAnonymous == true, onChange = vm::setAnonymous)
    // Rarely needed: the sync key to show — for a relay that takes only keys it knows — and, only where the key is
    // elsewhere, a new one (a lost phone that held the key itself has it); the relays.
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    var advanced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .toggleable(advanced, role = androidx.compose.ui.semantics.Role.Button, onValueChange = { advanced = it }).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.nostr_section_advanced), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(if (advanced) "▴" else "▾", color = muted, style = MaterialTheme.typography.titleMedium)
    }
    if (advanced) {
        if (settings?.nostrSync == true) SyncActions(vm, now = false, renew = !me.holdsKey)
        var relaysOpen by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.loadRelays(); relaysOpen = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.nostr_relays))
                Text(stringResource(Res.string.nostr_relays_note), color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", color = muted, style = MaterialTheme.typography.titleLarge)
        }
        if (relaysOpen) RelaysSheet(vm) { relaysOpen = false }
    }

    TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(Res.string.nostr_sign_out), color = MaterialTheme.colorScheme.error)
    }
    if (confirmSignOut) SignOutDialog(me, vm) { confirmSignOut = false }
}

/** Signing out, asked first — what goes and what stays said for where the key is. Here and from the profile menu. */
@Composable
internal fun SignOutDialog(me: NostrIdentity, vm: NostrIdentityViewModel, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(Res.string.nostr_sign_out)) },
    text = { Text(stringResource(when {
        !vm.keysInApp -> Res.string.nostr_sign_out_text_web
        me.holdsKey -> Res.string.nostr_sign_out_text
        else -> Res.string.nostr_sign_out_text_amber
    })) },
    confirmButton = { TextButton(onClick = { onDismiss(); vm.signOut() }) { Text(stringResource(Res.string.nostr_sign_out_confirm), color = MaterialTheme.colorScheme.error) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
)

/**
 * What the listener's Nostr contacts listen to (ADR 0024): sharing with mutual contacts and
 * looking at what contacts share, each a switch. The first one turned on publishes the contacts
 * note — said before, as every first public act is (ADR 0021, 8).
 */
@Composable
private fun ContactsSection(vm: NostrIdentityViewModel, onSharedShows: () -> Unit) {
    val state by vm.contactsState.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(Unit) { vm.refreshContacts() }
    var ask by remember { mutableStateOf<(() -> Unit)?>(null) }
    val note = state.note
    // Only the first note is announced: once there is one, changing it publishes nothing new about the listener.
    val turnOn = { act: () -> Unit -> if (note == null) ask = act else act() }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(stringResource(Res.string.contacts_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(Res.string.contacts_intro), color = muted, style = MaterialTheme.typography.bodySmall)
    val me by vm.identity.collectAsState()
    // Turned on on another device: its keys come over with one tap — shown at once, not after a read (maintainer 2026-11-08).
    val elsewhere = note != null && !note.opened
    if (elsewhere) TakeOverCard(state.working, holdsKey = me?.holdsKey == true) { vm.refreshContacts(force = true) }
    // Until then a switch says so: on, but not on this device yet.
    val notHere = stringResource(Res.string.contacts_not_here)
    SwitchRow(stringResource(Res.string.contacts_share), if (elsewhere) notHere else stringResource(Res.string.contacts_share_note), note?.sharing == true, enabled = !state.working) { on ->
        if (on) turnOn { vm.setContactsSharing(true) } else vm.setContactsSharing(false)
    }
    // Always, sharing off too: which shows go is chosen before the first share (maintainer 2026-11-08).
    SharedShowsRow(note?.sharing == true, onSharedShows)
    SwitchRow(stringResource(Res.string.contacts_see), if (elsewhere) notHere else stringResource(Res.string.contacts_see_note), note?.seeing == true, enabled = !state.working) { on ->
        if (on) turnOn { vm.setContactsSeeing(true) } else vm.setContactsSeeing(false)
    }
    // A mutual contact lost, and the signer has to be asked for the new share key (ADR 0024, 4).
    if (state.renewPending && !state.working) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.contacts_renew_text), Modifier.weight(1f), color = muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.refreshContacts(force = true) }) { Text(stringResource(Res.string.contacts_renew)) }
    }
    // Shared, but not with this inbox yet: said, rather than left to look like all there is (maintainer 2026-11-08).
    val waiting = state.read?.waiting.orEmpty().takeIf { note?.seeing == true && note.opened && state.reading == null }.orEmpty()
    if (waiting.isNotEmpty()) Text(
        if (waiting.size == 1) stringResource(Res.string.contacts_waiting_one, space.sovereigncast.hopp.ui.library.contactNames(waiting))
        else stringResource(Res.string.contacts_waiting_more, waiting.size),
        color = muted, style = MaterialTheme.typography.bodySmall,
    )
    ContactsReadLine(state.read, state.reading.takeIf { note != null })
    state.error?.let { Text(stringResource(Res.string.contacts_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    ask?.let { act ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(Res.string.contacts_public_title)) },
            text = { Text(stringResource(Res.string.contacts_public_text)) },
            confirmButton = { TextButton(onClick = { ask = null; act() }) { Text(stringResource(Res.string.contacts_public_confirm)) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** The contacts note made on another device, and the one tap that takes its keys over to this one. */
@Composable
private fun TakeOverCard(working: Boolean, holdsKey: Boolean, onTakeOver: () -> Unit) {
    val nostr = space.sovereigncast.hopp.ui.theme.Hopp.colors.nostr
    androidx.compose.material3.Surface(
        color = nostr?.tint ?: MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.contacts_elsewhere_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(Res.string.contacts_elsewhere_text), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (working) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                // A signer elsewhere has to say yes; a key Hopp holds needs no one.
                Text(stringResource(if (holdsKey) Res.string.contacts_taking_over else Res.string.contacts_confirm_signer), style = MaterialTheme.typography.bodySmall)
            } else Button(onClick = onTakeOver, colors = space.sovereigncast.hopp.ui.theme.nostrButtonColors()) { Text(stringResource(Res.string.contacts_take_over)) }
        }
    }
}

/**
 * What the contacts read found: while a read is under way, how far it got — the contacts, then who follows
 * back, then what they share — and the counts as soon as they are known; otherwise the counts of the last
 * read and how old it is (maintainer 2026-11-08: a minute of "Looking at your contacts" said nothing).
 */
@Composable
private fun ContactsReadLine(read: space.sovereigncast.hopp.contacts.ContactsRead?, reading: space.sovereigncast.hopp.contacts.ReadProgress?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val counts = when {
        reading?.mutual != null -> stringResource(Res.string.contacts_counts, reading.following ?: 0, reading.mutual, reading.withHopp ?: 0)
        read == null -> null
        read.following.isEmpty() -> stringResource(Res.string.contacts_none)
        else -> stringResource(Res.string.contacts_counts, read.following.size, read.mutual.size, read.withHopp.size)
    }
    counts?.let { Text(it, color = muted, style = MaterialTheme.typography.bodySmall) }
    if (reading != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            when {
                reading.following == null -> stringResource(Res.string.contacts_step_list)
                reading.mutual == null -> pluralStringResource(Res.plurals.contacts_step_mutual, reading.following, reading.following)
                else -> stringResource(Res.string.contacts_step_shared)
            },
            color = muted, style = MaterialTheme.typography.bodySmall,
        )
    } else if (read != null && read.following.isNotEmpty()) {
        val now = remember(read.at) { kotlin.time.Clock.System.now().epochSeconds }
        Text(stringResource(Res.string.contacts_updated, space.sovereigncast.hopp.ui.library.ago((now - read.at).coerceAtLeast(0))), color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The way to every show and whether it is shared (maintainer 2026-11-02), right below the switch —
 * with sharing off too, so a show can be left out before anything goes out.
 */
@Composable
private fun SharedShowsRow(sharing: Boolean, onClick: () -> Unit, podcasts: space.sovereigncast.hopp.data.PodcastRepository = org.koin.compose.koinInject()) {
    val shows by podcasts.shows.collectAsState(emptyList())
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shared = shows.count { !it.contactsHidden }
    val counts = stringResource(Res.string.contacts_shared_summary, shared, shows.size)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.contacts_shared_title))
            Text(if (sharing) counts else stringResource(Res.string.contacts_shared_later) + " · " + counts, color = muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = muted)
    }
}

/** Under the sync switch: when it last got through, or why not — and a way on where there is one. */
@Composable
private fun SyncStatusLine(vm: NostrIdentityViewModel) {
    val status by vm.syncStatus.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Ticks once a minute, so "min ago" stays true while the page is open.
    val now by produceState(kotlin.time.Clock.System.now().toEpochMilliseconds()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }
    val problem = status.problem
    val text = when {
        status.running -> stringResource(Res.string.sync_running)
        problem is space.sovereigncast.hopp.sync.SyncOutcome.WaitsForSigner -> stringResource(Res.string.sync_waits)
        problem is space.sovereigncast.hopp.sync.SyncOutcome.NeedsUpdate -> stringResource(Res.string.sync_newer)
        // Security audit 2026-10-22: a document only in part, or too large, is said as such (H-02, M-04).
        problem is space.sovereigncast.hopp.sync.SyncOutcome.Incomplete -> stringResource(Res.string.sync_incomplete)
        problem is space.sovereigncast.hopp.sync.SyncOutcome.Failed && problem.error is space.sovereigncast.hopp.sync.SyncTooLarge -> stringResource(Res.string.sync_too_large)
        // The signer's silence and its no, said as such (maintainer 2026-11-04) — not "has to allow it".
        problem is space.sovereigncast.hopp.sync.SyncOutcome.Failed && problem.error is space.sovereigncast.hopp.nostr.SignerNoAnswer -> stringResource(Res.string.sync_signer_silent)
        // Not a "no": the request never reached it — and where it was sent, to see what to change.
        problem is space.sovereigncast.hopp.sync.SyncOutcome.Failed && problem.error is space.sovereigncast.hopp.nostr.SignerUnreachable ->
            stringResource(Res.string.sync_signer_unreachable, (problem.error as space.sovereigncast.hopp.nostr.SignerUnreachable).relays.joinToString(", ") { it.substringAfter("://") })
        problem is space.sovereigncast.hopp.sync.SyncOutcome.Failed && problem.error is space.sovereigncast.hopp.nostr.Nip46Exception ->
            stringResource(Res.string.sync_signer_refused, problem.error.message.orEmpty())
        // What the relays did, said as it was (delta audit 2026-10-22, LN-01): not "no relay" when two of three answered.
        problem is space.sovereigncast.hopp.sync.SyncOutcome.Failed -> when (val e = problem.error) {
            is space.sovereigncast.hopp.sync.SyncNotReached -> relayLine(e.answered, e.asked)
            is space.sovereigncast.hopp.sync.SyncKeyringNotKnown -> relayLine(e.answered, e.asked)
            is space.sovereigncast.hopp.sync.SyncNotPublished ->
                if (e.asked > 0) stringResource(Res.string.sync_not_taken, e.took, e.asked) else stringResource(Res.string.sync_failed)
            else -> stringResource(Res.string.sync_failed)
        }
        else -> status.lastSyncedMs?.let { last ->
            val minutes = ((now - last) / 60_000).coerceAtLeast(0)
            when {
                minutes < 1 -> stringResource(Res.string.sync_last_now)
                minutes < 60 -> stringResource(Res.string.sync_last_minutes, minutes.toInt())
                else -> stringResource(Res.string.sync_last_hours, (minutes / 60).toInt())
            }
        }?.let { line -> if (status.pending > 0) line + " · " + stringResource(Res.string.sync_pending, status.pending) else line }
    }
    Text(text.orEmpty(), color = if (problem != null && !status.running) MaterialTheme.colorScheme.error else muted, style = MaterialTheme.typography.bodySmall)
    // Only a round the listener starts asks the signer: the way to it, right at the line (maintainer 2026-11-04).
    if (problem?.isTheSigner() == true && !status.running) OutlinedButton(onClick = vm::syncNow) { Text(stringResource(Res.string.sync_allow_now)) }
    val authUrl by vm.signerAuthUrl.collectAsState()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    authUrl?.let { url ->
        Text(stringResource(Res.string.nostr_bunker_auth), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(Res.string.nostr_bunker_auth_open)) }
    }
}

/** Too few relays answered: none at all, or how many of how many. */
@Composable
private fun relayLine(answered: Int, asked: Int): String =
    if (answered == 0 || asked == 0) stringResource(Res.string.sync_no_relay) else stringResource(Res.string.sync_too_few, answered, asked)

/**
 * Under the sync line, in one row: a round now; the sync key's public half (Dennis, maintainer 2026-11-03: a relay
 * that takes only its owner's or known keys — Haven, NIP-05 — can be told this one); where [renew], a new sync key
 * for a lost device or a browser no longer used (ADR 0022, 3) — asked first.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
/** Sync now, with the sync's state; or ([now] false) the sync key to show and, with [renew], a new one. */
private fun SyncActions(vm: NostrIdentityViewModel, now: Boolean, renew: Boolean = false, keys: space.sovereigncast.hopp.sync.SyncKeys = org.koin.compose.koinInject()) {
    val status by vm.syncStatus.collectAsState()
    val renewing by vm.renewing.collectAsState()
    val renewed by vm.renewed.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = if (now) Arrangement.End else Arrangement.Start, verticalArrangement = Arrangement.Center) {
        if (now && !status.running) TextButton(onClick = vm::syncNow) { Text(stringResource(Res.string.sync_now)) }
        if (!now) TextButton(onClick = { showKey = keys.publicKey() }) { Text(stringResource(Res.string.sync_key_show)) }
        if (!now && renew) {
            if (renewing) CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { confirm = true }) { Text(stringResource(Res.string.sync_renew_short)) }
        }
    }
    renewed?.let { r ->
        Text(
            when (r) {
                space.sovereigncast.hopp.sync.Renewal.Done -> stringResource(Res.string.sync_renewed)
                space.sovereigncast.hopp.sync.Renewal.Unfinished -> stringResource(Res.string.sync_renew_unfinished)
                space.sovereigncast.hopp.sync.Renewal.NotRenewed -> stringResource(Res.string.sync_renew_failed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (r == space.sovereigncast.hopp.sync.Renewal.NotRenewed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    showKey?.let { hex ->
        val npub = space.sovereigncast.hopp.nostr.Bech32.encode("npub", hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val actions = LocalPlatformActions.current
        val title = stringResource(Res.string.sync_key_title)
        AlertDialog(
            onDismissRequest = { showKey = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.sync_key_text), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(npub, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actions.copyText(npub, title) }) { Text(stringResource(Res.string.sync_key_copy)) } },
            dismissButton = { TextButton(onClick = { showKey = null }) { Text(stringResource(Res.string.boost_close)) } },
        )
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(Res.string.sync_renew)) },
        text = { Text(stringResource(Res.string.sync_renew_text)) },
        confirmButton = { TextButton(onClick = { confirm = false; vm.renewSyncKey() }) { Text(stringResource(Res.string.sync_renew_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The listener's other devices left a library on the relays (ADR 0021, 5): one button takes it. */
@Composable
private fun RestoreCard(offer: space.sovereigncast.hopp.sync.RestoreOffer, vm: NostrIdentityViewModel) {
    val restoring by vm.restoring.collectAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.sync_restore_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (offer.shows != null && offer.inProgress != null) Text(stringResource(Res.string.sync_restore_counts, offer.shows, offer.inProgress), fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.sync_restore_text), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::restore, enabled = !restoring) {
                if (restoring) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.sync_restore_button))
            }
            TextButton(onClick = vm::dismissRestore, enabled = !restoring) { Text(stringResource(Res.string.sync_restore_later)) }
        }
    }
}

/**
 * The listener's relays (NIP-65): what Hopp writes to and reads comments
 * from too — the same list other Nostr apps use. Relays that ask to log in
 * (NIP-42) do not work yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaysSheet(vm: NostrIdentityViewModel, onDismiss: () -> Unit) {
    val state by vm.relays.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var address by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.nostr_relays), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.nostr_relays_text), color = muted, style = MaterialTheme.typography.bodySmall)
            when (val s = state) {
                NostrIdentityViewModel.Relays.Loading -> CircularProgressIndicator(Modifier.padding(8.dp))
                is NostrIdentityViewModel.Relays.Shown -> {
                    val writers = s.list.count { it.write }
                    s.list.forEach { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.url.removePrefix("wss://"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(when {
                                    r.read && r.write -> Res.string.nostr_relay_both
                                    r.write -> Res.string.nostr_relay_write
                                    else -> Res.string.nostr_relay_read
                                }), color = muted, style = MaterialTheme.typography.bodySmall)
                            }
                            // The last one written to stays: without it nothing could be published.
                            val remove = stringResource(Res.string.nostr_relay_remove, r.url)
                            IconButton(onClick = { vm.removeRelay(r.url) }, enabled = !s.working && !(r.write && writers <= 1)) {
                                Icon(androidx.compose.material.icons.Icons.Rounded.Close, remove)
                            }
                        }
                    }
                    OutlinedTextField(
                        address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("wss://relay.example.com") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                    )
                    if (s.problem != null) Text(stringResource(when (s.problem) {
                        NostrIdentityViewModel.RelayProblem.BadAddress -> Res.string.nostr_relay_bad
                        NostrIdentityViewModel.RelayProblem.Unreachable -> Res.string.nostr_relay_unreachable
                        NostrIdentityViewModel.RelayProblem.NotRead -> Res.string.nostr_relay_not_read
                        NostrIdentityViewModel.RelayProblem.NotPublished -> Res.string.nostr_relay_not_published
                    }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.addRelay(address) }, enabled = !s.working && address.isNotBlank()) { Text(stringResource(Res.string.nostr_relay_add)) }
                        if (s.problem == NostrIdentityViewModel.RelayProblem.Unreachable && s.pending == address)
                            TextButton(onClick = { vm.addRelay(address, force = true) }, enabled = !s.working) { Text(stringResource(Res.string.nostr_relay_add_anyway)) }
                        if (s.working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
    // Added: the field empties for the next one.
    val shown = state as? NostrIdentityViewModel.Relays.Shown
    LaunchedEffect(shown?.list?.size) { if (shown != null && shown.problem == null && !shown.working) address = "" }
}

/** "This is not my device" (ADR 0022, 2): once on, until the tab closes — there is no way back to keeping. */
@Composable
private fun TabOnlyRow(tabOnly: space.sovereigncast.hopp.platform.TabOnly) {
    val active by tabOnly.active.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    SwitchRow(stringResource(Res.string.nostr_tab_only), stringResource(if (active) Res.string.nostr_tab_only_on else Res.string.nostr_tab_only_note),
        active || working, enabled = !active && !working) {
        working = true
        scope.launch { try { tabOnly.begin() } finally { working = false } }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The whole row switches, read out as one switch with its title.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .toggleable(checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        // Room between the words and the switch: on a phone they touched (maintainer 2026-11-08).
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * The backup of a key Hopp holds, as one card: not yet backed up, one button —
 * the password manager (Android) or iCloud Keychain (iOS) where there is one,
 * else copying the key; the other ways in a sheet. Backed up, one line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Backup(me: NostrIdentity, busy: Boolean, vm: NostrIdentityViewModel) {
    val actions = LocalPlatformActions.current
    val vault = actions.keyVault
    val state by vm.vault.collectAsState()
    var others by remember { mutableStateOf(false) }
    var warnCopy by remember { mutableStateOf(false) }
    val iCloud = vault?.kind == space.sovereigncast.hopp.platform.KeyVault.Kind.ICloudKeychain
    if (!me.exported) Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.nostr_backup_card_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(stringResource(Res.string.nostr_backup_card_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        if (vault != null) Button(onClick = { vm.saveToVault(vault) }, enabled = state != NostrIdentityViewModel.Vault.Working, modifier = Modifier.fillMaxWidth()) {
            if (state == NostrIdentityViewModel.Vault.Working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(if (iCloud) Res.string.nostr_vault_save_ios else Res.string.nostr_vault_save_android))
        } else Button(onClick = { warnCopy = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.nostr_copy_nsec)) }
        if (state == NostrIdentityViewModel.Vault.Failed) Text(stringResource(if (iCloud) Res.string.nostr_vault_failed_ios else Res.string.nostr_vault_failed_android),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { others = true }) { Text(stringResource(Res.string.nostr_backup_other), color = MaterialTheme.colorScheme.onErrorContainer) }
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("✓ " + stringResource(Res.string.nostr_backed_up), Modifier.weight(1f), color = space.sovereigncast.hopp.ui.theme.Hopp.colors.ok, fontWeight = FontWeight.Bold)
        TextButton(onClick = { others = true }) { Text(stringResource(Res.string.nostr_backup_other)) }
    }
    if (others) ModalBottomSheet(onDismissRequest = { others = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.nostr_backup), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            BackupWays(busy, vm, onCopy = { warnCopy = true })
        }
    }
    if (warnCopy) CopyWarning(onDismiss = { warnCopy = false }) { warnCopy = false; vm.nsecForCopy()?.let { actions.copySecret(it, "nsec") } }
}

/** Every way to back the key up, each with a line on where it works. */
@Composable
private fun BackupWays(busy: Boolean, vm: NostrIdentityViewModel, onCopy: () -> Unit) {
    val actions = LocalPlatformActions.current
    val vault = actions.keyVault
    val exported by vm.exported.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (vault != null) {
        val iCloud = vault.kind == space.sovereigncast.hopp.platform.KeyVault.Kind.ICloudKeychain
        Button(onClick = { vm.saveToVault(vault) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (iCloud) Res.string.nostr_vault_save_ios else Res.string.nostr_vault_save_android))
        }
        Text(stringResource(if (iCloud) Res.string.nostr_vault_note_ios else Res.string.nostr_vault_note_android), color = muted, style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.nostr_copy_nsec)) }
    Text(stringResource(Res.string.nostr_copy_nsec_note), color = muted, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(Res.string.nostr_ncryptsec_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(Res.string.nostr_ncryptsec_note), color = muted, style = MaterialTheme.typography.bodySmall)
    val done = exported
    if (done == null) {
        var password by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.nostr_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(again, { again = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.nostr_password_again)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            isError = again.isNotEmpty() && again != password,
            supportingText = { if (again.isNotEmpty() && again != password) Text(stringResource(Res.string.nostr_backup_mismatch)) })
        OutlinedButton(onClick = { vm.export(password) }, enabled = !busy && password.length >= 8 && password == again) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.nostr_backup_button))
        }
    } else {
        SelectionContainer { Text(done, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.copyText(done, "ncryptsec") }) { Text(stringResource(Res.string.nostr_copy)) }
            OutlinedButton(onClick = { actions.shareText(done, "Nostr") }) { Text(stringResource(Res.string.nostr_share)) }
        }
    }
}

@Composable
private fun CopyWarning(onDismiss: () -> Unit, onCopy: () -> Unit) {
    val ios = koinInject<space.sovereigncast.hopp.platform.AppInfo>().isIos
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.nostr_copy_nsec_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.nostr_copy_nsec_text))
                Text(stringResource(if (ios) Res.string.nostr_copy_nsec_clip_ios else Res.string.nostr_copy_nsec_clip_android),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onCopy) { Text(stringResource(Res.string.nostr_copy_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The npub of a saved nsec, shortened — the secret itself stays off the button. */
private fun vaultKeyLabel(nsec: String): String = runCatching {
    val (hrp, data) = space.sovereigncast.hopp.nostr.Bech32.decode(nsec)
    require(hrp == "nsec" && data.size == 32)
    val pub = space.sovereigncast.hopp.nostr.npub(space.sovereigncast.hopp.nostr.KeyPair(data).publicKeyHex)
    pub.take(12) + "…" + pub.takeLast(4)
}.getOrDefault("nsec")
