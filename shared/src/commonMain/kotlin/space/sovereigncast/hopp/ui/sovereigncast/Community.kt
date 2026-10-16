package space.sovereigncast.hopp.ui.sovereigncast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import space.sovereigncast.hopp.resources.sc_all
import org.koin.core.parameter.parametersOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import space.sovereigncast.hopp.resources.sc_more
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.boost_custom
import space.sovereigncast.hopp.resources.boost_message
import space.sovereigncast.hopp.resources.boost_name
import space.sovereigncast.hopp.resources.boost_close
import space.sovereigncast.hopp.resources.np_boost
import space.sovereigncast.hopp.resources.sc_b2u_label
import space.sovereigncast.hopp.resources.sc_badge
import space.sovereigncast.hopp.resources.sc_info_b2u
import space.sovereigncast.hopp.resources.sc_info_boosts
import space.sovereigncast.hopp.resources.sc_info_clips
import space.sovereigncast.hopp.resources.sc_info_intro
import space.sovereigncast.hopp.resources.sc_info_more
import space.sovereigncast.hopp.resources.sc_info_rb
import space.sovereigncast.hopp.resources.sc_info_suggestions
import space.sovereigncast.hopp.resources.sc_info_title
import space.sovereigncast.hopp.resources.sc_info_trust
import space.sovereigncast.hopp.resources.sc_info_url
import space.sovereigncast.hopp.resources.sc_confirming
import space.sovereigncast.hopp.resources.sc_done
import space.sovereigncast.hopp.resources.sc_external
import space.sovereigncast.hopp.resources.sc_failed
import space.sovereigncast.hopp.resources.sc_failed_expired
import space.sovereigncast.hopp.resources.sc_failed_mismatch
import space.sovereigncast.hopp.resources.sc_failed_no_round
import space.sovereigncast.hopp.resources.sc_minutes
import space.sovereigncast.hopp.resources.sc_open_wallet_app
import space.sovereigncast.hopp.resources.sc_paying
import space.sovereigncast.hopp.resources.sc_pay_title_b2u
import space.sovereigncast.hopp.resources.sc_pay_title_rb
import space.sovereigncast.hopp.resources.sc_per_minute
import space.sovereigncast.hopp.resources.sc_progress
import space.sovereigncast.hopp.resources.sc_publish_at
import space.sovereigncast.hopp.resources.sc_rb_label
import space.sovereigncast.hopp.resources.sc_requesting
import space.sovereigncast.hopp.resources.sc_send
import space.sovereigncast.hopp.resources.sc_send_external
import space.sovereigncast.hopp.resources.sc_unlock
import space.sovereigncast.hopp.resources.sc_unlocked
import space.sovereigncast.hopp.resources.sc_votes
import space.sovereigncast.hopp.resources.sc_suggestion_boost_note
import space.sovereigncast.hopp.resources.sc_pay_title_suggestion
import space.sovereigncast.hopp.resources.sc_min_sats
import space.sovereigncast.hopp.resources.sc_suggest_done
import space.sovereigncast.hopp.resources.sc_suggest_note
import space.sovereigncast.hopp.resources.sc_suggest_links
import space.sovereigncast.hopp.resources.sc_suggest_description
import space.sovereigncast.hopp.resources.sc_suggest_title
import space.sovereigncast.hopp.resources.sc_suggest
import space.sovereigncast.hopp.resources.sc_vote
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import space.sovereigncast.hopp.sovereigncast.Boost2UnlockEpisode
import space.sovereigncast.hopp.sovereigncast.Booster
import space.sovereigncast.hopp.sovereigncast.Chain
import space.sovereigncast.hopp.sovereigncast.ClipState
import space.sovereigncast.hopp.sovereigncast.CommunityBoost
import space.sovereigncast.hopp.sovereigncast.UpcomingEpisode
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatIsoShort
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.ui.components.isoToEpochSeconds
import space.sovereigncast.hopp.ui.library.Cover
import space.sovereigncast.hopp.resources.sc_anonymous
import space.sovereigncast.hopp.resources.sc_by
import space.sovereigncast.hopp.resources.sc_chain
import space.sovereigncast.hopp.resources.sc_clip_at_block
import space.sovereigncast.hopp.resources.sc_clip_at_sats
import space.sovereigncast.hopp.resources.sc_clip_play
import space.sovereigncast.hopp.resources.sc_details
import space.sovereigncast.hopp.resources.sc_legendary
import space.sovereigncast.hopp.resources.sc_new
import space.sovereigncast.hopp.resources.sc_open_community
import space.sovereigncast.hopp.resources.sc_open_episode
import space.sovereigncast.hopp.resources.sc_publish_long
import space.sovereigncast.hopp.sovereigncast.ReleaseBoostingRound
import space.sovereigncast.hopp.sovereigncast.ScTarget
import org.koin.compose.koinInject
import space.sovereigncast.hopp.sovereigncast.ScShow
import space.sovereigncast.hopp.player.RemoteClip
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.sovereigncast.Suggestion
import space.sovereigncast.hopp.sovereigncast.TopEpisode
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.theme.Hopp

