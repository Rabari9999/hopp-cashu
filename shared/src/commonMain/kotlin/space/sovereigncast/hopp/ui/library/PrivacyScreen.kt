package space.sovereigncast.hopp.ui.library

import space.sovereigncast.hopp.resources.pv_backup_tip_ios
import space.sovereigncast.hopp.resources.pv_backup_when_ios
import space.sovereigncast.hopp.resources.pv_backup_who_ios
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.resources.*
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
import space.sovereigncast.hopp.resources.pv_zap_who
import space.sovereigncast.hopp.resources.pv_zap_sees
import space.sovereigncast.hopp.resources.pv_zap_when
import space.sovereigncast.hopp.resources.pv_zap_tip
import space.sovereigncast.hopp.resources.pv_zap_title
import space.sovereigncast.hopp.resources.pv_zap_line
import space.sovereigncast.hopp.resources.pv_sc_sees
import space.sovereigncast.hopp.resources.pv_nostr_sees
import space.sovereigncast.hopp.resources.pv_nostr_tip
import space.sovereigncast.hopp.resources.pv_nostr_when
import space.sovereigncast.hopp.resources.pv_nostr_who
import space.sovereigncast.hopp.resources.pv_sc_when
import space.sovereigncast.hopp.resources.pv_sc_who
import space.sovereigncast.hopp.resources.pv_wallet_sees
import space.sovereigncast.hopp.resources.pv_wallet_when
import space.sovereigncast.hopp.resources.pv_wallet_who

internal enum class Check { Done, Open, Info }

/** [title] and [line]: the row in the list; the rest opens on a tap. */
private class Party(
    val who: StringResource, val sees: StringResource, val whenSeen: StringResource, val tip: StringResource,
    val title: StringResource, val line: StringResource,
)

/** ADR 0015's table, in the order a listener meets them, each with what
 *  the listener can do about it (device feedback 2026-10-11). Nostr relays
 *  since the identity (ADR 0021); push joins when it arrives (phase 2). */
private val PARTIES = listOf(
    Party(Res.string.pv_hosts_who, Res.string.pv_hosts_sees, Res.string.pv_hosts_when, Res.string.pv_hosts_tip, Res.string.pv_hosts_title, Res.string.pv_hosts_line),
    Party(Res.string.pv_pi_who, Res.string.pv_pi_sees, Res.string.pv_pi_when, Res.string.pv_pi_tip, Res.string.pv_pi_who, Res.string.pv_pi_line),
    Party(Res.string.pv_directories_who, Res.string.pv_directories_sees, Res.string.pv_directories_when, Res.string.pv_directories_tip, Res.string.pv_directories_who, Res.string.pv_directories_line),
    Party(Res.string.pv_sc_who, Res.string.pv_sc_sees, Res.string.pv_sc_when, Res.string.pv_sc_tip, Res.string.pv_sc_title, Res.string.pv_sc_line),
    Party(Res.string.pv_wallet_who, Res.string.pv_wallet_sees, Res.string.pv_wallet_when, Res.string.pv_wallet_tip, Res.string.pv_wallet_title, Res.string.pv_wallet_line),
    Party(Res.string.pv_recipients_who, Res.string.pv_recipients_sees, Res.string.pv_recipients_when, Res.string.pv_recipients_tip, Res.string.pv_recipients_title, Res.string.pv_recipients_line),
    // Zaps on comments (device feedback 2026-10-19).
    Party(Res.string.pv_zap_who, Res.string.pv_zap_sees, Res.string.pv_zap_when, Res.string.pv_zap_tip, Res.string.pv_zap_title, Res.string.pv_zap_line),
    Party(Res.string.pv_nostr_who, Res.string.pv_nostr_sees, Res.string.pv_nostr_when, Res.string.pv_nostr_tip, Res.string.pv_nostr_title, Res.string.pv_nostr_line),
    Party(Res.string.pv_extra_who, Res.string.pv_extra_sees, Res.string.pv_extra_when, Res.string.pv_extra_tip, Res.string.pv_extra_title, Res.string.pv_extra_line),
    Party(Res.string.pv_share_who, Res.string.pv_share_sees, Res.string.pv_share_when, Res.string.pv_share_tip, Res.string.pv_share_title, Res.string.pv_share_line),
    Party(Res.string.pv_backup_who, Res.string.pv_backup_sees, Res.string.pv_backup_when, Res.string.pv_backup_tip, Res.string.pv_backup_who, Res.string.pv_backup_line),
)

/** The system backup is Apple's iCloud on iOS, not Google's (device feedback 2026-10-13).
 *  What it takes is the same: the database; the wallet connection is in the keychain,
 *  this device only, and downloads are in Caches, both outside the backup. */
private val BACKUP_IOS = Party(Res.string.pv_backup_who_ios, Res.string.pv_backup_sees, Res.string.pv_backup_when_ios, Res.string.pv_backup_tip_ios, Res.string.pv_backup_who_ios, Res.string.pv_backup_line)

private fun parties(ios: Boolean) = if (!ios) PARTIES else PARTIES.map { if (it.who == Res.string.pv_backup_who) BACKUP_IOS else it }

