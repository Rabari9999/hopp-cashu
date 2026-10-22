package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncKeyringNotKnown
import space.sovereigncast.hopp.sync.SyncNotPublished
import space.sovereigncast.hopp.sync.SyncNotReached
import space.sovereigncast.hopp.sync.SyncOutcome
import space.sovereigncast.hopp.sync.SyncStatus
import space.sovereigncast.hopp.sync.SyncTooLarge
import space.sovereigncast.hopp.ui.theme.Hopp

/**
 * Sync at a glance on Home (maintainer 2026-10-22: only the Nostr page said whether progress
 * went across): a spinner while a round runs, a tick when the last one went through, a mark
 * when it did not — at the end of the greeting's line, one tap from [SyncSheet].
 */
@Composable
internal fun SyncMark(status: SyncStatus, onOpen: () -> Unit) {
    val label = stringResource(Res.string.home_sync_label)
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onOpen), contentAlignment = Alignment.Center) {
        when {
            status.running -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status.problem != null -> Icon(Icons.Rounded.Warning, label, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            else -> Icon(Icons.Rounded.CheckCircle, label, Modifier.size(16.dp), tint = Hopp.colors.ok)
        }
    }
}

/** What the last round came to — only what matters, no explanations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSheet(status: SyncStatus, nowMs: Long, onSyncNow: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val problem = status.problem
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    status.running -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    problem != null -> Icon(Icons.Rounded.Warning, null, Modifier.size(26.dp), tint = colors.error)
                    else -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(26.dp), tint = Hopp.colors.ok)
                }
                Column {
                    Text(
                        stringResource(when {
                            status.running -> Res.string.sync_running
                            problem != null -> Res.string.home_sync_not_done
                            else -> Res.string.home_sync_done
                        }),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                    val sub = if (problem != null && !status.running) problemLine(problem)
                        else status.lastSyncedMs?.let { stringResource(Res.string.home_sync_when, ago((nowMs - it) / 1000)) }
                    sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer).border(1.dp, colors.surfaceVariant, RoundedCornerShape(16.dp))) {
                val lines = buildList {
                    if (problem != null) status.lastSyncedMs?.let { add(stringResource(Res.string.home_sync_last) to ago((nowMs - it) / 1000)) }
                    status.relays?.let { (answered, asked) -> add(stringResource(Res.string.home_sync_relays) to stringResource(Res.string.home_sync_of, answered, asked)) }
                    if (problem == null) add(stringResource(Res.string.home_sync_got) to got(status.applied))
                    add(stringResource(Res.string.home_sync_to_send) to
                        if (status.toSend == 0) stringResource(Res.string.home_sync_nothing) else pluralStringResource(Res.plurals.home_sync_changes, status.toSend, status.toSend))
                    if (status.pending > 0) add(stringResource(Res.string.home_sync_waiting) to pluralStringResource(Res.plurals.home_sync_waiting_n, status.pending, status.pending))
                }
                lines.forEachIndexed { i, (label, value) ->
                    if (i > 0) HorizontalDivider(color = colors.surfaceVariant)
                    // The label keeps its line; a long value wraps on the right, one item per line (device feedback 2026-10-22).
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
                        Spacer(Modifier.size(12.dp))
                        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSyncNow, enabled = !status.running, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(stringResource(if (problem != null) Res.string.home_sync_retry else Res.string.sync_now))
                }
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(Res.string.settings)) }
            }
        }
    }
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
private fun problemLine(problem: SyncOutcome): String = when (problem) {
    SyncOutcome.WaitsForSigner -> stringResource(Res.string.home_sync_short_signer)
    SyncOutcome.NeedsUpdate -> stringResource(Res.string.home_sync_short_newer)
    is SyncOutcome.Incomplete -> stringResource(Res.string.home_sync_short_part)
    is SyncOutcome.Failed -> when (val e = problem.error) {
        is SyncNotReached -> reach(e.answered, e.asked)
        is SyncKeyringNotKnown -> reach(e.answered, e.asked)
        is SyncNotPublished -> if (e.asked > 0) stringResource(Res.string.home_sync_short_taken, e.took, e.asked) else stringResource(Res.string.home_sync_short_failed)
        is SyncTooLarge -> stringResource(Res.string.home_sync_short_large)
        else -> stringResource(Res.string.home_sync_short_failed)
    }
    else -> stringResource(Res.string.home_sync_short_failed)
}

@Composable
private fun reach(answered: Int, asked: Int): String =
    if (answered == 0 || asked == 0) stringResource(Res.string.home_sync_short_none) else stringResource(Res.string.home_sync_short_few, answered, asked)