/** "✓ Sovereigncast" — the show runs on an instance (ADR 0002). A tap says
 *  what that brings (device feedback 2026-10-10), wherever the badge is. */
@Composable
fun ScBadge() {
    var open by remember { mutableStateOf(false) }
    Text(
        "✓ " + stringResource(Res.string.sc_badge),
        Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = stringResource(Res.string.sc_info_title)) { open = true }
            .border(1.dp, Hopp.colors.value, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold,
    )
    if (open) ScInfoSheet { open = false }
}

/** What Sovereigncast adds to a show in Hopp, briefly, and where to read more. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScInfoSheet(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    val website = stringResource(Res.string.sc_info_url)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.sc_info_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.sc_info_intro), style = MaterialTheme.typography.bodyMedium)
            listOf(Res.string.sc_info_rb, Res.string.sc_info_b2u, Res.string.sc_info_suggestions, Res.string.sc_info_boosts, Res.string.sc_info_clips).forEach { r ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(HoppIcons.Hop, null, tint = Hopp.colors.valueText, modifier = Modifier.padding(top = 3.dp).size(14.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(r), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(Res.string.sc_info_trust), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            TextButton(onClick = { runCatching { uri.openUri(website) } }) { Text(stringResource(Res.string.sc_info_more)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The small orange action pill of a card. */
@Composable
private fun ActionPill(label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Hopp.colors.value).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HoppIcons.Hop, null, tint = Hopp.colors.onValue, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, color = Hopp.colors.onValue, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Progress(raised: Long, goal: Long, height: Int) = LinearProgressIndicator(
    progress = { if (goal > 0) (raised.toFloat() / goal).coerceIn(0f, 1f) else 0f },
    modifier = Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape((height / 2).dp)),
    color = Hopp.colors.value, trackColor = Hopp.colors.progressTrack, drawStopIndicator = {},
)

/** One compact card per running action — the details are a tap away
 *  (device feedback 2026-10-09: the show page was too busy). */
@Composable
private fun ActionCard(label: String, title: String, raised: Long, goal: Long, line: String, action: String?, onAction: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(colors.surface).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = stringResource(Res.string.sc_details), onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Progress(raised, goal, 4)
            Text(line, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) {
            Spacer(Modifier.size(12.dp))
            ActionPill(action, onAction)
        }
    }
}

/** The running Release Boosting round (ADR 0019's Sovereigncast card). */
@Composable
fun ReleaseBoostingCard(round: ReleaseBoostingRound, onOpen: () -> Unit, onBoost: () -> Unit) = ActionCard(
    label = stringResource(Res.string.sc_rb_label),
    title = round.episode.title,
    raised = round.raisedSats, goal = round.goalSats,
    line = stringResource(Res.string.sc_progress, formatSats(round.raisedSats), formatSats(round.goalSats)) + " · " +
        stringResource(Res.string.sc_publish_at, formatIsoShort(round.publishAt)),
    action = stringResource(Res.string.np_boost), onAction = onBoost, onOpen = onOpen,
)

