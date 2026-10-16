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
class CommentsViewModel(private val episodeId: Long, private val repo: CommentsRepository) : ViewModel() {
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
                // Nothing to read and no sats behind it (a Fountain boost before its
                // amount is in, or a bare link): not shown.
                fun says(c: Comment) = boosts[c.id] != null || commentBody(c.content).isNotEmpty()
                val shown = s.thread.comments.mapNotNull { c ->
                    val replies = c.replies.filter(::says)
                    if (says(c) || replies.isNotEmpty()) c to replies else null
                }
                if (shown.isEmpty()) Text(stringResource(Res.string.comments_none), color = colors.onSurfaceVariant)
                shown.forEach { (c, replies) ->
                    CommentRow(c, authors, boosts[c.id], onTimestamp)
                    replies.forEach { r -> CommentRow(r, authors, boosts[r.id], onTimestamp, Modifier.padding(start = 40.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.comments_from_relays, s.thread.relays.size), Modifier.weight(1f), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    if (s.refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = vm::load) { Text(stringResource(Res.string.comments_reload)) }
                }
            }
        }
    }
}

/** A name to show: the profile's, else a short npub. */
private fun nameOf(key: String, authors: Map<String, CommentAuthor>): String =
    authors[key]?.name ?: npub(key).let { it.take(10) + "…" + it.takeLast(4) }

@Composable
private fun CommentRow(c: Comment, authors: Map<String, CommentAuthor>, sats: Long?, onTimestamp: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val author = authors[c.pubkey]
    val name = nameOf(c.pubkey, authors)
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
        }
    }
}
