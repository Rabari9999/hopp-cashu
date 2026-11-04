package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.semantics
import space.sovereigncast.hopp.ui.LocalPlatformActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.nostr.RelayAnswer
import space.sovereigncast.hopp.nostr.Refusal
import space.sovereigncast.hopp.sync.RelayRound
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncManager
import space.sovereigncast.hopp.sync.SyncKeyringNotKnown
import space.sovereigncast.hopp.nostr.Nip46Exception
import space.sovereigncast.hopp.nostr.SignerNoAnswer
import space.sovereigncast.hopp.sync.SyncNotPublished
import space.sovereigncast.hopp.sync.SyncNotReached
import space.sovereigncast.hopp.sync.SyncOutcome
import space.sovereigncast.hopp.sync.SyncStatus
import space.sovereigncast.hopp.sync.SyncTooLarge
import space.sovereigncast.hopp.ui.theme.Hopp

/**
 * Sync at a glance (maintainer 2026-10-22: only the Nostr page said whether progress went across). Since
 * 2026-11-03 at the profile picture rather than in Home's greeting (maintainer): a dot at its foot —
 * a tick that stays when the last round went through, a grey ring while something waits, "!" when it did
 * not — and an arc round the picture while a round runs. The tick only as before (ND-04, [look]).
 */
@Composable
internal fun SyncBadge(status: SyncStatus, size: androidx.compose.ui.unit.Dp, picture: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        picture()
        val look = look(status)
        if (look == Look.Running) CircularProgressIndicator(Modifier.size(size + 7.dp), strokeWidth = 2.dp, color = colors.onSurface)
        else {
            val dot = 14.dp
            Box(
                Modifier.align(Alignment.BottomEnd).size(dot).clip(CircleShape).background(colors.surface).padding(2.dp).clip(CircleShape)
                    .background(when (look) { Look.Done -> Hopp.colors.ok; Look.Problem -> colors.error; else -> colors.surface })
                    .then(if (look == Look.Waiting) Modifier.border(1.5.dp, colors.onSurfaceVariant, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                when (look) {
                    Look.Done -> Icon(Icons.Rounded.Check, null, Modifier.size(8.dp), tint = colors.surface)
                    Look.Problem -> Text("!", color = colors.onError, fontWeight = FontWeight.Black, fontSize = 8.sp, lineHeight = 8.sp)
                    else -> Unit
                }
            }
        }
    }
}

/** The state in a word, for a screen reader at the profile picture. */
@Composable
internal fun syncStateLabel(status: SyncStatus): String = stringResource(when (look(status)) {
    Look.Running -> Res.string.sync_running
    Look.Problem -> Res.string.home_sync_not_done
    Look.Waiting -> Res.string.home_sync_pending
    Look.Done -> Res.string.home_sync_done
})

/**
 * The profile menu's sync row (maintainer 2026-11-03): how it went in a line — a tap opens [SyncSheet],
 * ↻ starts a round at once. With sync off ([status] null) it asks to turn it on, on the Nostr page.
 */
@Composable
internal fun SyncMenuRow(status: SyncStatus?, nowMs: Long, onOpen: () -> Unit, onSyncNow: () -> Unit, onTurnOn: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp).widthIn(min = 240.dp).testTag("sync-row").clip(shape).background(colors.surfaceVariant)
            .clickable(onClick = if (status == null) onTurnOn else onOpen).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (status == null) {
            Text(stringResource(Res.string.sync_menu_off), Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            return@Row
        }
        val look = look(status)
        when (look) {
            Look.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
            Look.Problem -> Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = colors.error)
            Look.Waiting -> Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            Look.Done -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = Hopp.colors.ok)
        }
        Column(Modifier.weight(1f)) {
            Text(syncStateLabel(status), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            val problem = status.problem
            val sub = when (look) {
                Look.Problem -> problem?.let { problemLine(it) }
                Look.Waiting -> status.toSend.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.home_sync_changes, it, it) }
                Look.Done -> status.lastSyncedMs?.let { stringResource(Res.string.home_sync_when, ago((nowMs - it) / 1000)) }
                Look.Running -> null
            }
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2) }
        }
        androidx.compose.material3.IconButton(onClick = onSyncNow, enabled = !status.running) {
            Icon(Icons.Rounded.Refresh, stringResource(Res.string.sync_now), Modifier.size(20.dp))
        }
    }
}

internal enum class Look { Running, Problem, Waiting, Done }