/** One episode waiting to be unlocked by boosts. */
@Composable
fun UnlockCard(item: Boost2UnlockEpisode, onOpen: () -> Unit, onUnlock: () -> Unit) = ActionCard(
    label = stringResource(Res.string.sc_b2u_label),
    title = item.episode.title,
    raised = item.raisedSats, goal = item.goalSats,
    line = if (item.unlocked) stringResource(Res.string.sc_unlocked)
        else stringResource(Res.string.sc_progress, formatSats(item.raisedSats), formatSats(item.goalSats)),
    action = if (item.unlocked) null else stringResource(Res.string.sc_unlock), onAction = onUnlock, onOpen = onOpen,
)

/**
 * What the community page shows of the episode a round is for (device
 * feedback 2026-10-09): cover, title, when it comes out, the description, the
 * block chain, the clip waiting at its mark, the sats so far — and the action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(
    label: String,
    episode: UpcomingEpisode,
    raised: Long,
    goal: Long,
    action: String?,
    communityUrl: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    facts: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (episode.imageUrl != null) {
                    Cover(episode.imageUrl, 72.dp, corner = 14.dp)
                    Spacer(Modifier.size(14.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
                    Text(episode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            facts()
            if (episode.description.isNotBlank()) SelectionContainer {
                Text(episode.description, style = MaterialTheme.typography.bodyMedium)
            }
            Progress(raised, goal, 6)
            Text(stringResource(Res.string.sc_progress, formatSats(raised), formatSats(goal)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (action != null) Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(Hopp.colors.value).clickable(onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HoppIcons.Hop, null, tint = Hopp.colors.onValue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(action, fontWeight = FontWeight.Bold, color = Hopp.colors.onValue)
                }
            }
            if (communityUrl != null) TextButton(onClick = { runCatching { uri.openUri(communityUrl) } }) { Text(stringResource(Res.string.sc_open_community)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The clip at its mark: locked, it only says where; unlocked, it plays — in
 *  Hopp's own player (phase 2; it opened in the browser before). */
@Composable
private fun ClipLine(clip: ClipState, lockedText: String, show: ScShow?) {
    val player: PlayerController = koinInject()
    val url = clip.url
    if (clip.unlocked && url != null) Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable {
            player.playRemoteClip(RemoteClip(url, clip.title.ifBlank { "Clip" }, show?.title.orEmpty(), show?.imageUrl))
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HoppIcons.Play, null, tint = Hopp.colors.valueText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(Res.string.sc_clip_play, clip.title.ifBlank { "Clip" }) + (clip.durationSeconds?.let { " · ${formatTime(it * 1000L)}" } ?: ""),
            fontWeight = FontWeight.Bold, color = Hopp.colors.valueText,
        )
    } else Text("🔒 $lockedText", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The blocks of a round, as the community page draws them: mined ones in
 *  orange, the halving block framed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainView(chain: Chain) {
    val colors = MaterialTheme.colorScheme
    if (chain.blocks in 1..216) FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(chain.blocks) { i ->
            Box(
                Modifier.size(11.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < chain.minedBlocks) Hopp.colors.value else Hopp.colors.progressTrack.copy(alpha = 0.3f))
                    .then(if (i + 1 == chain.halvingBlock) Modifier.border(1.5.dp, colors.onSurface, RoundedCornerShape(2.dp)) else Modifier),
            )
        }
    }
    Text(
        stringResource(Res.string.sc_chain, chain.minedBlocks, chain.blocks, formatSats(chain.blockSats.toLong())),
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
    )
}

