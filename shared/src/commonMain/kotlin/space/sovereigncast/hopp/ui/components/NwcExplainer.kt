package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.nwc_explainer_url
import space.sovereigncast.hopp.resources.onboarding_nwc_more

/** Whether the explainer answered, per client and address — asked once per run
 *  (the app has one client; each test its own). */
private val reachable = mutableMapOf<Pair<HttpClient, String>, Boolean>()

/**
 * "What NWC is and how to set it up" with a link to the explainer page
 * (device feedback 2026-10-11) — only when the page answers: a dead link is
 * worse than none. Shown by the tour's wallet page and the boost sheet
 * without a wallet.
 */
@Composable
fun NwcExplainerLink(modifier: Modifier = Modifier, centered: Boolean = false) {
    val http: HttpClient = koinInject()
    val url = stringResource(Res.string.nwc_explainer_url)
    val key = http to url
    val up by produceState(reachable[key] ?: false, url) {
        value = reachable[key] ?: (withTimeoutOrNull(5_000) { runCatching { http.get(url).status.isSuccess() }.getOrDefault(false) } ?: false)
            .also { reachable[key] = it }
    }
    if (!up) return
    val uri = LocalUriHandler.current
    Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            stringResource(Res.string.onboarding_nwc_more), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(url.substringAfter("://").substringBefore('/') + " ↗", fontWeight = FontWeight.Bold) }
    }
}
