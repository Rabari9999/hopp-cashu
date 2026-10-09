package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.border
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

private class Party(val who: StringResource, val sees: StringResource, val whenSeen: StringResource)

/** ADR 0015's table, in the order a listener meets them. Push and Nostr join
 *  it when those features arrive (phase 2). */
private val PARTIES = listOf(
    Party(Res.string.pv_hosts_who, Res.string.pv_hosts_sees, Res.string.pv_hosts_when),
    Party(Res.string.pv_pi_who, Res.string.pv_pi_sees, Res.string.pv_pi_when),
    Party(Res.string.pv_directories_who, Res.string.pv_directories_sees, Res.string.pv_directories_when),
    Party(Res.string.pv_sc_who, Res.string.pv_sc_sees, Res.string.pv_sc_when),
    Party(Res.string.pv_wallet_who, Res.string.pv_wallet_sees, Res.string.pv_wallet_when),
    Party(Res.string.pv_recipients_who, Res.string.pv_recipients_sees, Res.string.pv_recipients_when),
    Party(Res.string.pv_extra_who, Res.string.pv_extra_sees, Res.string.pv_extra_when),
    Party(Res.string.pv_backup_who, Res.string.pv_backup_sees, Res.string.pv_backup_when),
)

/**
 * Who sees what (ADR 0015): Hopp itself collects nothing, so the page is
 * about everyone else the app talks to — plainly, one card each — the
 * User-Agent it sends, and what the system backup takes along.
 */
@Composable
fun PrivacyScreen() {
    val app: AppInfo = koinInject()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.privacy_intro), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(Res.string.privacy_who_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        PARTIES.forEach { p ->
            Column(
                Modifier.fillMaxWidth().border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(p.who), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.privacy_sees, stringResource(p.sees)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(Res.string.privacy_when, stringResource(p.whenSeen)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Text(stringResource(Res.string.privacy_ua_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(Res.string.privacy_ua, app.userAgent), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(Res.string.privacy_backup_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(Res.string.privacy_backup), style = MaterialTheme.typography.bodyMedium)
    }
}
