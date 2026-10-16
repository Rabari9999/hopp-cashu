package space.sovereigncast.hopp.ui.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.comments.Comment
import space.sovereigncast.hopp.comments.CommentAuthor
import space.sovereigncast.hopp.comments.CommentThread
import space.sovereigncast.hopp.comments.CommentsRepository
import space.sovereigncast.hopp.comments.CommentWriter
import space.sovereigncast.hopp.comments.NotPublished
import space.sovereigncast.hopp.comments.ReportType
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.identity.NostrIdentity
import space.sovereigncast.hopp.nostr.SignerRejected
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import space.sovereigncast.hopp.nostr.npub
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.comments_failed
import space.sovereigncast.hopp.resources.comments_from_relays
import space.sovereigncast.hopp.resources.comments_intro
import space.sovereigncast.hopp.resources.comments_load
import space.sovereigncast.hopp.resources.comments_loading
import space.sovereigncast.hopp.resources.comments_none
import space.sovereigncast.hopp.resources.comments_reload
import space.sovereigncast.hopp.resources.comments_reply_to
import space.sovereigncast.hopp.ui.components.commentText
import space.sovereigncast.hopp.ui.components.formatDate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.comments.commentBody
import space.sovereigncast.hopp.resources.comments_boost
import space.sovereigncast.hopp.resources.comments_vpn_on
import space.sovereigncast.hopp.resources.privacy_vpn_off
import space.sovereigncast.hopp.platform.NetworkPrivacy
import space.sovereigncast.hopp.platform.NetworkPrivacySource
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.library.Check
import space.sovereigncast.hopp.ui.library.CheckLine
import org.koin.compose.koinInject
import space.sovereigncast.hopp.ui.theme.Hopp