/**
 * Who sees what (ADR 0015). Device feedback 2026-10-16: a wall of cards, hard
 * to know where to look. Now: a line on what Hopp does; "your protection" —
 * what Hopp can check, green when done, a tap to where it is set; then who
 * sees what as a short list, one line each, the details (when, the tip, what
 * Hopp sees of it) in a sheet on a tap. Nothing is ever red: the page helps,
 * it does not scold.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onPage: (SettingsPage) -> Unit = {}, onWallet: () -> Unit = {}, vm: PrivacyViewModel = koinViewModel()) {
    val app: AppInfo = koinInject()
    val actions = LocalPlatformActions.current
    val colors = MaterialTheme.colorScheme
    val st by vm.state.collectAsState()
    var open by remember { mutableStateOf<Party?>(null) }
    var device by remember { mutableStateOf<StringResource?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.privacy_lead), style = MaterialTheme.typography.bodyLarge)
        val s = st
        // What Hopp can check, together, at the top.
        if (s != null) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val done = s.checks.count { it }
            val all = s.checks.isNotEmpty() && done == s.checks.size
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.privacy_protect_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (s.checks.isNotEmpty()) Text(
                    (if (all) "✓ " else "") + stringResource(Res.string.privacy_summary, done, s.checks.size),
                    style = MaterialTheme.typography.labelLarge, color = if (all) Hopp.colors.ok else colors.onSurfaceVariant,
                )
            }
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
            DirectoriesCheck(s, onPage)
            NameCheck(s, onWallet)
        }

        Text(stringResource(Res.string.privacy_who_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))) {
            parties(app.isIos).forEachIndexed { i, p ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = colors.outlineVariant)
                ListRow(stringResource(p.title), stringResource(p.line)) { open = p }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))) {
            ListRow(stringResource(Res.string.privacy_ua_title), app.userAgent) { device = Res.string.privacy_ua_title }
            androidx.compose.material3.HorizontalDivider(color = colors.outlineVariant)
            ListRow(stringResource(Res.string.privacy_backup_title), stringResource(Res.string.privacy_device_line)) { device = Res.string.privacy_backup_title }
        }
    }

    open?.let { p ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { open = null }) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(p.who), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Detail(Res.string.privacy_sees, stringResource(p.sees))
                    Detail(Res.string.privacy_when, stringResource(p.whenSeen))
                    Detail(Res.string.privacy_tip_label, stringResource(p.tip))
                    st?.let { PartyCheck(p, it, onPage, onWallet) }
                }
            }
        }
    }
    device?.let { which ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { device = null }) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(which), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (which == Res.string.privacy_ua_title) {
                        Text(stringResource(Res.string.privacy_ua, app.userAgent), style = MaterialTheme.typography.bodyMedium)
                        CheckLine(Check.Done, stringResource(Res.string.privacy_ua_ok))
                    } else Text(stringResource(Res.string.privacy_backup), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** One row of a list: what, in a line, and › — a tap opens the details. */
@Composable
private fun ListRow(title: String, line: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Detail(label: StringResource, text: String) = Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DirectoriesCheck(s: PrivacyState, onPage: (SettingsPage) -> Unit) {
    if (s.directoriesOff) CheckLine(Check.Done, stringResource(Res.string.privacy_dirs_off)) { onPage(SettingsPage.Directories) }
    else {
        val on = listOfNotNull("fyyd".takeIf { s.settings.fyydSearch }, "Apple Podcasts".takeIf { s.settings.appleSearch })
        val names = if (on.size == 2) stringResource(Res.string.privacy_and, on[0], on[1]) else on.single()
        CheckLine(Check.Open, stringResource(Res.string.privacy_dirs_on, names)) { onPage(SettingsPage.Directories) }
    }
}

@Composable
private fun NameCheck(s: PrivacyState, onWallet: () -> Unit) {
    if (s.anonymous) CheckLine(Check.Done, stringResource(Res.string.privacy_name_empty))
    else CheckLine(Check.Open, stringResource(Res.string.privacy_name_set, s.settings.senderName)) { onWallet() }
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
        Res.string.pv_directories_who -> DirectoriesCheck(s, onPage)
        Res.string.pv_sc_who, Res.string.pv_recipients_who -> NameCheck(s, onWallet)
        Res.string.pv_wallet_who -> s.relay?.let { CheckLine(Check.Info, stringResource(Res.string.privacy_relay, it)) }
        // The tip there is a VPN (device feedback 2026-10-11).
        Res.string.pv_extra_who, Res.string.pv_nostr_who -> when (s.vpn) {
            true -> CheckLine(Check.Done, stringResource(Res.string.privacy_vpn_on))
            false -> CheckLine(Check.Open, stringResource(Res.string.privacy_vpn_off)) { actions.openVpnSettings() }
            null -> Unit
        }
        else -> Unit
    }
}

/** ✓ green when done, ○ when there is something to do, plain otherwise; › when a tap leads there. */
@Composable
internal fun CheckLine(check: Check, text: String, onClick: (() -> Unit)? = null) {
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
