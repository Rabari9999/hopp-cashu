package space.sovereigncast.hopp.ui.value

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.boost_close
import space.sovereigncast.hopp.resources.boost_custom
import space.sovereigncast.hopp.resources.boost_done
import space.sovereigncast.hopp.resources.boost_fees
import space.sovereigncast.hopp.resources.boost_failed
import space.sovereigncast.hopp.resources.boost_less
import space.sovereigncast.hopp.resources.boost_message
import space.sovereigncast.hopp.resources.boost_more
import space.sovereigncast.hopp.resources.boost_name
import space.sovereigncast.hopp.resources.boost_no_value
import space.sovereigncast.hopp.resources.boost_no_wallet
import space.sovereigncast.hopp.resources.boost_partial
import space.sovereigncast.hopp.resources.boost_send
import space.sovereigncast.hopp.resources.boost_send_over
import space.sovereigncast.hopp.resources.boost_split
import space.sovereigncast.hopp.resources.boost_title
import space.sovereigncast.hopp.resources.boost_unreachable
import space.sovereigncast.hopp.resources.boost_via
import space.sovereigncast.hopp.resources.boost_where
import space.sovereigncast.hopp.resources.boost_whole_show
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.np_boost
import space.sovereigncast.hopp.resources.np_over_budget
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.resources.np_streaming
import space.sovereigncast.hopp.resources.reason_invoice
import space.sovereigncast.hopp.resources.reason_keysend
import space.sovereigncast.hopp.resources.reason_remote
import space.sovereigncast.hopp.resources.reason_self
import space.sovereigncast.hopp.resources.reason_dust
import space.sovereigncast.hopp.resources.boost_none_payable
import space.sovereigncast.hopp.resources.boost_not_paid
import space.sovereigncast.hopp.resources.stream_invite_on
import space.sovereigncast.hopp.resources.stream_invite_text
import space.sovereigncast.hopp.resources.stream_invite_title
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatMsat
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.ui.theme.Hopp
import space.sovereigncast.hopp.value.BoostPlan
import space.sovereigncast.hopp.value.PlannedShare
import space.sovereigncast.hopp.value.ShareResult
import space.sovereigncast.hopp.value.SkipReason

/**
 * The value bar of now playing (ADR 0019): the boost button and the streaming
 * switch with rate and today's total — the only orange on screen. Streaming is
 * off until the listener turns it on, and turning it on first says what it
 * costs (ADR 0005).
 */
