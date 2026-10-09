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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
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
import space.sovereigncast.hopp.sovereigncast.Boost2UnlockEpisode
import space.sovereigncast.hopp.sovereigncast.ReleaseBoostingRound
import space.sovereigncast.hopp.sovereigncast.ScTarget
import space.sovereigncast.hopp.sovereigncast.Suggestion
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.theme.Hopp
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** "✓ Sovereigncast" — the show was verified with its instance (ADR 0002). */
@Composable
fun ScBadge() {
    Text(
        "✓ " + stringResource(Res.string.sc_badge),
        Modifier.clip(RoundedCornerShape(50)).border(1.dp, Hopp.colors.value, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold,
    )
}

@OptIn(ExperimentalTime::class)
private fun localTime(iso: String): String = runCatching {
    val t = Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
    "${t.day.toString().padStart(2, '0')}.${t.month.number.toString().padStart(2, '0')}. ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}.getOrDefault(iso)

@Composable
private fun ActionCard(label: String, title: String, raised: Long, goal: Long, lines: List<String>, action: String?, onAction: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(colors.surface).border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { if (goal > 0) (raised.toFloat() / goal).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Hopp.colors.value, trackColor = Hopp.colors.progressTrack, drawStopIndicator = {},
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
            if (action != null) Row(
                Modifier.clip(RoundedCornerShape(50)).background(Hopp.colors.value).clickable(onClick = onAction).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HoppIcons.Hop, null, tint = Hopp.colors.onValue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(action, color = Hopp.colors.onValue, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The running Release Boosting round (ADR 0019's Sovereigncast card). */
@Composable
fun ReleaseBoostingCard(round: ReleaseBoostingRound, onBoost: () -> Unit) = ActionCard(
    label = stringResource(Res.string.sc_rb_label),
    title = round.episode.title,
    raised = round.raisedSats, goal = round.goalSats,
    lines = listOf(
        stringResource(Res.string.sc_progress, formatSats(round.raisedSats), formatSats(round.goalSats)) + " · " +
            stringResource(Res.string.sc_publish_at, localTime(round.publishAt)),
        stringResource(Res.string.sc_per_minute, round.satsPerMinute),
    ),
    action = stringResource(Res.string.np_boost), onAction = onBoost,
)

/** One episode waiting to be unlocked by boosts. */
@Composable
fun UnlockCard(item: Boost2UnlockEpisode, onUnlock: () -> Unit) = ActionCard(
    label = stringResource(Res.string.sc_b2u_label),
    title = item.episode.title,
    raised = item.raisedSats, goal = item.goalSats,
    lines = listOf(
        if (item.unlocked) stringResource(Res.string.sc_unlocked)
        else stringResource(Res.string.sc_progress, formatSats(item.raisedSats), formatSats(item.goalSats)),
    ),
    action = if (item.unlocked) null else stringResource(Res.string.sc_unlock), onAction = onUnlock,
)

/** An open suggestion: votes are free, one per listener (ADR 0018). */
@Composable
fun SuggestionRow(s: Suggestion, voted: Boolean, onVote: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(Res.string.sc_vote)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (s.description.isNotBlank()) Text(s.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row {
                Text(stringResource(Res.string.sc_votes, s.voteCount), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                if (s.amountSats > 0) Text(" · ${formatSats(s.amountSats)} sats", style = MaterialTheme.typography.labelMedium, color = Hopp.colors.valueText)
            }
        }
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .then(if (voted) Modifier.background(colors.onSurface) else Modifier.border(1.dp, colors.outline, RoundedCornerShape(50)))
                .clickable(enabled = !voted, onClickLabel = label, onClick = onVote).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("▲ ${s.voteCount}", fontWeight = FontWeight.Bold, color = if (voted) colors.surface else colors.onSurface)
        }
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
            OutlinedTextField(message, { message = it.take(250) }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.boost_message)) })
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