@Composable
fun ReleaseBoostingSheet(round: ReleaseBoostingRound, show: ScShow?, onBoost: () -> Unit, onDismiss: () -> Unit) = ActionSheet(
    label = stringResource(Res.string.sc_rb_label), episode = round.episode, raised = round.raisedSats, goal = round.goalSats,
    action = stringResource(Res.string.np_boost), communityUrl = show?.communityPageUrl, onAction = onBoost, onDismiss = onDismiss,
) {
    Text(stringResource(Res.string.sc_publish_long, formatIsoShort(round.publishAt)), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(Res.string.sc_per_minute, round.satsPerMinute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    round.chain?.let { ChainView(it) }
    round.clip?.let { c -> ClipLine(c, stringResource(Res.string.sc_clip_at_block, c.atBlock ?: round.chain?.halvingBlock ?: 0), show) }
}

@Composable
fun UnlockSheet(item: Boost2UnlockEpisode, show: ScShow?, onUnlock: () -> Unit, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    ActionSheet(
        label = stringResource(Res.string.sc_b2u_label), episode = item.episode, raised = item.raisedSats, goal = item.goalSats,
        action = if (item.unlocked) null else stringResource(Res.string.sc_unlock), communityUrl = show?.communityPageUrl, onAction = onUnlock, onDismiss = onDismiss,
    ) {
        if (item.unlocked) Text(stringResource(Res.string.sc_unlocked), style = MaterialTheme.typography.titleSmall, color = Hopp.colors.valueText)
        item.link?.takeIf { item.unlocked }?.let { link -> TextButton(onClick = { runCatching { uri.openUri(link) } }) { Text(stringResource(Res.string.sc_open_episode)) } }
        item.clip?.let { c -> ClipLine(c, stringResource(Res.string.sc_clip_at_sats, formatSats(c.atSats ?: 0)), show) }
    }
}

/** An open suggestion: votes are free, one per listener (ADR 0018); a tap
 *  shows all of it (device feedback 2026-10-09). */
@Composable
fun SuggestionRow(s: Suggestion, voted: Boolean, onOpen: () -> Unit, onBoost: (() -> Unit)? = null, onVote: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(Res.string.sc_details), onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.amountSats > 0) Text("${formatSats(s.amountSats)} sats", style = MaterialTheme.typography.labelMedium, color = Hopp.colors.valueText)
        }
        Spacer(Modifier.size(8.dp))
        // Boosting it without opening it first (device feedback 2026-10-15).
        if (onBoost != null) {
            space.sovereigncast.hopp.ui.value.BoostIconButton(onBoost)
            Spacer(Modifier.size(8.dp))
        }
        VoteButton(s.voteCount, voted, onVote)
    }
}

@Composable
private fun VoteButton(count: Int, voted: Boolean, onVote: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .then(if (voted) Modifier.background(colors.onSurface) else Modifier.border(1.dp, colors.outline, RoundedCornerShape(50)))
            .clickable(enabled = !voted, onClickLabel = stringResource(Res.string.sc_vote), onClick = onVote).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("▲ $count", fontWeight = FontWeight.Bold, color = if (voted) colors.surface else colors.onSurface)
    }
}

/** A suggestion in full: who, when, the whole description, votes and sats —
 *  and, where the instance takes it, a boost that gives it weight (phase 2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionSheet(s: Suggestion, voted: Boolean, onVote: () -> Unit, onBoost: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val by = listOfNotNull(
                s.username.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.sc_by, it) },
                isoToEpochSeconds(s.createdAt)?.let { formatDate(it) },
            )
            if (by.isNotEmpty()) Text(by.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (s.description.isNotBlank()) SelectionContainer { Text(s.description, style = MaterialTheme.typography.bodyMedium) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.sc_votes, s.voteCount) + if (s.amountSats > 0) " · ${formatSats(s.amountSats)} sats" else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                )
                if (onBoost != null) {
                    ActionPill(stringResource(Res.string.np_boost), onBoost)
                    Spacer(Modifier.size(8.dp))
                }
                VoteButton(s.voteCount, voted, onVote)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Under the open suggestions: a new one, paid as its spam filter (phase 2). */
@Composable
fun SuggestButton(onClick: () -> Unit) = OutlinedButton(onClick = onClick, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
    Text("+ " + stringResource(Res.string.sc_suggest))
}

