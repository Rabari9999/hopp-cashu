package space.sovereigncast.hopp.ui.value

import space.sovereigncast.hopp.resources.wallet_browser_intro
import space.sovereigncast.hopp.resources.wallet_browser_connect
import space.sovereigncast.hopp.resources.wallet_browser_none
import space.sovereigncast.hopp.resources.wallet_browser_cannot_pay
import space.sovereigncast.hopp.resources.wallet_browser_budget_tip
import space.sovereigncast.hopp.resources.wallet_silent
import space.sovereigncast.hopp.resources.wallet_yes
import space.sovereigncast.hopp.resources.wallet_no
import space.sovereigncast.hopp.resources.wallet_retry
import space.sovereigncast.hopp.resources.wallet_reach_title
import space.sovereigncast.hopp.resources.wallet_reach_address
import space.sovereigncast.hopp.resources.wallet_reach_address_note
import space.sovereigncast.hopp.resources.wallet_reach_address_keysend
import space.sovereigncast.hopp.resources.wallet_reach_node
import space.sovereigncast.hopp.resources.wallet_reach_node_note
import space.sovereigncast.hopp.resources.wallet_reach_node_off
import space.sovereigncast.hopp.resources.wallet_reach_keysend_wallets
import space.sovereigncast.hopp.resources.history_unknown_note
import space.sovereigncast.hopp.resources.history_all_paid
import space.sovereigncast.hopp.resources.history_sum_paid
import space.sovereigncast.hopp.resources.history_sum_unknown
import space.sovereigncast.hopp.resources.history_sum_skipped
import space.sovereigncast.hopp.resources.history_sum_failed
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.wallet_own_set
import space.sovereigncast.hopp.resources.wallet_own_range
import space.sovereigncast.hopp.resources.wallet_own_value
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import space.sovereigncast.hopp.value.ConnectedWallet
import space.sovereigncast.hopp.value.PaymentLine
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.wallet_not_here
import space.sovereigncast.hopp.resources.history_boost
import space.sovereigncast.hopp.resources.history_empty
import space.sovereigncast.hopp.resources.history_empty_text
import space.sovereigncast.hopp.resources.history_failed
import space.sovereigncast.hopp.resources.history_fee
import space.sovereigncast.hopp.resources.history_paid
import space.sovereigncast.hopp.resources.history_skipped
import space.sovereigncast.hopp.resources.history_unknown
import space.sovereigncast.hopp.resources.history_stream
import space.sovereigncast.hopp.resources.history_zap
import space.sovereigncast.hopp.resources.sc_b2u_name
import space.sovereigncast.hopp.resources.sc_suggestion_name
import space.sovereigncast.hopp.resources.sc_rb_name
import space.sovereigncast.hopp.resources.wallet_balance
import space.sovereigncast.hopp.resources.wallet_balance_unknown
import space.sovereigncast.hopp.resources.wallet_budget
import space.sovereigncast.hopp.resources.wallet_connect
import space.sovereigncast.hopp.resources.wallet_scan
import space.sovereigncast.hopp.resources.wallet_scanned
import space.sovereigncast.hopp.resources.wallet_connected
import space.sovereigncast.hopp.resources.wallet_disconnect
import space.sovereigncast.hopp.resources.wallet_failed
import space.sovereigncast.hopp.resources.wallet_history
import space.sovereigncast.hopp.resources.wallet_intro
import space.sovereigncast.hopp.resources.wallet_cannot_pay
import space.sovereigncast.hopp.resources.wallet_limit_tip
import space.sovereigncast.hopp.resources.wallet_name
import space.sovereigncast.hopp.resources.wallet_quick_boost
import space.sovereigncast.hopp.resources.wallet_quick_boost_note
import space.sovereigncast.hopp.resources.wallet_quick_boost_off
import space.sovereigncast.hopp.resources.wallet_rate
import space.sovereigncast.hopp.resources.wallet_rate_show
import space.sovereigncast.hopp.resources.wallet_rate_value
import space.sovereigncast.hopp.resources.wallet_spent_today
import space.sovereigncast.hopp.resources.wallet_support
import space.sovereigncast.hopp.resources.wallet_support_note
import space.sovereigncast.hopp.resources.secrets_unreadable
import space.sovereigncast.hopp.resources.wallet_uri_hint
import space.sovereigncast.hopp.resources.wallet_no_nip44
import space.sovereigncast.hopp.value.WalletRepository
import space.sovereigncast.hopp.resources.wallet_uri_hide
import space.sovereigncast.hopp.resources.wallet_uri_show
import space.sovereigncast.hopp.ui.library.EmptyState
import space.sovereigncast.hopp.ui.components.formatDateTime
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.theme.Hopp
import space.sovereigncast.hopp.value.Payment
import space.sovereigncast.hopp.value.ValueService

