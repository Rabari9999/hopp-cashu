package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.theme.Hopp
import space.sovereigncast.hopp.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.privacy_backup
import space.sovereigncast.hopp.resources.privacy_backup_title
import space.sovereigncast.hopp.resources.privacy_intro
import space.sovereigncast.hopp.resources.privacy_sees
import space.sovereigncast.hopp.resources.privacy_ua
import space.sovereigncast.hopp.resources.privacy_ua_title
import space.sovereigncast.hopp.resources.privacy_when
import space.sovereigncast.hopp.resources.privacy_who_title
import space.sovereigncast.hopp.resources.pv_backup_sees
import space.sovereigncast.hopp.resources.pv_backup_when
import space.sovereigncast.hopp.resources.pv_backup_who
import space.sovereigncast.hopp.resources.pv_directories_sees
import space.sovereigncast.hopp.resources.pv_directories_when
import space.sovereigncast.hopp.resources.pv_directories_who
import space.sovereigncast.hopp.resources.pv_extra_sees
import space.sovereigncast.hopp.resources.pv_extra_when
import space.sovereigncast.hopp.resources.pv_extra_who
import space.sovereigncast.hopp.resources.pv_hosts_sees
import space.sovereigncast.hopp.resources.pv_hosts_when
import space.sovereigncast.hopp.resources.pv_hosts_who
import space.sovereigncast.hopp.resources.pv_pi_sees
import space.sovereigncast.hopp.resources.pv_pi_when
import space.sovereigncast.hopp.resources.pv_pi_who
import space.sovereigncast.hopp.resources.pv_recipients_sees
import space.sovereigncast.hopp.resources.pv_recipients_when
import space.sovereigncast.hopp.resources.pv_recipients_who
import space.sovereigncast.hopp.resources.pv_sc_sees
import space.sovereigncast.hopp.resources.pv_sc_when
import space.sovereigncast.hopp.resources.pv_sc_who
import space.sovereigncast.hopp.resources.pv_wallet_sees
import space.sovereigncast.hopp.resources.pv_wallet_when
import space.sovereigncast.hopp.resources.pv_wallet_who

private enum class Check { Done, Open, Info }

private class Party(val who: StringResource, val sees: StringResource, val whenSeen: StringResource, val tip: StringResource)

/** ADR 0015's table, in the order a listener meets them, each with what
 *  the listener can do about it (device feedback 2026-10-11). Push and
 *  Nostr join it when those features arrive (phase 2). */
private val PARTIES = listOf(
    Party(Res.string.pv_hosts_who, Res.string.pv_hosts_sees, Res.string.pv_hosts_when, Res.string.pv_hosts_tip),
    Party(Res.string.pv_pi_who, Res.string.pv_pi_sees, Res.string.pv_pi_when, Res.string.pv_pi_tip),
    Party(Res.string.pv_directories_who, Res.string.pv_directories_sees, Res.string.pv_directories_when, Res.string.pv_directories_tip),
    Party(Res.string.pv_sc_who, Res.string.pv_sc_sees, Res.string.pv_sc_when, Res.string.pv_sc_tip),
    Party(Res.string.pv_wallet_who, Res.string.pv_wallet_sees, Res.string.pv_wallet_when, Res.string.pv_wallet_tip),
    Party(Res.string.pv_recipients_who, Res.string.pv_recipients_sees, Res.string.pv_recipients_when, Res.string.pv_recipients_tip),
    Party(Res.string.pv_extra_who, Res.string.pv_extra_sees, Res.string.pv_extra_when, Res.string.pv_extra_tip),
    Party(Res.string.pv_share_who, Res.string.pv_share_sees, Res.string.pv_share_when, Res.string.pv_share_tip),
    Party(Res.string.pv_backup_who, Res.string.pv_backup_sees, Res.string.pv_backup_when, Res.string.pv_backup_tip),
)

/**
 * Who sees what (ADR 0015): Hopp itself collects nothing, so the page is
 * about everyone else the app talks to — plainly, one card each, with a
 * tip — the User-Agent it sends, and what the system backup takes along.
 * Where Hopp can see a tip is followed (VPN, private DNS, its own
 * settings), the card says so in green; a tap leads to where it is set.
 * Nothing is ever red: the page helps, it does not scold.
 */
