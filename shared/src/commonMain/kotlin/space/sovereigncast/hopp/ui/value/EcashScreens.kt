package space.sovereigncast.hopp.ui.value

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.wallet_ecash_add
import space.sovereigncast.hopp.resources.wallet_ecash_add_note
import space.sovereigncast.hopp.resources.wallet_ecash_amount
import space.sovereigncast.hopp.resources.wallet_ecash_check
import space.sovereigncast.hopp.resources.wallet_ecash_copy
import space.sovereigncast.hopp.resources.wallet_ecash_done
import space.sovereigncast.hopp.resources.wallet_ecash_failed
import space.sovereigncast.hopp.resources.wallet_ecash_intro
import space.sovereigncast.hopp.resources.wallet_ecash_invoice_hint
import space.sovereigncast.hopp.resources.wallet_ecash_mint_hint
import space.sovereigncast.hopp.resources.wallet_ecash_receive
import space.sovereigncast.hopp.resources.wallet_ecash_received
import space.sovereigncast.hopp.resources.wallet_ecash_send
import space.sovereigncast.hopp.resources.wallet_ecash_send_note
import space.sovereigncast.hopp.resources.wallet_ecash_still_held
import space.sovereigncast.hopp.resources.wallet_ecash_title
import space.sovereigncast.hopp.resources.wallet_ecash_token_hint
import space.sovereigncast.hopp.resources.wallet_ecash_unknown
import space.sovereigncast.hopp.resources.wallet_ecash_use
import space.sovereigncast.hopp.resources.wallet_ecash_withdraw
import space.sovereigncast.hopp.resources.wallet_ecash_withdrawn
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.components.QrImage
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.theme.Hopp

/**
 * No wallet yet: ecash at a Cashu mint instead of an NWC connection (ADR 0026).
 * The mint is contacted only on the tap (ADR 0015).
 */
@Composable
internal fun EcashConnect(vm: WalletViewModel, working: Boolean) {
    var mint by remember { mutableStateOf("") }
    Text(stringResource(Res.string.wallet_ecash_intro), Modifier.padding(top = 8.dp))
    OutlinedTextField(
        mint, { mint = it }, Modifier.fillMaxWidth().testTag("ecash_mint"), singleLine = true,
        label = { Text(stringResource(Res.string.wallet_ecash_mint_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedButton(onClick = { vm.connectEcash(mint) }, enabled = mint.isNotBlank() && !working) {
        Text(stringResource(Res.string.wallet_ecash_use))
    }
}

/**
 * The ecash wallet in use: add sats by Lightning, take in or hand out tokens,
 * withdraw to an invoice. A token is bearer money: copied as a secret.
 */
@Composable
internal fun EcashPanel(vm: WalletViewModel) {
    val state by vm.ecash.collectAsState()
    val colors = MaterialTheme.colorScheme
    val actions = LocalPlatformActions.current
    val working = state == EcashAction.Working
    var amount by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var invoice by remember { mutableStateOf("") }
    val sats = amount.trim().toLongOrNull()?.takeIf { it > 0 }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(Res.string.wallet_ecash_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            amount, { t -> amount = t.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth().testTag("ecash_amount"), singleLine = true,
            label = { Text(stringResource(Res.string.wallet_ecash_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { sats?.let(vm::addEcash) }, enabled = sats != null && !working) { Text(stringResource(Res.string.wallet_ecash_add)) }
            OutlinedButton(onClick = { sats?.let(vm::sendEcash) }, enabled = sats != null && !working) { Text(stringResource(Res.string.wallet_ecash_send)) }
            if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        when (val s = state) {
            is EcashAction.Invoice -> {
                QrImage(s.invoice, stringResource(Res.string.wallet_ecash_add), Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Text(stringResource(Res.string.wallet_ecash_add_note), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::checkEcash) { Text(stringResource(Res.string.wallet_ecash_check)) }
                    TextButton(onClick = { actions.copyText(s.invoice, "invoice") }) { Text(stringResource(Res.string.wallet_ecash_copy)) }
                }
            }
            is EcashAction.Token -> {
                QrImage(s.token, stringResource(Res.string.wallet_ecash_send), Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Text(stringResource(Res.string.wallet_ecash_send_note), color = colors.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.copySecret(s.token, "ecash") }) { Text(stringResource(Res.string.wallet_ecash_copy)) }
                    TextButton(onClick = vm::dismissEcash) { Text(stringResource(Res.string.wallet_ecash_done)) }
                }
            }
            is EcashAction.Received -> Text(stringResource(Res.string.wallet_ecash_received, formatSats(s.sats)), color = Hopp.colors.valueText)
            EcashAction.Withdrawn -> Text(stringResource(Res.string.wallet_ecash_withdrawn), color = Hopp.colors.valueText)
            EcashAction.Unknown -> Text(stringResource(Res.string.wallet_ecash_unknown), color = colors.onSurfaceVariant)
            EcashAction.StillHeld -> Text(stringResource(Res.string.wallet_ecash_still_held), color = colors.error)
            is EcashAction.Failed -> Text(stringResource(Res.string.wallet_ecash_failed, s.reason), color = colors.error)
            EcashAction.Idle, EcashAction.Working -> Unit
        }

        OutlinedTextField(
            token, { token = it }, Modifier.fillMaxWidth().testTag("ecash_token"), singleLine = true,
            label = { Text(stringResource(Res.string.wallet_ecash_token_hint)) },
        )
        OutlinedButton(onClick = { vm.receiveEcash(token); token = "" }, enabled = token.isNotBlank() && !working) {
            Text(stringResource(Res.string.wallet_ecash_receive))
        }
        OutlinedTextField(
            invoice, { invoice = it }, Modifier.fillMaxWidth().testTag("ecash_invoice"), singleLine = true,
            label = { Text(stringResource(Res.string.wallet_ecash_invoice_hint)) },
        )
        OutlinedButton(onClick = { vm.withdrawEcash(invoice, sats); invoice = "" }, enabled = invoice.isNotBlank() && !working) {
            Text(stringResource(Res.string.wallet_ecash_withdraw))
        }
    }
}