/** One episode's comments: nothing asked until [load] — relays see the IP address and the episode (ADR 0021, 2). */
class CommentsViewModel(
    private val episodeId: Long,
    private val repo: CommentsRepository,
    private val writer: CommentWriter,
    private val identities: IdentityRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    sealed interface State {
        /** Looking for comments kept from before — nobody is asked. */
        data object Opening : State
        data object Idle : State
        data object Loading : State
        /** [refreshing]: shown from before while the relays are asked what is new. */
        data class Loaded(val thread: CommentThread, val refreshing: Boolean = false) : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Opening)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _authors = MutableStateFlow<Map<String, CommentAuthor>>(emptyMap())
    /** Names and pictures — they follow the comments, which show at once with short npubs. */
    val authors: StateFlow<Map<String, CommentAuthor>> = _authors.asStateFlow()
    private val _boosts = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Sats behind comments (Fountain's boosts), by comment id — they follow too. */
    val boosts: StateFlow<Map<String, Long>> = _boosts.asStateFlow()
    /** Asked the relays since this page opened. */
    var asked = false
        private set

    // Loaded before: shown at once from the database, names and sats too.
    init {
        viewModelScope.launch {
            val kept = runCatching { repo.cached(episodeId) }.getOrNull()
            if (_state.value != State.Opening) return@launch
            _state.value = kept?.let { State.Loaded(it) } ?: State.Idle
            kept?.let(::details)
        }
    }

    fun load() {
        val shown = _state.value
        if (shown == State.Loading || (shown as? State.Loaded)?.refreshing == true) return
        asked = true
        _state.value = (shown as? State.Loaded)?.copy(refreshing = true) ?: State.Loading
        viewModelScope.launch {
            val thread = runCatching { repo.anchor(episodeId)?.let { repo.reader.load(it) } }.getOrNull()
            if (thread == null) { _state.value = (shown as? State.Loaded)?.copy(refreshing = false) ?: State.Failed; return@launch }
            _state.value = State.Loaded(thread)
            details(thread)
        }
    }

    /** The listener's identity — without one, writing offers to set it up. */
    val identity: StateFlow<NostrIdentity?> = identities.identity
    val settings: StateFlow<Settings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The comment being written — kept while the listener sets up an identity. */
    val draft = MutableStateFlow("")
    val replyTo = MutableStateFlow<Comment?>(null)
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()
    /** Why the last action failed, for a line under the field. */
    enum class Failure { NotPublished, Rejected, Other }
    val error = MutableStateFlow<Failure?>(null)
    /** A public action waits for "public, for good" to be acknowledged, once (ADR 0021, 8). */
    val pending = MutableStateFlow<(() -> Unit)?>(null)
    /** The backup reminder is due: first comment, key from Hopp, no backup (ADR 0021, 9). */
    val remindBackup = MutableStateFlow(false)

    private fun thread(): CommentThread? = (_state.value as? State.Loaded)?.thread

    /** [action] now, or after the one-time note that what is published stays. */
    private fun publicly(action: () -> Unit) {
        if (settings.value?.nostrPublicNoted == true) action() else pending.value = action
    }

    fun acknowledgePublic() {
        val action = pending.value ?: return
        pending.value = null
        viewModelScope.launch { settingsRepo.update { it.copy(nostrPublicNoted = true) }; action() }
    }

    fun send() = publicly {
        val text = draft.value.trim()
        val thread = thread() ?: return@publicly
        if (text.isEmpty() || _sending.value) return@publicly
        _sending.value = true
        error.value = null
        viewModelScope.launch {
            runCatching { writer.comment(repo.anchor(episodeId) ?: error("no episode"), text, thread.relays, replyTo.value) }
                .onSuccess {
                    draft.value = ""; replyTo.value = null
                    refreshFromStore()
                    val me = identity.value
                    if (me != null && me.holdsKey && !me.exported && settings.value?.nostrBackupReminded != true) {
                        settingsRepo.update { it.copy(nostrBackupReminded = true) }
                        remindBackup.value = true
                    }
                }
                .onFailure { error.value = reason(it) }
            _sending.value = false
        }
    }

    /** Likes shown before the signer and the relays are done (device feedback 2026-10-16: a noticeable wait). */
    val liking = MutableStateFlow<Set<String>>(emptySet())

    fun like(c: Comment) = publicly {
        val thread = thread() ?: return@publicly
        val me = identity.value?.pubkey ?: return@publicly
        if (me in c.likes || c.id in liking.value) return@publicly
        liking.value += c.id
        viewModelScope.launch {
            runCatching { writer.like(repo.anchor(episodeId) ?: error("no episode"), c, thread.relays) }
                .onSuccess { refreshFromStore() }.onFailure { error.value = reason(it) }
            liking.value -= c.id
        }
    }

    /** Hidden here at once; with an identity also on the listener's public mute list. */
    fun mute(pubkey: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(mutedLocally = it.mutedLocally + pubkey) }
            if (identity.value != null) runCatching { writer.mute(pubkey) }.onFailure { error.value = reason(it) }
        }
    }

    /** Hidden here at once; with an identity also reported (NIP-56). */
    fun report(c: Comment, type: ReportType) {
        hide(c)
        if (identity.value != null) publicly {
            viewModelScope.launch { runCatching { writer.report(c, type, thread()?.relays.orEmpty()) }.onFailure { error.value = reason(it) } }
        }
    }

    fun hide(c: Comment) { viewModelScope.launch { settingsRepo.update { it.copy(hiddenComments = it.hiddenComments + c.id) } } }

    private suspend fun refreshFromStore() {
        val kept = runCatching { repo.cached(episodeId) }.getOrNull() ?: return
        _state.value = State.Loaded(kept, refreshing = (_state.value as? State.Loaded)?.refreshing == true)
        details(kept)
    }

    private fun reason(e: Throwable): Failure = when (e) {
        is NotPublished -> Failure.NotPublished
        is SignerRejected -> Failure.Rejected
        else -> Failure.Other
    }

    private fun details(thread: CommentThread) {
        viewModelScope.launch { runCatching { repo.reader.boosts(thread) }.getOrNull()?.let { _boosts.value = it } }
        viewModelScope.launch {
            val missing = thread.people - _authors.value.keys
            if (missing.isNotEmpty()) runCatching { repo.reader.authors(missing, thread.relays) }.getOrNull()?.let { _authors.value = _authors.value + it }
        }
    }
}