/** The tick only when the last round went through and nothing made here waits to go out — a change made
 *  since, or no round yet since the start, is "waiting" (delta audit 3 2026-10-22, ND-04). */
internal fun look(status: SyncStatus): Look = when {
    status.running -> Look.Running
    status.problem != null -> Look.Problem
    status.toSend > 0 || status.lastSyncedMs == null -> Look.Waiting
    else -> Look.Done
}

/** What the last round came to — only what matters, no explanations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSheet(status: SyncStatus, nowMs: Long, onSyncNow: () -> Unit, onSettings: () -> Unit, onRewrite: (String) -> Unit = {}, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val problem = status.problem
    // Each relay after every round (maintainer 2026-10-29: two of three went through and the third's reason was
    // nowhere); a tap shows what it answered. How many a document needs, only when too few went through.
    val rounds = status.relayRounds.takeIf { !status.running }.orEmpty()
    val failing = problem != null && rounds.any { !it.ok }
    var detail by remember { mutableStateOf<String?>(null) }
    val actions = LocalPlatformActions.current
    // Open whole, and scrolling where the screen is too short: on a small phone with larger text the half-open
    // sheet hid its buttons (delta audit 4 2026-10-23).
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val open = rounds.firstOrNull { it.url == detail }
            if (open != null) {
                RelayDetail(open, status.relayHistory[open.url].orEmpty()) { detail = null }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (look(status)) {
                    Look.Running -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Look.Problem -> Icon(Icons.Rounded.Warning, null, Modifier.size(26.dp), tint = colors.error)
                    Look.Waiting -> Icon(Icons.Rounded.Refresh, null, Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                    Look.Done -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(26.dp), tint = Hopp.colors.ok)
                }
                Column {
                    Text(
                        stringResource(when (look(status)) {
                            Look.Running -> Res.string.sync_running
                            Look.Problem -> Res.string.home_sync_not_done
                            Look.Waiting -> Res.string.home_sync_pending
                            Look.Done -> Res.string.home_sync_done
                        }),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                    val sub = if (problem != null && !status.running) problemLine(problem)
                        else status.lastSyncedMs?.let { stringResource(Res.string.home_sync_when, ago((nowMs - it) / 1000)) }
                    sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
            }
            if (failing) QuorumBar(rounds.count { it.ok }, rounds.size)
            if (rounds.isNotEmpty()) RelayList(rounds, status.relayHistory) { detail = it }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).border(1.dp, colors.surfaceVariant, RoundedCornerShape(16.dp))) {
                val lines = buildList {
                    if (problem != null) status.lastSyncedMs?.let { add(stringResource(Res.string.home_sync_last) to ago((nowMs - it) / 1000)) }
                    if (rounds.isEmpty()) status.relays?.let { (answered, asked) -> add(stringResource(Res.string.home_sync_relays) to stringResource(Res.string.home_sync_of, answered, asked)) }
                    if (problem == null) add(stringResource(Res.string.home_sync_got) to got(status.applied))
                    add(stringResource(Res.string.home_sync_to_send) to
                        if (status.toSend == 0) stringResource(Res.string.home_sync_nothing) else pluralStringResource(Res.plurals.home_sync_changes, status.toSend, status.toSend))
                    if (status.pending > 0) add(stringResource(Res.string.home_sync_waiting) to pluralStringResource(Res.plurals.home_sync_waiting_n, status.pending, status.pending))
                }
                lines.forEachIndexed { i, (label, value) ->
                    if (i > 0) HorizontalDivider(color = colors.surfaceVariant)
                    // The label keeps its line; a long value wraps on the right, one item per line (device feedback 2026-10-22).
                    // A label too long for a small screen ends in "…" instead of being cut at the edge (delta audit 4 2026-10-23).
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.size(12.dp))
                        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    }
                }
            }
            // One line each, smaller where it must: on 320 dp "Jetzt abgleichen" took two lines and "Einstellungen"
            // broke in the word (delta audit 4 2026-10-23).
            val label = MaterialTheme.typography.labelLarge
            val inside = PaddingValues(horizontal = 12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSyncNow, enabled = !status.running, modifier = Modifier.weight(1f).height(48.dp), contentPadding = inside) {
                    FittingLine(stringResource(when {
                        problem?.isTheSigner() == true -> Res.string.sync_allow_now
                        problem != null -> Res.string.home_sync_retry
                        else -> Res.string.sync_now
                    }), label, min = 11.sp)
                }
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f).height(48.dp), contentPadding = inside) {
                    FittingLine(stringResource(Res.string.settings), label, min = 11.sp)
                }
            }
            if (problem?.isTheSigner() == true && !status.running) Text(stringResource(Res.string.sync_allow_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (failing && status.toSend > 0) Text(stringResource(Res.string.sync_nothing_lost), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            // A document the relays hold only in part (maintainer 2026-10-30: "Incomplete" for good): when Hopp
            // writes it anew by itself, and the way to have it done now, from what this device has.
            // Offered only where a majority read it to the end (review 2026-10-31): a relay that did not answer may hold more.
            (problem as? SyncOutcome.Incomplete)?.takeIf { it.canRewrite && it.doc in AREAS }?.let { incomplete ->
                val doc = incomplete.doc!!
                val name = areaName(doc)
                incomplete.rewriteInMs?.let { ms ->
                    Text(stringResource(Res.string.sync_rewrite_in, name, ((ms + 59_999) / 60_000).coerceAtLeast(1)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Text(stringResource(Res.string.sync_rewrite_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                OutlinedButton(onClick = { onRewrite(doc) }, enabled = !status.running, modifier = Modifier.fillMaxWidth().height(48.dp), contentPadding = inside) {
                    FittingLine(stringResource(Res.string.sync_rewrite, name), label, min = 11.sp)
                }
            }
            if (rounds.isNotEmpty()) {
                val report = report(status, nowMs)
                TextButton(onClick = { actions.copyText(report, "Hopp sync") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(Res.string.sync_copy_report))
                }
            }
        }
    }
}

/** How many relays went through of how many, and the majority a document needs (SyncRelays). */
@Composable
private fun QuorumBar(ok: Int, asked: Int) {
    val needed = asked / 2 + 1
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(asked) { i ->
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (i < ok) Hopp.colors.ok else colors.surfaceVariant))
            }
        }
        Text(stringResource(Res.string.sync_quorum, ok, asked, needed), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** One line per relay: its name, what went wrong in a few words, read and send. */
@Composable
private fun RelayList(rounds: List<RelayRound>, history: Map<String, List<Boolean>>, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).border(1.dp, colors.surfaceVariant, RoundedCornerShape(16.dp))) {
        // What went wrong first.
        rounds.sortedByDescending { it.worst.weight }.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = colors.surfaceVariant)
            Row(Modifier.fillMaxWidth().clickable { onOpen(r.url) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StateDot(r.worst)
                Column(Modifier.weight(1f)) {
                    Text(host(r.url), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // One that answered everything: one short line.
                    if (r.ok) {
                        Text(listOfNotNull(answerLine(r.worst), r.ms?.let(::duration)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        return@Column
                    }
                    Text(answerLine(r.worst), style = MaterialTheme.typography.bodyMedium, color = tint(r.worst), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val failed = history[r.url].orEmpty().count { !it }
                    val line = listOfNotNull(
                        stringResource(Res.string.sync_read) + " " + mark(r.reads.values.maxByOrNull { it.weight }),
                        stringResource(Res.string.sync_send) + " " + mark(r.sends.values.maxByOrNull { it.weight }),
                    ).joinToString(" · ")
                    Text(line, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    if (!r.ok && failed > 1) Text(stringResource(Res.string.sync_failed_rounds, failed, history[r.url].orEmpty().size), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Text("›", color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** One relay: what it answered per document, its own words, its last rounds, what to do. */
@Composable
private fun RelayDetail(r: RelayRound, history: List<Boolean>, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(space.sovereigncast.hopp.ui.components.HoppIcons.ChevronLeft, stringResource(Res.string.back)) }
        Text(host(r.url), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StateDot(r.worst)
        Text(answerLine(r.worst), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = tint(r.worst))
    }
    Text(stringResource(hint(r.worst)), style = MaterialTheme.typography.bodyMedium)
    // Per document: read and send, "–" where nothing was asked.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).border(1.dp, colors.surfaceVariant, RoundedCornerShape(16.dp))) {
        val areas = (r.reads.keys + r.sends.keys).sortedBy { AREAS.indexOf(it).let { i -> if (i < 0) AREAS.size else i } }
        val column = Modifier.width(64.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.sync_read), column, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(stringResource(Res.string.sync_send), column, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        areas.forEach { area ->
            HorizontalDivider(color = colors.surfaceVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(areaName(area), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(mark(r.reads[area]), column, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = r.reads[area]?.let { tint(it) } ?: colors.onSurfaceVariant)
                Text(mark(r.sends[area]), column, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = r.sends[area]?.let { tint(it) } ?: colors.onSurfaceVariant)
            }
        }
    }
    // The relay's own words, as it sent them.
    (r.worst as? RelayAnswer.Refused ?: r.worst as? RelayAnswer.Unreachable)?.let { a ->
        val words = when (a) { is RelayAnswer.Refused -> a.reason; is RelayAnswer.Unreachable -> a.error; else -> null }
        if (!words.isNullOrBlank()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.sync_relay_reply), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Text(words, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant).padding(12.dp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (history.size > 1) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.sync_relay_rounds), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        val failed = history.count { !it }
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            history.forEach { ok -> Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(if (ok) Hopp.colors.ok else colors.error)) }
            repeat(SyncManager.HISTORY - history.size) { Spacer(Modifier.weight(1f)) }
        }
        Text(stringResource(Res.string.sync_failed_rounds, failed, history.size), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** A dot in the answer's colour; its meaning is in the text beside it. */
@Composable
private fun StateDot(a: RelayAnswer) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(tint(a)))
}

@Composable
private fun tint(a: RelayAnswer) = when (a) {
    is RelayAnswer.Ok -> Hopp.colors.ok
    is RelayAnswer.Refused, is RelayAnswer.Unreachable -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "✓", "✗" or "–" (not asked); a refusal or silence the line beside it says in words. */
private fun mark(a: RelayAnswer?): String = when (a) {
    null -> "–"
    is RelayAnswer.Ok -> "✓"
    else -> "✗"
}

@Composable
private fun answerLine(a: RelayAnswer): String = when (a) {
    is RelayAnswer.Ok -> stringResource(Res.string.sync_relay_ok)
    is RelayAnswer.Refused -> stringResource(Res.string.sync_relay_refused, stringResource(why(Refusal.of(a.reason))))
    RelayAnswer.Silent -> stringResource(Res.string.sync_relay_silent)
    is RelayAnswer.Unreachable -> stringResource(Res.string.sync_relay_unreachable)
    RelayAnswer.Partial -> stringResource(Res.string.sync_relay_partial)
}

private fun why(r: Refusal) = when (r) {
    Refusal.RateLimited -> Res.string.sync_why_rate
    Refusal.Blocked -> Res.string.sync_why_blocked
    Refusal.AuthRequired -> Res.string.sync_why_auth
    Refusal.Restricted -> Res.string.sync_why_restricted
    Refusal.Pow -> Res.string.sync_why_pow
    Refusal.Invalid -> Res.string.sync_why_invalid
    Refusal.Error -> Res.string.sync_why_error
    Refusal.Other -> Res.string.sync_why_other
}

private fun hint(a: RelayAnswer) = when (a) {
    is RelayAnswer.Refused -> when (Refusal.of(a.reason)) {
        Refusal.RateLimited -> Res.string.sync_hint_rate
        Refusal.Blocked, Refusal.AuthRequired, Refusal.Restricted, Refusal.Pow -> Res.string.sync_hint_refused
        else -> Res.string.sync_hint_error
    }
    RelayAnswer.Silent, is RelayAnswer.Unreachable -> Res.string.sync_hint_down
    RelayAnswer.Partial -> Res.string.sync_hint_partial
    is RelayAnswer.Ok -> Res.string.sync_hint_error
}

private val AREAS = listOf(SyncDocs.KEYRING, SyncDocs.SETTINGS, SyncDocs.SUBSCRIPTIONS, SyncDocs.PROGRESS, SyncDocs.QUEUE, SyncDocs.STATS)

@Composable
private fun areaName(area: String): String = when (area) {
    SyncDocs.SUBSCRIPTIONS -> stringResource(Res.string.sync_area_subscriptions)
    SyncDocs.PROGRESS -> stringResource(Res.string.sync_area_progress)
    SyncDocs.QUEUE -> stringResource(Res.string.queue_title)
    SyncDocs.SETTINGS -> stringResource(Res.string.sync_area_settings)
    SyncDocs.STATS -> stringResource(Res.string.sync_area_stats)
    SyncDocs.KEYRING -> stringResource(Res.string.sync_area_keyring)
    else -> area
}

private fun host(url: String) = url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')

private fun duration(ms: Long) = if (ms < 1000) "$ms ms" else "${space.sovereigncast.hopp.ui.components.formatTenths(ms / 100)} s"

/** The round in plain text, to pass on when asking for help: technical, not translated. */
private fun report(status: SyncStatus, nowMs: Long): String = buildString {
    appendLine("Hopp sync, ${(nowMs - (status.lastSyncedMs ?: nowMs)) / 60_000} min since the last full round")
    // Names of its own: a release build shortens class names ("w77", maintainer 2026-10-30).
    status.problem?.let { p ->
        val kind = when (p) {
            is SyncOutcome.Incomplete -> "Incomplete"
            is SyncOutcome.Failed -> "Failed"
            SyncOutcome.WaitsForSigner -> "Waits for the signer"
            SyncOutcome.NeedsUpdate -> "Needs a newer Hopp"
            SyncOutcome.NoIdentity -> "No identity"
            else -> "Other"
        }
        appendLine("Problem: $kind" + ((p as? SyncOutcome.Failed)?.error?.let { e -> " – " + (e.message ?: "error") } ?: ""))
        (p as? SyncOutcome.Incomplete)?.let { i ->
            appendLine("  document: ${i.doc ?: "?"}" + (i.rewriteInMs?.let { ms -> ", written anew in ${(ms + 59_999) / 60_000} min" } ?: ""))
            i.found?.takeIf { it.isNotBlank() }?.let { appendLine("  read: $it") }
        }
    }
    status.relayRounds.forEach { r ->
        appendLine("${host(r.url)}: ${text(r.worst)}")
        (r.reads.keys + r.sends.keys).forEach { area -> appendLine("  $area: read ${r.reads[area]?.let(::text) ?: "–"}, send ${r.sends[area]?.let(::text) ?: "–"}") }
        status.relayHistory[r.url]?.let { h -> appendLine("  last rounds: " + h.joinToString("") { if (it) "✓" else "✗" }) }
    }
    if (status.toSend > 0) appendLine("Waiting to send: ${status.toSend}")
    status.documents.forEach { (doc, what) -> appendLine("$doc: $what") }
}

private fun text(a: RelayAnswer) = when (a) {
    is RelayAnswer.Ok -> "ok (${a.ms} ms)"
    is RelayAnswer.Refused -> "refused (${a.reason})"
    RelayAnswer.Silent -> "no answer in time"
    is RelayAnswer.Unreachable -> "unreachable" + (a.error?.let { " ($it)" } ?: "")
    RelayAnswer.Partial -> "read in part"
}

/** What the last round brought, per document, one per line: "1 show", "2 progress" — or "nothing". */
@Composable
private fun got(applied: Map<String, Int>): String {
    val parts = listOfNotNull(
        applied[SyncDocs.SUBSCRIPTIONS]?.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.home_sync_got_shows, it, it) },
        applied[SyncDocs.PROGRESS]?.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.home_sync_got_progress, it, it) },
        applied[SyncDocs.QUEUE]?.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.home_sync_got_queue, it, it) },
        applied[SyncDocs.SETTINGS]?.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.home_sync_got_settings, it, it) },
    )
    return if (parts.isEmpty()) stringResource(Res.string.home_sync_nothing) else parts.joinToString("\n")
}

/** Why the last round did not go through, in a few words. */
@Composable
internal fun problemLine(problem: SyncOutcome): String = when (problem) {
    SyncOutcome.WaitsForSigner -> stringResource(Res.string.home_sync_short_signer)
    SyncOutcome.NeedsUpdate -> stringResource(Res.string.home_sync_short_newer)
    is SyncOutcome.Incomplete -> stringResource(Res.string.home_sync_short_part)
    is SyncOutcome.Failed -> when (val e = problem.error) {
        is SyncNotReached -> reach(e.answered, e.asked)
        is SyncKeyringNotKnown -> reach(e.answered, e.asked)
        is SyncNotPublished -> if (e.asked > 0) stringResource(Res.string.home_sync_short_taken, e.took, e.asked) else stringResource(Res.string.home_sync_short_failed)
        is SyncTooLarge -> stringResource(Res.string.home_sync_short_large)
        is SignerNoAnswer -> stringResource(Res.string.home_sync_short_silent)
        is Nip46Exception -> stringResource(Res.string.home_sync_short_refused)
        else -> stringResource(Res.string.home_sync_short_failed)
    }
    else -> stringResource(Res.string.home_sync_short_failed)
}

@Composable
private fun reach(answered: Int, asked: Int): String =
    if (answered == 0 || asked == 0) stringResource(Res.string.home_sync_short_none) else stringResource(Res.string.home_sync_short_few, answered, asked)
