package space.sovereigncast.hopp.ui.identity

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.cancel
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
import space.sovereigncast.hopp.resources.nostr_or_new
import space.sovereigncast.hopp.resources.nostr_amber_note
import space.sovereigncast.hopp.resources.nostr_amber
import space.sovereigncast.hopp.resources.nostr_anonymous_note
import space.sovereigncast.hopp.resources.nostr_boosts
import space.sovereigncast.hopp.resources.nostr_boosts_note
import space.sovereigncast.hopp.resources.nostr_backup
import space.sovereigncast.hopp.resources.nostr_backup_button
import space.sovereigncast.hopp.resources.nostr_backup_done
import space.sovereigncast.hopp.resources.nostr_backup_missing
import space.sovereigncast.hopp.resources.nostr_backup_mismatch
import space.sovereigncast.hopp.resources.nostr_backup_note
import space.sovereigncast.hopp.resources.nostr_copy
import space.sovereigncast.hopp.resources.nostr_create
import space.sovereigncast.hopp.resources.nostr_intro
import space.sovereigncast.hopp.resources.nostr_name
import space.sovereigncast.hopp.resources.nostr_password
import space.sovereigncast.hopp.resources.nostr_password_again
import space.sovereigncast.hopp.resources.nostr_permanent
import space.sovereigncast.hopp.resources.nostr_published
import space.sovereigncast.hopp.resources.nostr_publish_again
import space.sovereigncast.hopp.resources.nostr_publish_failed
import space.sovereigncast.hopp.resources.nostr_share
import space.sovereigncast.hopp.resources.nostr_sign_out
import space.sovereigncast.hopp.resources.nostr_sign_out_confirm
import space.sovereigncast.hopp.resources.nostr_sign_out_text
import space.sovereigncast.hopp.ui.LocalPlatformActions

