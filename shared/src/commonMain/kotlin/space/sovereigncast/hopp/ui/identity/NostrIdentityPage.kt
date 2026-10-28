package space.sovereigncast.hopp.ui.identity

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
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.identity.NostrIdentity
import space.sovereigncast.hopp.nostr.SignerRejected
import space.sovereigncast.hopp.resources.nostr_extension
import space.sovereigncast.hopp.resources.nostr_extension_failed
import space.sovereigncast.hopp.resources.nostr_extension_rejected
import space.sovereigncast.hopp.resources.nostr_hero_text_web
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
) : ViewModel() {
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

    /** The name also becomes the boost name when there is none yet (ADR 0021, 4). */
    fun create(name: String) = work {
        if (settings.current().senderName.isBlank() && name.isNotBlank()) settings.update { it.copy(senderName = name.trim()) }
        _published.value = identities.create(name).size
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
        // A bunker hears that the session is over — a courtesy, not waited on long.
        viewModelScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(5_000) { runCatching { identities.endRemoteSession() } }
            identities.signOut()
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
fun NostrIdentityPage(vm: NostrIdentityViewModel = koinViewModel()) {
    val identity by vm.identity.collectAsState()
    val unreadable by vm.unreadable.collectAsState()
    val settings by vm.current.collectAsState()
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val me = identity
        // Device feedback 2026-10-16: too much text, too many ways at once. One way in for
        // the newcomer — a name and "Get started" —, everything for an existing account
        // behind one link; once in, the profile, one backup card, two switches.
        if (me == null && unreadable) Unreadable(vm) else if (me == null) Welcome(settings?.senderName.orEmpty(), busy, vm) else Signed(me, settings, busy, vm)
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
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(space.sovereigncast.hopp.ui.components.HoppIcons.Comment, null, Modifier.size(36.dp))
        }
        Text(stringResource(Res.string.nostr_hero_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(stringResource(if (vm.keysInApp) Res.string.nostr_hero_text else Res.string.nostr_hero_text_web), style = MaterialTheme.typography.bodyLarge, color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (!vm.keysInApp) {
        // The browser holds no key (ADR 0022, 2): the ways in with a signer, right here.
        Text(stringResource(Res.string.nostr_web_note), Modifier.fillMaxWidth(), color = muted, style = MaterialTheme.typography.bodyMedium)
        SignIn(busy, vm)
        return
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(Res.string.nostr_name)) }, singleLine = true)
    Button(onClick = { vm.create(name) }, enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
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
    var bunker by rememberSaveable { mutableStateOf(false) }
    val locked = text.trim().removePrefix("nostr:").startsWith("ncryptsec1")
    if (vm.signerApp) {
        val ext = vm.signerIsExtension
        Button(onClick = vm::signInWithSigner, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (ext) Res.string.nostr_extension else Res.string.nostr_amber)) }
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
private fun Signed(me: NostrIdentity, settings: Settings?, busy: Boolean, vm: NostrIdentityViewModel) {
    val actions = LocalPlatformActions.current
    val published by vm.published.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmSignOut by remember { mutableStateOf(false) }

    // Who: picture, name, the npub short with a copy button.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            val picture = me.picture
            if (picture != null) coil3.compose.AsyncImage(picture, null, Modifier.size(56.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Text((me.name ?: "N").take(1).uppercase(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            val loading by vm.loadingProfile.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(me.name ?: stringResource(Res.string.nostr_profile_loading).takeIf { loading } ?: "Nostr", style = MaterialTheme.typography.titleLarge)
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(me.npub.take(12) + "…" + me.npub.takeLast(6), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        TextButton(onClick = { actions.copyText(me.npub, "npub") }) { Text(stringResource(Res.string.nostr_copy)) }
    }
    if (!me.holdsKey) Text(stringResource(if (me.signerPackage == space.sovereigncast.hopp.nostr.ExternalSigner.NIP07_PACKAGE) Res.string.nostr_via_extension else Res.string.nostr_via_amber), color = muted, style = MaterialTheme.typography.bodySmall)
    // The profile's own numbers: what was heard and given (maintainer 2026-10-26).
    val openStats = LocalOpenStats.current
    OutlinedButton(onClick = openStats) { Text(stringResource(Res.string.stats_nostr_link)) }
    // Only when something is wrong: the profile reached no relay.
    if (published == 0) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.nostr_publish_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::publishAgain, enabled = !busy) { Text(stringResource(Res.string.nostr_publish_again)) }
    }

    val offer by vm.restore.collectAsState()
    offer?.let { RestoreCard(it, vm) }

    // A key Hopp holds lives only on this device and in no backup (ADR 0015).
    if (me.holdsKey) Backup(me, busy, vm)

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    val restoring by vm.restoring.collectAsState()
    SwitchRow(stringResource(Res.string.sync_title), stringResource(Res.string.sync_note), settings?.nostrSync == true || restoring) { if (!restoring) vm.setSync(it) }
    if (settings?.nostrSync == true) SyncStatusLine(vm)
    // Only where the key is elsewhere: a lost phone that held the key itself has it, sync key or not.
    if (settings?.nostrSync == true && !me.holdsKey) RenewSyncKey(vm)
    SwitchRow(stringResource(Res.string.nostr_boosts), stringResource(Res.string.nostr_boosts_note), settings?.nostrBoosts == true, vm::setNostrBoosts)
    SwitchRow(stringResource(Res.string.nostr_anonymous), stringResource(Res.string.nostr_anonymous_note), settings?.scAnonymous == true, vm::setAnonymous)
    var relaysOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.loadRelays(); relaysOpen = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.nostr_relays))
            Text(stringResource(Res.string.nostr_relays_note), color = muted, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", color = muted, style = MaterialTheme.typography.titleLarge)
    }
    if (relaysOpen) RelaysSheet(vm) { relaysOpen = false }

    TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(Res.string.nostr_sign_out), color = MaterialTheme.colorScheme.error)
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(Res.string.nostr_sign_out)) },
        text = { Text(stringResource(if (me.holdsKey) Res.string.nostr_sign_out_text else Res.string.nostr_sign_out_text_amber)) },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text(stringResource(Res.string.nostr_sign_out_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(Res.string.cancel)) } },
    )
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text.orEmpty(), Modifier.weight(1f), color = if (problem != null && !status.running) MaterialTheme.colorScheme.error else muted, style = MaterialTheme.typography.bodySmall)
        if (!status.running) TextButton(onClick = vm::syncNow) { Text(stringResource(Res.string.sync_now)) }
    }
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

/** A new sync key, for a lost device or a browser no longer used (ADR 0022, 3) — asked first. */
@Composable
private fun RenewSyncKey(vm: NostrIdentityViewModel) {
    val renewing by vm.renewing.collectAsState()
    val renewed by vm.renewed.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (renewed) {
                space.sovereigncast.hopp.sync.Renewal.Done -> stringResource(Res.string.sync_renewed)
                space.sovereigncast.hopp.sync.Renewal.Unfinished -> stringResource(Res.string.sync_renew_unfinished)
                space.sovereigncast.hopp.sync.Renewal.NotRenewed -> stringResource(Res.string.sync_renew_failed)
                null -> ""
            },
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = if (renewed == space.sovereigncast.hopp.sync.Renewal.NotRenewed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (renewing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else TextButton(onClick = { confirm = true }) { Text(stringResource(Res.string.sync_renew)) }
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

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row switches, read out as one switch with its title.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
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