@Composable
fun ValueBar(vm: ValueBarViewModel, item: EpisodeItem, onBoost: () -> Unit, onOpenWallet: () -> Unit) {
    val settings by vm.current.collectAsState()
    val wallet by vm.connected.collectAsState()
    val rate by vm.rate.collectAsState()
    val today by vm.streamedToday.collectAsState()
    val pending by vm.pending.collectAsState()
    val over by vm.overBudget.collectAsState()
    var invite by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(item.id, settings?.streamRateSats) { vm.forEpisode(item) }
    val streaming = settings?.streaming == true && wallet != null

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp)).padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Hopp.colors.value).clickable(onClick = onBoost).padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HoppIcons.Hop, null, tint = Hopp.colors.onValue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(Res.string.np_boost), color = Hopp.colors.onValue, fontWeight = FontWeight.Bold)
        }
        // Sent and set aside, and a tap says when and why (device feedback 2026-10-11).
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .then(if (streaming) Modifier.clickable(onClickLabel = stringResource(Res.string.stream_sheet_title)) { details = true } else Modifier)
                .padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            Text(stringResource(Res.string.np_streaming, rate ?: 0), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            if (streaming) Text(
                if (over) stringResource(Res.string.np_over_budget)
                else stringResource(Res.string.np_streamed_pending, formatSats(today / 1000), pendingSats(pending)) + " ›",
                style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText,
            )
        }
        Switch(
            checked = streaming,
            onCheckedChange = { on ->
                when {
                    !on -> vm.setStreaming(false)
                    wallet == null -> onOpenWallet()
                    else -> invite = true
                }
            },
            colors = SwitchDefaults.colors(checkedTrackColor = Hopp.colors.value, checkedThumbColor = Hopp.colors.onValue),
        )
    }

    if (details) StreamSheet(vm, item, today, pending, rate ?: 0) { details = false }

    if (invite) AlertDialog(
        onDismissRequest = { invite = false },
        title = { Text(stringResource(Res.string.np_streaming, rate ?: 0)) },
        text = { Text(stringResource(Res.string.stream_invite_text, rate ?: 0, formatSats((settings?.dailyBudgetSats ?: 0).toLong()))) },
        confirmButton = { TextButton(onClick = { invite = false; vm.setStreaming(true) }) { Text(stringResource(Res.string.stream_invite_on)) } },
        dismissButton = { TextButton(onClick = { invite = false }) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** Whole sats, and "<1" for a part of one — the bar has no room for decimals. */
@Composable
private fun pendingSats(msat: Long): String = if (msat in 1..999) "<1" else formatSats(msat / 1000)

/**
 * What streaming has sent and what waits (device feedback 2026-10-11:
 * payments looked random): today's total, what is set aside, when the next
 * batch goes, the rule behind it, and per recipient what this episode has
 * set aside — with a button to send the whole sats now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamSheet(vm: ValueBarViewModel, item: EpisodeItem, todayMsat: Long, pendingMsat: Long, rate: Int, onDismiss: () -> Unit) {
    val detail by vm.detail.collectAsState()
    val colors = MaterialTheme.colorScheme
    // Accruals grow every few seconds while playing; the countdown with them.
    LaunchedEffect(item.id) { while (true) { vm.refreshDetail(item.id); kotlinx.coroutines.delay(3_000) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.stream_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(Res.string.np_streaming, rate), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            StreamFigure(stringResource(Res.string.stream_sent_today), formatSats(todayMsat / 1000) + " sats")
            StreamFigure(stringResource(Res.string.stream_pending), formatMsat(pendingMsat) + " sats")
            detail?.takeIf { it.episodeId == item.id }?.nextSettleSeconds?.let { s ->
                StreamFigure(stringResource(Res.string.stream_next), if (s < 60) stringResource(Res.string.stream_next_soon) else stringResource(Res.string.stream_next_minutes, ((s + 59) / 60).toInt()))
            }
            Text(stringResource(Res.string.stream_how), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

            Text(stringResource(Res.string.stream_recipients), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            val d = detail
            // Until the first look at this episode, nothing — not "nothing set aside".
            if (d == null || d.episodeId != item.id) CircularProgressIndicator(Modifier.size(24.dp)) else {
                if (d.shares.isEmpty()) Text(stringResource(Res.string.stream_nothing), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                d.shares.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(formatMsat(s.msat) + " sats", style = MaterialTheme.typography.bodyMedium, color = if (s.msat >= 1000) Hopp.colors.valueText else colors.onSurfaceVariant)
                    }
                }
                val elsewhere = pendingMsat - d.shares.sumOf { it.msat }
                if (elsewhere >= 1) Text(stringResource(Res.string.stream_other, formatMsat(elsewhere)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (d.shares.any { it.msat >= 1000 }) TextButton(onClick = { vm.settleNow(item.id) }) { Text(stringResource(Res.string.stream_send_now), fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StreamFigure(label: String, value: String) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

private val PRESETS = listOf(100L, 500L, 1_000L, 2_100L)

/**
 * The boost dialog (ADR 0019): where in the episode, amount presets, message
 * and name, the split as one bar — an unreachable share in orange — and one
 * button with the exact amount; underneath the wallet and today's budget.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BoostSheet(
    vm: ValueBarViewModel,
    /** null: a boost to the whole show (device feedback 2026-10-08). */
    item: EpisodeItem?,
    showTitle: String,
    positionMs: Long,
    onDismiss: () -> Unit,
    onOpenWallet: () -> Unit,
    podcastId: Long = item?.podcastId ?: 0,
) {
    val wallet by vm.connected.collectAsState()
    val settings by vm.current.collectAsState()
    val plan by vm.plan.collectAsState()
    val state by vm.boost.collectAsState()
    val left by vm.budgetLeft.collectAsState()
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val position = remember { positionMs / 1000 }
    var amount by remember { mutableLongStateOf(1_000L) }
    var custom by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var name by remember(settings?.senderName) { mutableStateOf(settings?.senderName.orEmpty()) }
    var overConfirmed by remember { mutableStateOf(false) }
    LaunchedEffect(amount) {
        overConfirmed = false
        if (amount > 0) { if (item != null) vm.plan(item, position, amount) else vm.planShow(podcastId, amount) }
    }

    ModalBottomSheet(onDismissRequest = { vm.resetBoost(); onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HoppIcons.Hop, null, tint = Hopp.colors.value, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(Res.string.boost_title, showTitle), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                if (item != null) stringResource(Res.string.boost_where, item.title, formatTime(positionMs)) else stringResource(Res.string.boost_whole_show),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )

            when (val s = state) {
                is BoostState.Done -> { BoostDone(s.results, onClose = { vm.resetBoost(); onDismiss() }); return@Column }
                is BoostState.Failed -> Text(stringResource(Res.string.boost_failed, s.reason), color = colors.error)
                else -> Unit
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { p -> Pill(formatSats(p), selected = !custom && amount == p) { custom = false; amount = p } }
                Pill("…", selected = custom) { custom = true }
            }
            if (custom) OutlinedTextField(
                if (amount > 0) amount.toString() else "", { t -> amount = t.filter(Char::isDigit).take(9).toLongOrNull() ?: 0 },
                Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_custom)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(message, { message = it.take(250) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_message)) })
            OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_name)) }, singleLine = true)

            when (val p = plan) {
                BoostPlan.NoValue -> Text(stringResource(Res.string.boost_no_value), color = colors.onSurfaceVariant)
                is BoostPlan.Ready -> SplitView(p.shares)
                null -> Unit
            }

            val ready = plan as? BoostPlan.Ready
            val nonePayable = ready != null && ready.shares.isNotEmpty() && ready.shares.all { it.unpayable != null }
            if (nonePayable && wallet != null) Text(stringResource(Res.string.boost_none_payable), color = colors.error)
            val canBoost = ready != null && amount > 0 && !nonePayable
            val over = amount * 1000 > left
            val w = wallet
            if (w == null) {
                BigButton(stringResource(Res.string.boost_no_wallet), enabled = true, onClick = onOpenWallet)
            } else {
                BigButton(
                    if (over && !overConfirmed) stringResource(Res.string.boost_send_over, formatSats(amount)) else stringResource(Res.string.boost_send, formatSats(amount)),
                    enabled = canBoost && state != BoostState.Sending,
                    busy = state == BoostState.Sending,
                ) {
                    if (over && !overConfirmed) overConfirmed = true
                    else {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (item != null) vm.send(item, position, amount, message, name) else vm.sendShow(podcastId, amount, message, name)
                    }
                }
                Text(
                    stringResource(Res.string.boost_via, w.label, formatSats(left / 1000)),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Hopp.colors.value else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    ) { Text(label, fontWeight = FontWeight.Bold, color = if (selected) Hopp.colors.onValue else MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50))
            .background(if (enabled) Hopp.colors.value else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Hopp.colors.onValue, strokeWidth = 2.dp)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HoppIcons.Hop, null, tint = if (enabled) Hopp.colors.onValue else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(label, fontWeight = FontWeight.Bold, color = if (enabled) Hopp.colors.onValue else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The compact split of ADR 0019: one bar, the largest four by name, the rest
 *  behind "+N more", and a line for what cannot be reached. */
@Composable
private fun SplitView(shares: List<PlannedShare>) {
    val colors = MaterialTheme.colorScheme
    val total = shares.sumOf { it.amountMsat }.coerceAtLeast(1)
    var all by remember { mutableStateOf(false) }
    val greys = listOf(colors.onSurface, colors.onSurface.copy(alpha = 0.7f), colors.onSurface.copy(alpha = 0.5f), colors.onSurface.copy(alpha = 0.35f))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.boost_split, shares.size), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            shares.forEachIndexed { i, s ->
                val color = if (s.unpayable != null) Hopp.colors.value.copy(alpha = 0.55f) else greys[i % greys.size]
                Box(Modifier.weight(s.amountMsat.toFloat().coerceAtLeast(1f)).height(10.dp).background(color))
                if (i < shares.lastIndex) Spacer(Modifier.size(2.dp).background(Color.Transparent))
            }
        }
        val sorted = shares.sortedByDescending { it.amountMsat }
        val shown = if (all) sorted else sorted.take(4)
        val line = shown.joinToString(" · ") { s -> "${s.recipient.name ?: s.recipient.address.take(12)} ${s.amountMsat * 100 / total} %" }
        Text(line, style = MaterialTheme.typography.bodySmall)
        if (sorted.size > 4) Text(
            if (all) stringResource(Res.string.boost_less) else stringResource(Res.string.boost_more, sorted.size - 4),
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { all = !all },
        )
        val unreachable = shares.filter { it.unpayable != null }
        if (unreachable.isNotEmpty()) {
            val why = unreachable.mapNotNull { it.unpayable }.distinct().map { reasonText(it) }.joinToString(", ")
            Text(stringResource(Res.string.boost_unreachable, unreachable.size) + " ($why)", style = MaterialTheme.typography.bodySmall, color = Hopp.colors.valueText)
        }
    }
}