/** One of the show's most popular episodes, by its rank (phase 2). */
@Composable
fun TopEpisodeRow(e: TopEpisode, onOpen: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(place(e.rank), Modifier.width(32.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.description.isNotBlank()) Text(e.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── leaderboard (device feedback 2026-10-09) ───────────────────────────────

private fun place(rank: Int?) = when (rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; null -> ""; else -> "$rank." }

/** One boost of the community: sender, sats, message, which episode. A long
 *  message opens in full on a tap (device feedback 2026-10-14). */
@Composable
fun CommunityBoostRow(b: CommunityBoost, showRank: Boolean) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var cut by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = cut || open) { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showRank) Text(place(b.rank), Modifier.width(32.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        SelectionContainer(Modifier.weight(1f)) { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.sender.ifBlank { stringResource(Res.string.sc_anonymous) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.message.isNotBlank()) Text(
                b.message, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!open) cut = it.hasVisualOverflow },
            )
            if (cut && !open) Text(stringResource(Res.string.sc_more), style = MaterialTheme.typography.labelMedium, color = Hopp.colors.valueText)
            if (b.episode.title.isNotBlank()) Text(b.episode.title, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }
        Spacer(Modifier.size(8.dp))
        Text("${formatSats(b.amountSats)} sats", color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/** A boost in full — the whole message, selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityBoostSheet(b: CommunityBoost, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${formatSats(b.amountSats)} sats", color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            Text(b.sender.ifBlank { stringResource(Res.string.sc_anonymous) }, style = MaterialTheme.typography.titleMedium)
            if (b.episode.title.isNotBlank()) Text(b.episode.title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (b.message.isNotBlank()) SelectionContainer { Text(b.message, style = MaterialTheme.typography.bodyLarge) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A place in the all-time ranking: no amounts, only place, trend and medals. */
@Composable
fun BoosterRow(b: Booster) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(place(b.rank), Modifier.width(32.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.sender.ifBlank { stringResource(Res.string.sc_anonymous) }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (b.legendary) Text(" ⚡ " + stringResource(Res.string.sc_legendary), style = MaterialTheme.typography.labelSmall,
                    color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
            }
            val m = b.medals
            if (m.gold + m.silver + m.bronze > 0) Text(
                listOfNotNull("🥇 ${m.gold}".takeIf { m.gold > 0 }, "🥈 ${m.silver}".takeIf { m.silver > 0 }, "🥉 ${m.bronze}".takeIf { m.bronze > 0 })
                    .joinToString("  "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
        }
        val prev = b.prevRank
        Text(
            when {
                b.trend == "new" -> stringResource(Res.string.sc_new)
                b.trend == "up" && prev != null -> "▲ ${prev - b.rank}"
                b.trend == "down" && prev != null -> "▼ ${b.rank - prev}"
                else -> "–"
            },
            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
        )
    }
}

/**
 * Paying the instance's invoice (ADR 0005, 0018): amounts, message, name,
 * then each step shown — through the connected wallet, or a wallet app
 * via `lightning:` while Hopp waits for the instance's confirmation. A new
 * suggestion (phase 2) is the same payment with its topic instead of a
 * message, from the show's minimum on.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScPaySheet(vm: CommunityViewModel, target: ScTarget, title: String, satsPerMinute: Int?, onDismiss: () -> Unit) {
    val pay by vm.pay.collectAsState()
    val hasWallet by vm.hasWallet.collectAsState()
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val suggesting = target is ScTarget.NewSuggestion
    val min = if (suggesting) vm.suggestionMinSats else 1L
    val presets: List<Long> = when {
        suggesting -> (listOf(min) + listOf(210L, 1_000L, 2_100L).filter { it > min }).take(4)
        satsPerMinute != null -> listOf(1L, 10L, 60L).map { it * satsPerMinute }
        else -> listOf(100L, 500L, 1_000L, 2_100L)
    }
    var amount by remember { mutableLongStateOf(if (suggesting) presets[0] else presets[1]) }
    var custom by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var links by remember { mutableStateOf("") }
    val ready = amount >= min && (!suggesting || topic.isNotBlank())

    ModalBottomSheet(onDismissRequest = { vm.resetPay(); onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when (target) {
                    ScTarget.ReleaseBoosting -> stringResource(Res.string.sc_pay_title_rb, title)
                    is ScTarget.Boost2Unlock -> stringResource(Res.string.sc_pay_title_b2u, title)
                    is ScTarget.NewSuggestion -> stringResource(Res.string.sc_suggest)
                    is ScTarget.SuggestionBoost -> stringResource(Res.string.sc_pay_title_suggestion, title)
                },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            when (val p = pay) {
                is ScPay.Done -> {
                    Text(
                        if (suggesting) stringResource(Res.string.sc_suggest_done) else stringResource(Res.string.sc_done, formatSats(p.sats)),
                        style = MaterialTheme.typography.titleMedium, color = Hopp.colors.valueText,
                    )
                    TextButton(onClick = { vm.resetPay(); onDismiss() }, modifier = Modifier.padding(bottom = 24.dp)) { Text(stringResource(Res.string.boost_close)) }
                    return@Column
                }
                is ScPay.External -> {
                    Text(stringResource(Res.string.sc_external), color = colors.onSurfaceVariant)
                    TextButton(onClick = { runCatching { uri.openUri("lightning:" + p.invoice.bolt11) } }) { Text(stringResource(Res.string.sc_open_wallet_app)) }
                    CircularProgressIndicator(Modifier.padding(bottom = 24.dp))
                    return@Column
                }
                ScPay.Requesting, ScPay.Paying, ScPay.Confirming -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(when (p) {
                            ScPay.Requesting -> Res.string.sc_requesting
                            ScPay.Paying -> Res.string.sc_paying
                            else -> Res.string.sc_confirming
                        }))
                    }
                    return@Column
                }
                is ScPay.Failed -> Text(
                    when (p.code) {
                        "mismatch" -> stringResource(Res.string.sc_failed_mismatch)
                        "expired" -> stringResource(Res.string.sc_failed_expired)
                        "no_active_round" -> stringResource(Res.string.sc_failed_no_round)
                        else -> stringResource(Res.string.sc_failed, p.detail ?: p.code)
                    },
                    color = colors.error,
                )
                ScPay.Idle -> Unit
            }

            when (target) {
                is ScTarget.NewSuggestion -> {
                    // The instance's limits (listener-v1.yaml, SuggestionCreate).
                    OutlinedTextField(topic, { topic = it.take(300) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sc_suggest_title)) })
                    OutlinedTextField(about, { about = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sc_suggest_description)) }, minLines = 2)
                    OutlinedTextField(links, { links = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sc_suggest_links)) })
                    Text(stringResource(Res.string.sc_suggest_note, formatSats(min)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                is ScTarget.SuggestionBoost ->
                    Text(stringResource(Res.string.sc_suggestion_boost_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                else -> Unit
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { a ->
                    val label = formatSats(a) + (satsPerMinute?.let { m -> " · " + stringResource(Res.string.sc_minutes, (a / m).toInt()) } ?: "")
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(if (!custom && amount == a) Hopp.colors.value else colors.surfaceVariant)
                            .clickable { custom = false; amount = a }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) { Text(label, fontWeight = FontWeight.Bold, color = if (!custom && amount == a) Hopp.colors.onValue else colors.onSurface) }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (custom) Hopp.colors.value else colors.surfaceVariant)
                        .clickable { custom = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) { Text("…", fontWeight = FontWeight.Bold, color = if (custom) Hopp.colors.onValue else colors.onSurface) }
            }
            if (custom) OutlinedTextField(
                if (amount > 0) amount.toString() else "", { t -> amount = t.filter(Char::isDigit).take(9).toLongOrNull() ?: 0 },
                Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_custom)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = amount < min,
                supportingText = if (min > 1) { { Text(stringResource(Res.string.sc_min_sats, formatSats(min))) } } else null,
            )
            // The instance takes 100 characters (listener-v1.yaml, BoostRequest);
            // a suggestion carries its topic instead.
            if (!suggesting) OutlinedTextField(message, { message = it.take(100) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_message)) })
            OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_name)) }, singleLine = true)
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(if (ready) Hopp.colors.value else colors.surfaceVariant)
                    .clickable(enabled = ready) {
                        if (target is ScTarget.NewSuggestion) vm.pay(ScTarget.NewSuggestion(topic, about, links), amount, "", name, topic.trim())
                        else vm.pay(target, amount, message, name, title)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (hasWallet) Res.string.sc_send else Res.string.sc_send_external, formatSats(amount)),
                    fontWeight = FontWeight.Bold, color = if (ready) Hopp.colors.onValue else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
