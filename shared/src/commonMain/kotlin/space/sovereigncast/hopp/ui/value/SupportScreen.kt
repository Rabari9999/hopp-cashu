package space.sovereigncast.hopp.ui.value

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import space.sovereigncast.hopp.storePaysForContent
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.resources.support_no_wallet_app
import space.sovereigncast.hopp.resources.support_wallet_app
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.settings_wallet
import space.sovereigncast.hopp.resources.support_other_wallet
import space.sovereigncast.hopp.resources.support_share
import space.sovereigncast.hopp.resources.support_text
import space.sovereigncast.hopp.resources.support_to

/**
 * "Support Hopp" (maintainer 2026-11-02): a donation to Hopp's Lightning address from the
 * connected wallet — NWC or, in a browser, WebLN — on one page: why, the amount, a message, one
 * button. The boost dialog's own form, so balance, budget and the outcome work as for any boost.
 * Under it a wallet app on the phone (maintainer 2026-11-03: no connection needed), the address itself
 * for any other wallet, and the share of every boost.
 */
@Composable
fun SupportScreen(onOpenWallet: () -> Unit, vm: ValueBarViewModel = koinViewModel()) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BoostForm(
        vm, item = null, showTitle = "Hopp", positionMs = 0, onDismiss = {}, onOpenWallet = onOpenWallet,
        podcastId = 0, donateTo = Settings.SUPPORT_ADDRESS, presets = PRESETS, start = 5_000L, heading = false, plain = true,
        header = {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.support_text), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.support_to, Settings.SUPPORT_ADDRESS), color = muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        footer = {
            Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.support_other_wallet), color = muted, style = MaterialTheme.typography.bodySmall)
                // On a phone, any wallet app that takes a Lightning address — no wallet connected to Hopp needed
                // (maintainer 2026-11-03). Not in the browser, which has no such app, nor where the store forbids it.
                if (storePaysForContent() && !LocalPlatformActions.current.paysWithBrowser) {
                    val uri = LocalUriHandler.current
                    var noApp by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { noApp = runCatching { uri.openUri("lightning:" + Settings.SUPPORT_ADDRESS) }.isFailure }) {
                        Text(stringResource(Res.string.support_wallet_app))
                    }
                    if (noApp) Text(stringResource(Res.string.support_no_wallet_app), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                SelectionContainer { Text(Settings.SUPPORT_ADDRESS, fontWeight = FontWeight.SemiBold) }
                Text(stringResource(Res.string.support_share), color = muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenWallet, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(stringResource(Res.string.settings_wallet)) }
            }
        },
    )
}

/** The amounts offered for Hopp (maintainer 2026-11-02). */
private val PRESETS = listOf(1_000L, 5_000L, 21_000L)