/**
 * The comments as a column — the caller scrolls it. The relays are asked only
 * on the listener's tap, in the player as on the episode page (device feedback
 * 2026-10-16) — or right away behind a VPN, which keeps the address from them.
 * Comments kept from before show either way.
 */
@Composable
fun CommentsPane(vm: CommentsViewModel, onTimestamp: (Long) -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val authors by vm.authors.collectAsState()
    val boosts by vm.boosts.collectAsState()
    val colors = MaterialTheme.colorScheme
    val net: NetworkPrivacySource = koinInject()
    val privacy by remember(net) { net.observe() }.collectAsState(NetworkPrivacy())
    val actions = LocalPlatformActions.current
    val opening = state == CommentsViewModel.State.Opening
    androidx.compose.runtime.LaunchedEffect(privacy.vpn, opening) { if (privacy.vpn == true && !opening && !vm.asked) vm.load() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // As on the privacy page: with a VPN the relays do not see the address.
        if (privacy.vpn == true && !opening) CheckLine(Check.Done, stringResource(Res.string.comments_vpn_on))
        when (val s = state) {
            CommentsViewModel.State.Opening -> Unit
            CommentsViewModel.State.Idle -> {
                Text(stringResource(Res.string.comments_intro), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (privacy.vpn == false) CheckLine(Check.Open, stringResource(Res.string.privacy_vpn_off)) { actions.openVpnSettings() }
                FilledTonalButton(onClick = vm::load) { Text(stringResource(Res.string.comments_load)) }
            }
            CommentsViewModel.State.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.comments_loading), color = colors.onSurfaceVariant)
            }
            CommentsViewModel.State.Failed -> {
                Text(stringResource(Res.string.comments_failed), color = colors.onSurfaceVariant)
                TextButton(onClick = vm::load) { Text(stringResource(Res.string.comments_reload)) }
            }
            is CommentsViewModel.State.Loaded -> {
                val settings by vm.settings.collectAsState()
                val me by vm.identity.collectAsState()
                // The listener's own name at once — the relays may not know the profile yet.
                val authors = me?.let { i -> authors + (i.pubkey to CommentAuthor(i.name ?: authors[i.pubkey]?.name, i.picture ?: authors[i.pubkey]?.picture)) } ?: authors
                val hidden = settings?.hiddenComments.orEmpty()
                val mutedHere = settings?.mutedLocally.orEmpty()
                val to by vm.replyTo.collectAsState()
                val liking by vm.liking.collectAsState()
                // A new comment at the top; an answer right under what it answers
                // (device feedback 2026-10-16: far down, the field at the top went unseen).
                if (to == null) Composer(vm, me != null, authors)
                // Nothing to read and no sats behind it (a Fountain boost before its
                // amount is in, or a bare link): not shown. Nor what the listener
                // hid, reported or muted here.
                fun says(c: Comment) = c.id !in hidden && c.pubkey !in mutedHere && (boosts[c.id] != null || commentBody(c.content).isNotEmpty())
                val shown = s.thread.comments.filter { it.pubkey !in mutedHere && it.id !in hidden }.mapNotNull { c ->
                    val replies = c.replies.filter(::says)
                    if (says(c) || replies.isNotEmpty()) c to replies else null
                }
                if (shown.isEmpty()) Text(stringResource(Res.string.comments_none), color = colors.onSurfaceVariant)
                shown.forEach { (c, replies) ->
                    CommentRow(c, authors, boosts[c.id], me?.pubkey, c.id in liking, vm, onTimestamp)
                    if (to?.id == c.id) Composer(vm, me != null, authors, inline = true, modifier = Modifier.padding(start = 40.dp))
                    replies.forEach { r ->
                        CommentRow(r, authors, boosts[r.id], me?.pubkey, r.id in liking, vm, onTimestamp, Modifier.padding(start = 40.dp))
                        if (to?.id == r.id) Composer(vm, me != null, authors, inline = true, modifier = Modifier.padding(start = 40.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.comments_from_relays, s.thread.relays.size), Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    if (s.refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = vm::load) { Text(stringResource(Res.string.comments_reload)) }
                }
            }
        }
    }
    PublicNote(vm)
    BackupReminder(vm)
}