class NostrIdentityViewModel(
    private val identities: IdentityRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val identity: StateFlow<NostrIdentity?> = identities.identity

    /** While the profile is looked up on the relays (device feedback 2026-10-16: nothing said so). */
    private val _loadingProfile = MutableStateFlow(false)
    val loadingProfile: StateFlow<Boolean> = _loadingProfile.asStateFlow()

    init {
        // A profile from a signer app can change elsewhere — and an older Hopp did not find it at all.
        if (identities.identity.value?.holdsKey == false) viewModelScope.launch {
            _loadingProfile.value = true
            try { runCatching { identities.refreshProfile() } } finally { _loadingProfile.value = false }
        }
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
    /** What went wrong with the signer app, for one line under its button. */
    private val _signerError = MutableStateFlow<SignerError?>(null)
    val signerError: StateFlow<SignerError?> = _signerError.asStateFlow()
    enum class SignerError { Rejected, Failed }

    /** Signs in with the signer app; its profile name becomes the boost name when none is set. */
    fun signInWithSigner() = work {
        _signerError.value = null
        _loadingProfile.value = true
        try {
            val me = identities.signInWithSigner()
            val name = me.name
            if (name != null && settings.current().senderName.isBlank()) settings.update { it.copy(senderName = name) }
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
        data class Failed(val reason: String) : Bunker
    }
    private val _bunker = MutableStateFlow<Bunker>(Bunker.Idle)
    val bunker: StateFlow<Bunker> = _bunker.asStateFlow()
    /** The signer wants the listener to confirm on one of its pages first. */
    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _bunker.value = Bunker.Failed(e.message ?: e::class.simpleName.orEmpty())
        } finally {
            _authUrl.value = null
            _loadingProfile.value = false
        }
    }

    fun publishAgain() = work { _published.value = identities.publishProfile().size }
    fun export(password: String) = work { _exported.value = identities.export(password) }
    fun setAnonymous(on: Boolean) = viewModelScope.launch { settings.update { it.copy(scAnonymous = on) } }
    fun setNostrBoosts(on: Boolean) = viewModelScope.launch { settings.update { it.copy(nostrBoosts = on) } }
    fun signOut() {
        _exported.value = null; _published.value = null
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
@Composable
fun NostrIdentityPage(vm: NostrIdentityViewModel = koinViewModel()) {
    val identity by vm.identity.collectAsState()
    val settings by vm.current.collectAsState()
    val busy by vm.busy.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val me = identity
        if (me == null) {
            // A signer app keeps the key out of Hopp: offered first where there is one (ADR 0021, stage 1b).
            if (vm.signerApp) SignerLogin(busy, vm)
            // A remote signer — on iOS the only way to keep the key outside Hopp (ADR 0006).
            BunkerLogin(vm)
            Text(stringResource(Res.string.nostr_or_new), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Create(settings?.senderName.orEmpty(), busy, vm::create)
        } else Signed(me, settings, busy, vm)
    }
}

@Composable
private fun SignerLogin(busy: Boolean, vm: NostrIdentityViewModel) {
    val error by vm.signerError.collectAsState()
    Text(stringResource(Res.string.nostr_amber_note), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = vm::signInWithSigner, enabled = !busy) { Text(stringResource(Res.string.nostr_amber)) }
    error?.let {
        Text(stringResource(if (it == NostrIdentityViewModel.SignerError.Rejected) Res.string.nostr_amber_rejected else Res.string.nostr_amber_failed),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BunkerLogin(vm: NostrIdentityViewModel) {
    val state by vm.bunker.collectAsState()
    val authUrl by vm.authUrl.collectAsState()
    val actions = LocalPlatformActions.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var address by rememberSaveable { mutableStateOf("") }
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
                Text(stringResource(Res.string.nostr_bunker_failed, it.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
private fun Create(boostName: String, busy: Boolean, onCreate: (String) -> Unit) {
    var name by rememberSaveable(boostName) { mutableStateOf(boostName) }
    Text(stringResource(Res.string.nostr_intro), style = MaterialTheme.typography.bodyMedium)
    Permanent()
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.nostr_name)) }, singleLine = true)
    Button(onClick = { onCreate(name) }, enabled = !busy && name.isNotBlank()) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.nostr_create))
    }
}

@Composable
private fun Permanent() = Text(
    stringResource(Res.string.nostr_permanent),
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
    style = MaterialTheme.typography.bodySmall,
)

@Composable
private fun Signed(me: NostrIdentity, settings: Settings?, busy: Boolean, vm: NostrIdentityViewModel) {
    val actions = LocalPlatformActions.current
    val published by vm.published.collectAsState()
    val exported by vm.exported.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        me.picture?.let {
            coil3.compose.AsyncImage(it, null, Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        me.name?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
        val loading by vm.loadingProfile.collectAsState()
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            if (me.name == null) Text(stringResource(Res.string.nostr_profile_loading), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
    SelectionContainer { Text(me.npub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.copyText(me.npub, "npub") }) { Text(stringResource(Res.string.nostr_copy)) }
    }
    if (!me.holdsKey) Text(stringResource(Res.string.nostr_via_amber), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    when (val n = published) {
        null -> Unit
        0 -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.nostr_publish_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::publishAgain, enabled = !busy) { Text(stringResource(Res.string.nostr_publish_again)) }
        }
        else -> Text(stringResource(Res.string.nostr_published, n), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

    // A key Hopp holds lives only on this device and in no backup (ADR 0015): its export comes first.
    if (me.holdsKey) Backup(me, busy, exported, vm)

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.nostr_anonymous))
            Text(stringResource(Res.string.nostr_anonymous_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = settings?.scAnonymous == true, onCheckedChange = vm::setAnonymous)
    }

    // Boosts as public comments too, as Fountain does (device feedback 2026-10-16).
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.nostr_boosts))
            Text(stringResource(Res.string.nostr_boosts_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = settings?.nostrBoosts == true, onCheckedChange = vm::setNostrBoosts)
    }

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

@Composable
private fun Backup(me: NostrIdentity, busy: Boolean, exported: String?, vm: NostrIdentityViewModel) {
    val actions = LocalPlatformActions.current
    Text(stringResource(Res.string.nostr_backup), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (!me.exported) Text(stringResource(Res.string.nostr_backup_missing), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(Res.string.nostr_backup_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    if (exported == null) {
        var password by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.nostr_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(again, { again = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.nostr_password_again)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            isError = again.isNotEmpty() && again != password,
            supportingText = { if (again.isNotEmpty() && again != password) Text(stringResource(Res.string.nostr_backup_mismatch)) })
        Button(onClick = { vm.export(password) }, enabled = !busy && password.length >= 8 && password == again) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(Res.string.nostr_backup_button))
        }
    } else {
        Text(stringResource(Res.string.nostr_backup_done), style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(exported, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.copyText(exported, "ncryptsec") }) { Text(stringResource(Res.string.nostr_copy)) }
            OutlinedButton(onClick = { actions.shareText(exported, "Nostr") }) { Text(stringResource(Res.string.nostr_share)) }
        }
    }
}
