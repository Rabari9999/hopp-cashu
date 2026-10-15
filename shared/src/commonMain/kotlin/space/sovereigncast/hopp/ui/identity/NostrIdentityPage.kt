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
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.nostr_anonymous
import space.sovereigncast.hopp.resources.nostr_anonymous_note
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
    val current: StateFlow<Settings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    /** Relays that took the profile; null = not tried in this visit. */
    private val _published = MutableStateFlow<Int?>(null)
    val published: StateFlow<Int?> = _published.asStateFlow()
    private val _exported = MutableStateFlow<String?>(null)
    val exported: StateFlow<String?> = _exported.asStateFlow()

    /** The name also becomes the boost name when there is none yet (ADR 0021, 4). */
    fun create(name: String) = work {
        if (settings.current().senderName.isBlank() && name.isNotBlank()) settings.update { it.copy(senderName = name.trim()) }
        _published.value = identities.create(name).size
    }

    fun publishAgain() = work { _published.value = identities.publishProfile().size }
    fun export(password: String) = work { _exported.value = identities.export(password) }
    fun setAnonymous(on: Boolean) = viewModelScope.launch { settings.update { it.copy(scAnonymous = on) } }
    fun signOut() { _exported.value = null; _published.value = null; identities.signOut() }

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
        if (me == null) Create(settings?.senderName.orEmpty(), busy, vm::create)
        else Signed(me, settings, busy, vm)
    }
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

    me.name?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
    SelectionContainer { Text(me.npub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.copyText(me.npub, "npub") }) { Text(stringResource(Res.string.nostr_copy)) }
    }
    when (val n = published) {
        null -> Unit
        0 -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.nostr_publish_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::publishAgain, enabled = !busy) { Text(stringResource(Res.string.nostr_publish_again)) }
        }
        else -> Text(stringResource(Res.string.nostr_published, n), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

    // The key lives only on this device and in no backup (ADR 0015): its export comes first.
    Text(stringResource(Res.string.nostr_backup), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    if (!me.exported) Text(stringResource(Res.string.nostr_backup_missing), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(Res.string.nostr_backup_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    val out = exported
    if (out == null) {
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
        SelectionContainer { Text(out, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.copyText(out, "ncryptsec") }) { Text(stringResource(Res.string.nostr_copy)) }
            OutlinedButton(onClick = { actions.shareText(out, "Nostr") }) { Text(stringResource(Res.string.nostr_share)) }
        }
    }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.nostr_anonymous))
            Text(stringResource(Res.string.nostr_anonymous_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = settings?.scAnonymous == true, onCheckedChange = vm::setAnonymous)
    }

    TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(Res.string.nostr_sign_out), color = MaterialTheme.colorScheme.error)
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(Res.string.nostr_sign_out)) },
        text = { Text(stringResource(Res.string.nostr_sign_out_text)) },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text(stringResource(Res.string.nostr_sign_out_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(Res.string.cancel)) } },
    )
}
