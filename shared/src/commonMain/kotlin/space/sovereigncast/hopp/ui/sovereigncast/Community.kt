package space.sovereigncast.hopp.ui.sovereigncast

import androidx.compose.foundation.background
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
import space.sovereigncast.hopp.sovereigncast.Suggestion
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.theme.Hopp

/** "✓ Sovereigncast" — the show was verified with its instance (ADR 0002). */
@Composable
fun ScBadge() {
    Text(
        "✓ " + stringResource(Res.string.sc_badge),
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, Hopp.colors.value, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold,
    )
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

/** The clip at its mark: locked, it only says where; unlocked, it plays. */
@Composable
private fun ClipLine(clip: ClipState, lockedText: String) {
    val uri = LocalUriHandler.current
    val url = clip.url
    if (clip.unlocked && url != null) Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable { runCatching { uri.openUri(url) } }.padding(vertical = 6.dp),
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
fun ReleaseBoostingSheet(round: ReleaseBoostingRound, communityUrl: String?, onBoost: () -> Unit, onDismiss: () -> Unit) = ActionSheet(
    label = stringResource(Res.string.sc_rb_label), episode = round.episode, raised = round.raisedSats, goal = round.goalSats,
    action = stringResource(Res.string.np_boost), communityUrl = communityUrl, onAction = onBoost, onDismiss = onDismiss,
) {
    Text(stringResource(Res.string.sc_publish_long, formatIsoShort(round.publishAt)), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(Res.string.sc_per_minute, round.satsPerMinute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    round.chain?.let { ChainView(it) }
    round.clip?.let { c -> ClipLine(c, stringResource(Res.string.sc_clip_at_block, c.atBlock ?: round.chain?.halvingBlock ?: 0)) }
}

@Composable
fun UnlockSheet(item: Boost2UnlockEpisode, communityUrl: String?, onUnlock: () -> Unit, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    ActionSheet(
        label = stringResource(Res.string.sc_b2u_label), episode = item.episode, raised = item.raisedSats, goal = item.goalSats,
        action = if (item.unlocked) null else stringResource(Res.string.sc_unlock), communityUrl = communityUrl, onAction = onUnlock, onDismiss = onDismiss,
    ) {
        if (item.unlocked) Text(stringResource(Res.string.sc_unlocked), style = MaterialTheme.typography.titleSmall, color = Hopp.colors.valueText)
        item.link?.takeIf { item.unlocked }?.let { link -> TextButton(onClick = { runCatching { uri.openUri(link) } }) { Text(stringResource(Res.string.sc_open_episode)) } }
        item.clip?.let { c -> ClipLine(c, stringResource(Res.string.sc_clip_at_sats, formatSats(c.atSats ?: 0))) }
    }
}

/** An open suggestion: votes are free, one per listener (ADR 0018); a tap
 *  shows all of it (device feedback 2026-10-09). */
@Composable
fun SuggestionRow(s: Suggestion, voted: Boolean, onOpen: () -> Unit, onVote: () -> Unit) {
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

/** A suggestion in full: who, when, the whole description, votes and sats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionSheet(s: Suggestion, voted: Boolean, onVote: () -> Unit, onDismiss: () -> Unit) {
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
                VoteButton(s.voteCount, voted, onVote)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── leaderboard (device feedback 2026-10-09) ───────────────────────────────

private fun place(rank: Int?) = when (rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; null -> ""; else -> "$rank." }

/** One boost of the community: sender, sats, message, which episode. */
@Composable
fun CommunityBoostRow(b: CommunityBoost, showRank: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        if (showRank) Text(place(b.rank), Modifier.width(32.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.sender.ifBlank { stringResource(Res.string.sc_anonymous) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.message.isNotBlank()) Text(b.message, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (b.episode.title.isNotBlank()) Text(b.episode.title, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(8.dp))
        Text("${formatSats(b.amountSats)} sats", color = Hopp.colors.valueText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
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
 * via `lightning:` while Hopp waits for the instance's confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScPaySheet(vm: CommunityViewModel, target: ScTarget, title: String, satsPerMinute: Int?, onDismiss: () -> Unit) {
    val pay by vm.pay.collectAsState()
    val hasWallet by vm.hasWallet.collectAsState()
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val presets: List<Long> = satsPerMinute?.let { m -> listOf(1L, 10L, 60L).map { it * m } } ?: listOf(100L, 500L, 1_000L, 2_100L)
    var amount by remember { mutableLongStateOf(presets[1]) }
    var custom by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = { vm.resetPay(); onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (target is ScTarget.ReleaseBoosting) Res.string.sc_pay_title_rb else Res.string.sc_pay_title_b2u, title),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            when (val p = pay) {
                is ScPay.Done -> {
                    Text(stringResource(Res.string.sc_done, formatSats(p.sats)), style = MaterialTheme.typography.titleMedium, color = Hopp.colors.valueText)
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
            )
            // The instance takes 100 characters (listener-v1.yaml, BoostRequest).
            OutlinedTextField(message, { message = it.take(100) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_message)) })
            OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_name)) }, singleLine = true)
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50)).background(Hopp.colors.value)
                    .clickable(enabled = amount > 0) { vm.pay(target, amount, message, name, title) }.padding(bottom = 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (hasWallet) Res.string.sc_send else Res.string.sc_send_external, formatSats(amount)),
                    fontWeight = FontWeight.Bold, color = Hopp.colors.onValue,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