@Composable
fun PrivacyScreen(onPage: (SettingsPage) -> Unit = {}, onWallet: () -> Unit = {}, vm: PrivacyViewModel = koinViewModel()) {
    val app: AppInfo = koinInject()
    val actions = LocalPlatformActions.current
    val colors = MaterialTheme.colorScheme
    val st by vm.state.collectAsState()
    // All of it selectable: the User-Agent, a host, a tip (device feedback 2026-10-12).
    SelectionContainer { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.privacy_intro), style = MaterialTheme.typography.bodyLarge)
        val s = st
        if (s != null && s.checks.isNotEmpty()) {
            val done = s.checks.count { it }
            val all = done == s.checks.size
            Text(
                (if (all) "✓  " else "") + stringResource(Res.string.privacy_summary, done, s.checks.size),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall, color = if (all) Hopp.colors.ok else colors.onSurfaceVariant,
            )
        }

        Text(stringResource(Res.string.privacy_ip_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(Res.string.privacy_ip), style = MaterialTheme.typography.bodyMedium)
        if (s != null) {
            when (s.vpn) {
                true -> CheckLine(Check.Done, stringResource(Res.string.privacy_vpn_on))
                false -> CheckLine(Check.Open, stringResource(Res.string.privacy_vpn_off)) { actions.openVpnSettings() }
                null -> Unit
            }
            when {
                s.net.vpn == true -> CheckLine(Check.Done, stringResource(Res.string.privacy_dns_vpn))
                s.dns == true -> CheckLine(Check.Done, stringResource(Res.string.privacy_dns_on))
                s.dns == false -> CheckLine(Check.Open, stringResource(Res.string.privacy_dns_off)) { actions.openNetworkSettings() }
            }
        }

        Text(stringResource(Res.string.privacy_who_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        PARTIES.forEach { p ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(p.who), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.privacy_sees, stringResource(p.sees)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(Res.string.privacy_when, stringResource(p.whenSeen)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                val label = stringResource(Res.string.privacy_tip)
                val tip = stringResource(p.tip)
                Text(
                    buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }; append(" "); append(tip) },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
                if (s != null) PartyCheck(p, s, onPage, onWallet)
            }
        }
        Text(stringResource(Res.string.privacy_ua_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(Res.string.privacy_ua, app.userAgent), style = MaterialTheme.typography.bodyMedium)
        CheckLine(Check.Done, stringResource(Res.string.privacy_ua_ok))
        Text(stringResource(Res.string.privacy_backup_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(Res.string.privacy_backup), style = MaterialTheme.typography.bodyMedium)
    } }
}

/** What Hopp can tell about one card's tip, if anything. */
@Composable
private fun PartyCheck(p: Party, s: PrivacyState, onPage: (SettingsPage) -> Unit, onWallet: () -> Unit) {
    val actions = LocalPlatformActions.current
    when (p.who) {
        Res.string.pv_hosts_who -> CheckLine(Check.Info, when (val h = s.settings.refreshHours) {
            0 -> stringResource(Res.string.privacy_refresh_off)
            1 -> stringResource(Res.string.privacy_refresh_hourly)
            else -> stringResource(Res.string.privacy_refresh_hours, h)
        }) { onPage(SettingsPage.Downloads) }
        Res.string.pv_directories_who ->
            if (s.directoriesOff) CheckLine(Check.Done, stringResource(Res.string.privacy_dirs_off)) { onPage(SettingsPage.Directories) }
            else {
                val on = listOfNotNull("fyyd".takeIf { s.settings.fyydSearch }, "Apple Podcasts".takeIf { s.settings.appleSearch })
                val names = if (on.size == 2) stringResource(Res.string.privacy_and, on[0], on[1]) else on.single()
                CheckLine(Check.Open, stringResource(Res.string.privacy_dirs_on, names)) { onPage(SettingsPage.Directories) }
            }
        Res.string.pv_sc_who, Res.string.pv_recipients_who ->
            if (s.anonymous) CheckLine(Check.Done, stringResource(Res.string.privacy_name_empty))
            else CheckLine(Check.Open, stringResource(Res.string.privacy_name_set, s.settings.senderName)) { onWallet() }
        Res.string.pv_wallet_who -> s.relay?.let { CheckLine(Check.Info, stringResource(Res.string.privacy_relay, it)) }
        // The tip there is a VPN (device feedback 2026-10-11).
        Res.string.pv_extra_who -> when (s.vpn) {
            true -> CheckLine(Check.Done, stringResource(Res.string.privacy_vpn_on))
            false -> CheckLine(Check.Open, stringResource(Res.string.privacy_vpn_off)) { actions.openVpnSettings() }
            null -> Unit
        }
        else -> Unit
    }
}

/** ✓ green when done, ○ when there is something to do, plain otherwise; › when a tap leads there. */
@Composable
private fun CheckLine(check: Check, text: String, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val tint = when (check) { Check.Done -> Hopp.colors.ok; Check.Open -> colors.onSurface; Check.Info -> colors.onSurfaceVariant }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(when (check) { Check.Done -> "✓"; Check.Open -> "○"; Check.Info -> "·" }, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(text, Modifier.weight(1f), color = tint, style = MaterialTheme.typography.bodyMedium, fontWeight = if (check == Check.Done) FontWeight.Medium else null)
        if (onClick != null) Text("›", color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
    }
}