/**
 * The wallet (ADR 0005): connect over NWC by pasting the connection string
 * (or opening a `nostr+walletconnect:` link), what the wallet can, the daily
 * budget, the streaming rate, the name sent with boosts.
 */
/** Where Hopp does not pay (the browser for now, [space.sovereigncast.hopp.ui.PlatformActions.canPay]):
 *  what is left of the wallet page, for a link that still leads here. */
@Composable
fun WalletNotHere() {
    Text(
        stringResource(Res.string.wallet_not_here), Modifier.padding(24.dp),
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WalletScreen(vm: WalletViewModel = koinViewModel()) {
    val wallet by vm.connected.collectAsState()
    val settings by vm.current.collectAsState()
    val balance by vm.balanceMsat.collectAsState()
    val connect by vm.connect.collectAsState()
    val spent by vm.spentToday.collectAsState()
    val unreadable by vm.unreadable.collectAsState()
    val colors = MaterialTheme.colorScheme
    // The connection string can spend: plain remember, not saveable — it never
    // goes into the saved state — masked unless asked, cleared once connected,
    // and the page kept out of screenshots (security review 2026-10-10, M-01).
    var uri by remember { mutableStateOf(space.sovereigncast.hopp.ui.IncomingLinks.takeWalletDraft().orEmpty()) }
    var showUri by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    LaunchedEffect(wallet) { if (wallet != null) uri = "" }
    val actions = space.sovereigncast.hopp.ui.LocalPlatformActions.current
    androidx.compose.runtime.DisposableEffect(Unit) { actions.secureScreen(true); onDispose { actions.secureScreen(false) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val w = wallet
        if (w == null && unreadable) {
            // There is a connection, unreadable for now: nothing to connect over it (security review 2026-10-20).
            Text(stringResource(Res.string.secrets_unreadable), color = colors.error)
            OutlinedButton(onClick = vm::disconnect) { Text(stringResource(Res.string.wallet_disconnect)) }
        } else if (w == null && !vm.nwcHere) {
            // The browser: its own wallet, an extension's (WebLN — ADR 0022, 4, stage W3).
            Text(stringResource(Res.string.wallet_browser_intro))
            if (vm.browserWallet) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::connectBrowser, enabled = connect != ConnectState.Working) { Text(stringResource(Res.string.wallet_browser_connect)) }
                if (connect == ConnectState.Working) CircularProgressIndicator(Modifier.padding(4.dp))
            } else Text(stringResource(Res.string.wallet_browser_none), color = colors.onSurfaceVariant)
            (connect as? ConnectState.Failed)?.let {
                Text(if (it.code == WalletRepository.CANNOT_PAY) stringResource(Res.string.wallet_browser_cannot_pay) else stringResource(Res.string.wallet_failed, it.reason), color = colors.error)
            }
            Text(stringResource(Res.string.wallet_browser_budget_tip), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else if (w == null) {
            Text(stringResource(Res.string.wallet_intro))
            OutlinedTextField(uri, { uri = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.wallet_uri_hint)) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showUri) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    if (uri.isNotEmpty()) TextButton(onClick = { showUri = !showUri }) {
                        Text(stringResource(if (showUri) Res.string.wallet_uri_hide else Res.string.wallet_uri_show))
                    }
                })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.connect(uri) }, enabled = uri.isNotBlank() && connect != ConnectState.Working) { Text(stringResource(Res.string.wallet_connect)) }
                // The wallet's QR code instead of copying (device feedback 2026-10-14):
                // read, it connects as a pasted string does.
                if (actions.canScanQr) OutlinedButton(onClick = { actions.scanQr { text -> uri = text.trim(); scanned = true; vm.connect(uri) } }, enabled = connect != ConnectState.Working) {
                    Text(stringResource(Res.string.wallet_scan))
                }
                if (connect == ConnectState.Working) CircularProgressIndicator(Modifier.padding(4.dp))
            }
            // What happened to the picture, said where it matters (device feedback 2026-10-14).
            if (scanned) Text(stringResource(Res.string.wallet_scanned), color = Hopp.colors.valueText, style = MaterialTheme.typography.bodyMedium)
            (connect as? ConnectState.Failed)?.let {
                Text(if (it.code == WalletRepository.UNSUPPORTED_ENCRYPTION) stringResource(Res.string.wallet_no_nip44) else stringResource(Res.string.wallet_failed, it.reason), color = colors.error)
            }
            Text(stringResource(Res.string.wallet_limit_tip), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(Res.string.wallet_connected, w.label), style = MaterialTheme.typography.titleMedium)
            val silent by vm.silent.collectAsState()
            if (silent) {
                // No answer at all (device feedback 2026-10-26: coinos with its app closed).
                Text(stringResource(Res.string.wallet_silent), color = colors.error)
                val checking by vm.checking.collectAsState()
                Retry(checking, vm::refreshBalance)
            } else Text(
                balance?.let { stringResource(Res.string.wallet_balance, formatSats(it / 1000)) } ?: stringResource(Res.string.wallet_balance_unknown),
                color = colors.onSurfaceVariant,
            )
            WalletReach(w)
            OutlinedButton(onClick = vm::disconnect) { Text(stringResource(Res.string.wallet_disconnect)) }
        }

        // Budget, rate and name mean something only with a wallet.
        settings?.takeIf { wallet != null }?.let { s ->
            Text(stringResource(Res.string.wallet_budget), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            ChoicesOrOwn(Settings.budgetChoicesSats, s.dailyBudgetSats, 1..10_000_000, stringResource(Res.string.wallet_budget), "sats",
                { formatSats(it.toLong()) }) { v -> vm.update { it.copy(dailyBudgetSats = v) } }
            Text(stringResource(Res.string.wallet_spent_today, formatSats(spent / 1000), formatSats(s.dailyBudgetSats.toLong())),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

            Text(stringResource(Res.string.wallet_rate), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            val rateLabel: @Composable (Int) -> String = { r -> if (r == 0) stringResource(Res.string.wallet_rate_show) else stringResource(Res.string.wallet_rate_value, r) }
            ChoicesOrOwn(Settings.streamRateChoicesSats, s.streamRateSats, 1..10_000, stringResource(Res.string.wallet_rate), "sats/min", rateLabel) { v ->
                vm.update { it.copy(streamRateSats = v) }
            }

            Text(stringResource(Res.string.wallet_quick_boost), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            val quickLabel: @Composable (Int) -> String = { q -> if (q == 0) stringResource(Res.string.wallet_quick_boost_off) else formatSats(q.toLong()) }
            ChoicesOrOwn(Settings.quickBoostChoicesSats, s.quickBoostSats, 1..1_000_000, stringResource(Res.string.wallet_quick_boost), "sats", quickLabel) { v ->
                vm.update { it.copy(quickBoostSats = v) }
            }
            Text(stringResource(Res.string.wallet_quick_boost_note), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

            if (space.sovereigncast.hopp.storePaysForContent()) {
                Text(stringResource(Res.string.wallet_support), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                val supportLabel: @Composable (Int) -> String = { p -> if (p == 0) stringResource(Res.string.wallet_quick_boost_off) else "$p %" }
                ChoicesOrOwn(Settings.supportChoices, s.supportHoppPercent, 1..Settings.MAX_SUPPORT_PERCENT, stringResource(Res.string.wallet_support), "%", supportLabel) { v ->
                    vm.update { it.copy(supportHoppPercent = v) }
                }
                Text(stringResource(Res.string.wallet_support_note, Settings.SUPPORT_ADDRESS), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }

            var name by remember(s.senderName) { mutableStateOf(s.senderName) }
            OutlinedTextField(
                name, { name = it; vm.update { st -> st.copy(senderName = it.trim()) } }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(stringResource(Res.string.wallet_name)) }, singleLine = true,
            )
        }
    }
}

/** Every boost and streaming payment, with who got what (ADR 0013's boost log). */
@Composable
fun HistoryScreen(vm: HistoryViewModel = koinViewModel()) {
    val payments by vm.payments.collectAsState()
    val list = payments ?: return
    if (list.isEmpty()) {
        EmptyState(stringResource(Res.string.history_empty), stringResource(Res.string.history_empty_text))
        return
    }
    LazyColumn {
        items(list, key = { it.id }) { PaymentRow(it) }
    }
}

@Composable
private fun PaymentRow(p: Payment) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val kind = when (p.kind) {
                    ValueService.KIND_STREAM -> Res.string.history_stream
                    ValueService.KIND_RELEASE_BOOSTING -> Res.string.sc_rb_name
                    ValueService.KIND_BOOST2UNLOCK -> Res.string.sc_b2u_name
                    ValueService.KIND_SUGGESTION -> Res.string.sc_suggestion_name
                    ValueService.KIND_ZAP -> Res.string.history_zap
                    else -> Res.string.history_boost
                }
                // Date and time (device feedback 2026-10-09).
                Text(
                    stringResource(kind) + " · " + formatDateTime(p.createdAt),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                )
                Text(listOfNotNull(p.podcastTitle, p.episodeTitle).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                p.message?.takeIf { it.isNotBlank() }?.let { Text("„$it“", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
            // What was to go out, not only what is sure to have gone (device feedback 2026-10-26: a
            // round to coinos read "0 sats" — two shares unknown, one skipped).
            Text(formatSats((if (p.lines.isEmpty()) p.amountMsat else p.lines.sumOf { it.msat }) / 1000) + " sats", color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
        }
        if (p.lines.isNotEmpty()) Outcome(p.lines)
        if (open && p.lines.any { it.status == "unknown" }) Text(
            stringResource(Res.string.history_unknown_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh).padding(10.dp),
        )
        if (open) p.lines.forEach { l ->
            val status = when (l.status) {
                "paid" -> stringResource(Res.string.history_paid)
                "skipped" -> stringResource(Res.string.history_skipped)
                "unknown" -> stringResource(Res.string.history_unknown)
                else -> stringResource(Res.string.history_failed)
            }
            Text(
                "${l.name ?: l.address.take(16)} · ${formatSats(l.msat / 1000)} sats · $status" +
                    (l.feesMsat?.takeIf { it > 0 }?.let { " · " + feeText(it) } ?: "") +
                    (l.reason?.let { " (${reasonText(it)})" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * What the connection reaches, in plain words (device feedback 2026-10-26: "keysend: no" said
 * nothing about what that means for a boost or for streaming). The wallet decides what a
 * connection may do.
 */
@Composable
private fun WalletReach(w: ConnectedWallet) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(Res.string.wallet_reach_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Capability(
            stringResource(Res.string.wallet_reach_address), w.canPayInvoice || w.canKeysend,
            stringResource(if (w.canPayInvoice) Res.string.wallet_reach_address_note else Res.string.wallet_reach_address_keysend),
        )
        Capability(
            stringResource(Res.string.wallet_reach_node), w.canKeysend,
            stringResource(if (w.canKeysend) Res.string.wallet_reach_node_note else Res.string.wallet_reach_node_off),
        )
        if (!w.canPayInvoice && !w.canKeysend) Text(stringResource(Res.string.wallet_cannot_pay), color = colors.error)
        if (!w.canKeysend) Text(stringResource(Res.string.wallet_reach_keysend_wallets), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** "Try again", and while the wallet is asked a small spinner in its place (device feedback 2026-10-26:
 *  a tap seemed to do nothing). */
@Composable
internal fun Retry(checking: Boolean, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onClick, enabled = !checking, contentPadding = PaddingValues(0.dp)) { Text(stringResource(Res.string.wallet_retry), fontWeight = FontWeight.Bold) }
        if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Capability(label: String, yes: Boolean, note: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tint = if (yes) Hopp.colors.ok else colors.error
        Box(Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(if (yes) "✓" else "✗", color = tint, fontWeight = FontWeight.Bold)
        }
        val state = stringResource(if (yes) Res.string.wallet_yes else Res.string.wallet_no)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { stateDescription = state }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** A payment's outcome at a glance: how many sats went, are unknown, were skipped or failed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Outcome(lines: List<PaymentLine>) {
    val colors = MaterialTheme.colorScheme
    val by = lines.groupBy { it.status }.mapValues { (_, l) -> l.sumOf { it.msat } / 1000 }
    @Composable fun chip(text: String, background: androidx.compose.ui.graphics.Color) =
        Text(text, Modifier.clip(RoundedCornerShape(12.dp)).background(background).padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ok = Hopp.colors.ok.copy(alpha = 0.14f)
        if (by.keys == setOf("paid")) chip(stringResource(Res.string.history_all_paid), ok)
        else {
            by["paid"]?.let { chip(stringResource(Res.string.history_sum_paid, formatSats(it)), ok) }
            by["unknown"]?.let { chip(stringResource(Res.string.history_sum_unknown, formatSats(it)), Hopp.colors.value.copy(alpha = 0.16f)) }
            by["skipped"]?.let { chip(stringResource(Res.string.history_sum_skipped, formatSats(it)), colors.surfaceVariant) }
            by.filterKeys { it !in setOf("paid", "unknown", "skipped") }.values.sum().takeIf { it > 0 }
                ?.let { chip(stringResource(Res.string.history_sum_failed, formatSats(it)), colors.errorContainer) }
        }
    }
}

@Composable
private fun feeText(msat: Long) = stringResource(Res.string.history_fee, space.sovereigncast.hopp.ui.components.formatMsat(msat))

/**
 * The usual choices, and one of the listener's own (device feedback
 * 2026-10-19): "Own value…" asks for a whole number in [range]; once set it
 * stands as its own chip, chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoicesOrOwn(
    options: List<Int>, value: Int, range: IntRange, title: String, unit: String,
    label: @Composable (Int) -> String, onPick: (Int) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = value == o, onClick = { onPick(o) }, label = { Text(label(o)) }) }
        val own = value !in options
        FilterChip(selected = own, onClick = { asking = true },
            label = { Text(if (own) label(value) + " ✎" else stringResource(Res.string.wallet_own_value)) })
    }
    if (asking) {
        var text by remember { mutableStateOf(value.takeIf { it !in options }?.toString().orEmpty()) }
        val number = text.trim().toIntOrNull()
        val ok = number != null && number in range
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    text, { t -> text = t.filter(Char::isDigit).take(8) }, singleLine = true,
                    label = { Text(unit) }, isError = text.isNotEmpty() && !ok,
                    supportingText = { Text(stringResource(Res.string.wallet_own_range, range.first, range.last, unit)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            },
            confirmButton = { TextButton(onClick = { number?.let(onPick); asking = false }, enabled = ok) { Text(stringResource(Res.string.wallet_own_set)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