/** Opens the Nostr identity page — from the player it closes first. */
val LocalOpenIdentity = staticCompositionLocalOf<() -> Unit> { {} }

/** Writing: the field, or without an identity the way to one (the draft waits). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Composer(vm: CommentsViewModel, hasIdentity: Boolean, authors: Map<String, CommentAuthor>, inline: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (!hasIdentity) {
        val open = LocalOpenIdentity.current
        OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.comments_need_identity)) }
        return
    }
    val draft by vm.draft.collectAsState()
    val to by vm.replyTo.collectAsState()
    val sending by vm.sending.collectAsState()
    val error by vm.error.collectAsState()
    // An answer's field takes the focus and scrolls into view at once.
    val focus = remember { FocusRequester() }
    val inView = remember { BringIntoViewRequester() }
    if (inline) LaunchedEffect(to?.id) { runCatching { focus.requestFocus() }; inView.bringIntoView() }
    Column(modifier.bringIntoViewRequester(inView), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        to?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.comments_replying, nameOf(c.pubkey, authors)), Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { vm.replyTo.value = null }) { Text(stringResource(Res.string.comments_cancel)) }
            }
        }
        OutlinedTextField(
            draft, { vm.draft.value = it }, Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(stringResource(if (to != null) Res.string.comments_reply_hint else Res.string.comments_write_hint)) }, maxLines = 6,
            trailingIcon = {
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = vm::send, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, stringResource(Res.string.comments_send))
                }
            },
        )
        error?.let { f ->
            val why = stringResource(when (f) {
                CommentsViewModel.Failure.NotPublished -> Res.string.comments_not_published
                CommentsViewModel.Failure.Rejected -> Res.string.comments_signer_rejected
                CommentsViewModel.Failure.Other -> Res.string.comments_failed_other
            })
            Text(stringResource(Res.string.comments_failed_send, why), color = colors.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Once, before the first comment, like or report: what is published stays (ADR 0021, 8). */
@Composable
private fun PublicNote(vm: CommentsViewModel) {
    val pending by vm.pending.collectAsState()
    if (pending == null) return
    AlertDialog(
        onDismissRequest = { vm.pending.value = null },
        title = { Text(stringResource(Res.string.comments_public_title)) },
        text = { Text(stringResource(Res.string.comments_public_text)) },
        confirmButton = { TextButton(onClick = vm::acknowledgePublic) { Text(stringResource(Res.string.comments_public_ok)) } },
        dismissButton = { TextButton(onClick = { vm.pending.value = null }) { Text(stringResource(Res.string.comments_cancel)) } },
    )
}

/** After the first comment with a key from Hopp that has no backup (ADR 0021, 9). */
@Composable
private fun BackupReminder(vm: CommentsViewModel) {
    val due by vm.remindBackup.collectAsState()
    if (!due) return
    val open = LocalOpenIdentity.current
    AlertDialog(
        onDismissRequest = { vm.remindBackup.value = false },
        title = { Text(stringResource(Res.string.comments_backup_title)) },
        text = { Text(stringResource(Res.string.comments_backup_text)) },
        confirmButton = { TextButton(onClick = { vm.remindBackup.value = false; open() }) { Text(stringResource(Res.string.comments_backup_now)) } },
        dismissButton = { TextButton(onClick = { vm.remindBackup.value = false }) { Text(stringResource(Res.string.comments_later)) } },
    )
}