/** A reason in the listener's words: the dialog's codes and the router's
 *  skip reasons are translated, anything else (a wallet's own error) shown
 *  as it came. */
@Composable
internal fun reasonText(reason: String): String = when (reason) {
    "keysend", SkipReason.KEYSEND -> stringResource(Res.string.reason_keysend)
    "remote", SkipReason.REMOTE -> stringResource(Res.string.reason_remote)
    "invoice", SkipReason.INVOICE -> stringResource(Res.string.reason_invoice)
    "self", SkipReason.SELF -> stringResource(Res.string.reason_self)
    SkipReason.DUST -> stringResource(Res.string.reason_dust)
    else -> reason
}

@Composable
private fun BoostDone(results: List<ShareResult>, onClose: () -> Unit) {
    val paid = results.filterIsInstance<ShareResult.Paid>()
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(HoppIcons.Hop, null, tint = Hopp.colors.value, modifier = Modifier.size(48.dp))
        Text(stringResource(Res.string.boost_done, formatSats(paid.sumOf { it.share.amountMsat } / 1000)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        // The recipients get the full amount; routing is paid on top (device feedback 2026-10-08).
        val fees = paid.sumOf { it.feesMsat ?: 0 }
        if (fees > 0) Text(stringResource(Res.string.boost_fees, formatMsat(fees)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (paid.size < results.size) {
            Text(stringResource(Res.string.boost_partial, paid.size, results.size), color = Hopp.colors.valueText)
            // Who was not paid, and why — the wallet's own words when it refused.
            results.filterNot { it is ShareResult.Paid }.forEach { r ->
                val why = when (r) {
                    is ShareResult.Skipped -> reasonText(r.reason)
                    is ShareResult.Failed -> reasonText(r.reason)
                    else -> ""
                }
                Text(stringResource(Res.string.boost_not_paid, r.share.recipient.name ?: r.share.recipient.address, why),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.padding(bottom = 16.dp)) { Text(stringResource(Res.string.boost_close)) }
    }
}

/** The orange Boost pill — on a show page for the whole show. */
@Composable
fun BoostPill(onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Hopp.colors.value).clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HoppIcons.Hop, null, tint = Hopp.colors.onValue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(stringResource(Res.string.np_boost), color = Hopp.colors.onValue, fontWeight = FontWeight.Bold)
    }
}
