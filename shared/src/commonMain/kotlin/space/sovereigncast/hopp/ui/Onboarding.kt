package space.sovereigncast.hopp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.onboarding_later
import space.sovereigncast.hopp.resources.onboarding_library_text
import space.sovereigncast.hopp.resources.onboarding_library_title
import space.sovereigncast.hopp.resources.onboarding_next
import space.sovereigncast.hopp.resources.onboarding_skip
import space.sovereigncast.hopp.resources.onboarding_start
import space.sovereigncast.hopp.resources.onboarding_subscribe_text
import space.sovereigncast.hopp.resources.onboarding_subscribe_title
import space.sovereigncast.hopp.resources.onboarding_v4v_text
import space.sovereigncast.hopp.resources.onboarding_v4v_title
import space.sovereigncast.hopp.resources.onboarding_wallet
import space.sovereigncast.hopp.resources.onboarding_wallet_text
import space.sovereigncast.hopp.resources.onboarding_wallet_title
import space.sovereigncast.hopp.resources.onboarding_welcome_text
import space.sovereigncast.hopp.resources.onboarding_welcome_title
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.HoppSymbol
import space.sovereigncast.hopp.ui.theme.Hopp

/** [icon] null: Hopp's logo symbol, for the page that says what Hopp is. */
private class TourPage(val icon: ImageVector?, val value: Boolean, val title: StringResource, val text: StringResource, val nwcLink: Boolean = false)

private val ALL_PAGES = listOf(
    TourPage(null, false, Res.string.onboarding_welcome_title, Res.string.onboarding_welcome_text),
    TourPage(Icons.Rounded.Search, false, Res.string.onboarding_subscribe_title, Res.string.onboarding_subscribe_text),
    TourPage(Icons.AutoMirrored.Rounded.List, false, Res.string.onboarding_library_title, Res.string.onboarding_library_text),
    TourPage(HoppIcons.Hop, true, Res.string.onboarding_v4v_title, Res.string.onboarding_v4v_text),
    TourPage(HoppIcons.Hop, true, Res.string.onboarding_wallet_title, Res.string.onboarding_wallet_text, nwcLink = true),
)

/**
 * The first start (device feedback 2026-10-10): five short pages — what Hopp
 * is, subscribing, the library, value for value, the wallet — swiped or
 * stepped through, skippable at any time. The last page offers to connect a
 * wallet right away — left out when one is connected (device feedback
 * 2026-10-10); [onDone] says whether the listener took it.
 */
@Composable
fun OnboardingScreen(hasWallet: Boolean = false, onDone: (connectWallet: Boolean) -> Unit) {
    // A wallet already connected (tour again, or after an update): no page asking for one.
    val PAGES = if (hasWallet) ALL_PAGES.dropLast(1) else ALL_PAGES
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val last = pager.currentPage == PAGES.lastIndex
    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = { onDone(false) }) { Text(stringResource(Res.string.onboarding_skip)) }
            else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
            val p = PAGES[i]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)
                    .wrapContentWidth().widthIn(max = 520.dp), // a tablet: a column, not a banner (ADR 0020)
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(if (p.value) Hopp.colors.value else colors.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    // The welcome page shows the logo; the hop alone means boost (ADR 0019).
                    if (p.icon == null) HoppSymbol(Modifier.size(60.dp))
                    else Icon(p.icon, null, Modifier.size(48.dp), tint = if (p.value) Hopp.colors.onValue else colors.onSurface)
                }
                Spacer(Modifier.height(28.dp))
                Text(stringResource(p.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(p.text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                // The NWC explainer (device feedback 2026-10-11), only when it answers.
                if (p.nwcLink) space.sovereigncast.hopp.ui.components.NwcExplainerLink(Modifier.padding(top = 16.dp), centered = true)
            }
        }
        // Where you are: one dot per page.
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            PAGES.indices.forEach { i ->
                Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) colors.onSurface else colors.outlineVariant))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (last && hasWallet) {
                Button(onClick = { onDone(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.onboarding_start)) }
                TextButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("") }
            } else if (last) {
                Button(
                    onClick = { onDone(true) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Hopp.colors.value, contentColor = Hopp.colors.onValue),
                ) { Text(stringResource(Res.string.onboarding_wallet), fontWeight = FontWeight.Bold) }
                TextButton(onClick = { onDone(false) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.onboarding_later)) }
            } else {
                Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.onboarding_next))
                }
                // Same height as the last page's second button, so nothing jumps.
                TextButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("") }
            }
        }
    }
}