/** A name to show: the profile's, else a short npub. */
private fun nameOf(key: String, authors: Map<String, CommentAuthor>): String =
    authors[key]?.name ?: npub(key).let { it.take(10) + "…" + it.takeLast(4) }

@Composable
private fun CommentRow(
    c: Comment, authors: Map<String, CommentAuthor>, sats: Long?, me: String?, liking: Boolean, vm: CommentsViewModel,
    onTimestamp: (Long) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val author = authors[c.pubkey]
    val name = nameOf(c.pubkey, authors)
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    // Liking or answering without an identity: the way to one, as the composer offers.
    val openIdentity = LocalOpenIdentity.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            if (author?.picture != null) AsyncImage(author.picture, null, Modifier.size(32.dp), contentScale = ContentScale.Crop)
            else Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sats != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.comments_boost, formatSats(sats)), color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                Text(formatDate(c.createdAt), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            c.replyTo?.let { to ->
                Text(stringResource(Res.string.comments_reply_to, nameOf(to, authors)), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            val link = SpanStyle(color = colors.onSurface, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)
            val mark = SpanStyle(color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
            val mention = SpanStyle(fontWeight = FontWeight.SemiBold)
            val text = remember(c.content, authors, link, mark) { commentText(c.content.trim(), { nameOf(it, authors) }, mention, link, mark, onTimestamp) }
            if (text.isNotEmpty()) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            // Like, answer, more — small, under the text.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val liked = me != null && (me in c.likes || liking)
                val count = c.likes.size + if (liking && me !in c.likes) 1 else 0
                val likeLabel = stringResource(Res.string.comments_like)
                Text(
                    (if (liked) "♥" else "♡") + if (count > 0) " $count" else "",
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !liked) { if (me == null) openIdentity() else vm.like(c) }
                        .padding(vertical = 6.dp, horizontal = 2.dp).semantics { contentDescription = likeLabel },
                    color = if (liked) Hopp.colors.valueText else colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(Res.string.comments_reply),
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable { if (me == null) openIdentity() else vm.replyTo.value = c }.padding(vertical = 6.dp, horizontal = 2.dp),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                )
                Box {
                    val more = stringResource(Res.string.comments_more)
                    Text("⋯", Modifier.clip(RoundedCornerShape(6.dp)).clickable { menu = true }.padding(vertical = 6.dp, horizontal = 6.dp)
                        .semantics { contentDescription = more }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (c.pubkey != me) DropdownMenuItem(text = { Text(stringResource(Res.string.comments_mute, name)) }, onClick = { menu = false; vm.mute(c.pubkey) })
                        if (c.pubkey != me) DropdownMenuItem(text = { Text(stringResource(Res.string.comments_report)) }, onClick = { menu = false; reporting = true })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.comments_hide)) }, onClick = { menu = false; vm.hide(c) })
                    }
                }
            }
        }
    }
    if (reporting) ReportDialog(hasIdentity = me != null, onDismiss = { reporting = false }) { type -> reporting = false; vm.report(c, type) }
}

/** NIP-56's reasons Hopp offers; without an identity the comment is only hidden here. */
@Composable
private fun ReportDialog(hasIdentity: Boolean, onDismiss: () -> Unit, onReport: (ReportType) -> Unit) {
    var type by remember { mutableStateOf(ReportType.Spam) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.comments_report_title)) },
        text = {
            Column {
                ReportType.entries.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { type = t }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Text(stringResource(when (t) {
                            ReportType.Spam -> Res.string.comments_report_spam
                            ReportType.Profanity -> Res.string.comments_report_profanity
                            ReportType.Illegal -> Res.string.comments_report_illegal
                            ReportType.Other -> Res.string.comments_report_other
                        }))
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (hasIdentity) Res.string.comments_report_note else Res.string.comments_report_local),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onReport(type) }) { Text(stringResource(Res.string.comments_report)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.comments_cancel)) } },
    )
}
