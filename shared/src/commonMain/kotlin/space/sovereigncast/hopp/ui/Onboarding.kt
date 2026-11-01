package space.sovereigncast.hopp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import space.sovereigncast.hopp.resources.onboarding_wallet_text_browser
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
import space.sovereigncast.hopp.resources.onboarding_nostr_text
import space.sovereigncast.hopp.resources.onboarding_nostr_title
import space.sovereigncast.hopp.resources.onboarding_nostr_comments
import space.sovereigncast.hopp.resources.onboarding_nostr_votes
import space.sovereigncast.hopp.resources.onboarding_nostr_boosts
import space.sovereigncast.hopp.resources.onboarding_nostr_restore
import space.sovereigncast.hopp.resources.onboarding_moving
import space.sovereigncast.hopp.resources.onboarding_returning
import space.sovereigncast.hopp.resources.onboarding_nostr_sync
import space.sovereigncast.hopp.resources.onboarding_nostr_setup
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import space.sovereigncast.hopp.resources.onboarding_wallet_title
import space.sovereigncast.hopp.resources.onboarding_welcome_text
import space.sovereigncast.hopp.resources.onboarding_welcome_title
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.HoppSymbol
import space.sovereigncast.hopp.ui.theme.Hopp

/** [icon] null: Hopp's logo symbol, for the page that says what Hopp is. */
private data class TourPage(
    val icon: ImageVector?, val value: Boolean, val title: StringResource, val text: StringResource, val nwcLink: Boolean = false,
    /** What it brings, a line each — a page with more to say than a paragraph holds. */
    val points: List<StringResource> = emptyList(),
    val after: StringResource? = null,
    /** Set up right here, from Hopp — a Nostr key needs nothing outside it, unlike a wallet. */
    val setup: StringResource? = null,
    /** Beside [setup] in the footer: the library back from Nostr, for those who used Hopp before (ADR 0021, 5). */
    val restore: StringResource? = null,
    /** The welcome page: a quiet way in for those who use Hopp already, before any page explains Nostr. */
    val returning: Boolean = false,
    /** The subscribing page: for those who come from another podcast app, their shows over (OPML). */
    val moving: Boolean = false,
)

private val ALL_PAGES = listOf(
    TourPage(null, false, Res.string.onboarding_welcome_title, Res.string.onboarding_welcome_text, returning = true),
    TourPage(Icons.Rounded.Search, false, Res.string.onboarding_subscribe_title, Res.string.onboarding_subscribe_text, moving = true),
    TourPage(Icons.AutoMirrored.Rounded.List, false, Res.string.onboarding_library_title, Res.string.onboarding_library_text),
    TourPage(HoppIcons.Hop, true, Res.string.onboarding_v4v_title, Res.string.onboarding_v4v_text),
    // Nostr, optional (maintainer, 2026-10-16): what it is, what Hopp does with it — sync, announced
    // as coming until it came (2026-10-20), among the rest. Set up or restore side by side in the
    // footer, which never scrolls (maintainer, 2026-10-21: a link in the page fell off small phones).
    TourPage(
        HoppIcons.Comment, false, Res.string.onboarding_nostr_title, Res.string.onboarding_nostr_text,
        points = listOf(Res.string.onboarding_nostr_comments, Res.string.onboarding_nostr_votes, Res.string.onboarding_nostr_boosts, Res.string.onboarding_nostr_sync),
        setup = Res.string.onboarding_nostr_setup, restore = Res.string.onboarding_nostr_restore,
    ),
    TourPage(HoppIcons.Hop, true, Res.string.onboarding_wallet_title, Res.string.onboarding_wallet_text, nwcLink = true),
)

/**
 * The first start (device feedback 2026-10-10): six short pages — what Hopp
 * is, subscribing, the library, value for value, Nostr, the wallet — swiped or
 * stepped through, skippable at any time. The last page offers to connect a
 * wallet right away — left out when one is connected (device feedback
 * 2026-10-10); [onDone] says whether the listener took it.
 */
@Composable
fun OnboardingScreen(
    hasWallet: Boolean = false,
    /** The page to open at: where the listener left the tour for one of the pages below. */
    initialPage: Int = 0,
    /** Each gets the page the tour is at, to come back to it. */
    onNostr: (page: Int) -> Unit = {},
    onRestore: (page: Int) -> Unit = onNostr,
    onMove: (page: Int) -> Unit = {},
    onDone: (connectWallet: Boolean) -> Unit,
) {
    // A wallet already connected (tour again, or after an update): no page asking for one.
    // Where Hopp pays at all: not in the browser for now — no value-for-value page there either.
    val canPay = LocalPlatformActions.current.canPay
    // In the browser the wallet is an extension's (WebLN), not one connected over NWC.
    val browser = LocalPlatformActions.current.paysWithBrowser
    val PAGES = when {
        !canPay -> ALL_PAGES.dropLast(1).filter { it.title != Res.string.onboarding_v4v_title }
            .map { p -> p.copy(points = p.points.filter { it != Res.string.onboarding_nostr_boosts }) }
        hasWallet -> ALL_PAGES.dropLast(1)
        browser -> ALL_PAGES.dropLast(1) + ALL_PAGES.last().copy(text = Res.string.onboarding_wallet_text_browser, nwcLink = false)
        else -> ALL_PAGES
    }
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, PAGES.lastIndex)) { PAGES.size }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val last = pager.currentPage == PAGES.lastIndex
    // A phone on its side has no height for page, dots and buttons one above the
    // other — the page's text was cut off (device check 2026-10-20): side by side there.
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        val sideBySide = maxWidth > maxHeight && maxHeight < 480.dp
        @Composable fun Skip() = Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = { onDone(false) }) { Text(stringResource(Res.string.onboarding_skip)) }
            else Spacer(Modifier.height(48.dp))
        }
        @Composable fun Pages(modifier: Modifier) {
            HorizontalPager(pager, modifier) { i ->
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
                    if (p.points.isNotEmpty()) Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.points.forEach { point ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                Text(stringResource(point), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    p.after?.let { Text(stringResource(it), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center) }
                    // The NWC explainer (device feedback 2026-10-11), only when it answers.
                    if (p.nwcLink) space.sovereigncast.hopp.ui.components.NwcExplainerLink(Modifier.padding(top = 16.dp), centered = true)
                }
            }
        }
        @Composable fun Dots() {
            // Where you are: one dot per page.
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                PAGES.indices.forEach { i ->
                    Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) colors.onSurface else colors.outlineVariant))
                }
            }
        }
        @Composable fun Buttons() {
            // As wide as the text above, not the whole of a tablet (ADR 0020).
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).wrapContentWidth().widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    // The second row: on the Nostr page set up or restore, on the welcome page the way
                    // back for those who use Hopp already; elsewhere the same height, so nothing jumps.
                    val page = PAGES[pager.currentPage]
                    val setup = page.setup
                    val restore = page.restore
                    when {
                        setup != null && restore != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onNostr(pager.currentPage) }, modifier = Modifier.weight(1f)) { Text(stringResource(setup), maxLines = 1) }
                            OutlinedButton(onClick = { onRestore(pager.currentPage) }, modifier = Modifier.weight(1f)) { Text(stringResource(restore), maxLines = 1) }
                        }
                        setup != null -> OutlinedButton(onClick = { onNostr(pager.currentPage) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(setup)) }
                        page.returning -> TextButton(onClick = { onRestore(pager.currentPage) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.onboarding_returning)) }
                        page.moving -> TextButton(onClick = { onMove(pager.currentPage) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.onboarding_moving)) }
                        else -> TextButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("") }
                    }
                }
            }
        }
        if (sideBySide) Row(Modifier.fillMaxSize()) {
            Pages(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Skip()
                Column { Dots(); Buttons() }
            }
        } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // A large window: the tour as one column in its middle, "Skip" at its corner and not the
            // window's — 1 300 px from the text in a browser (UX review 2026-10-30). A phone as before.
            val wide = maxWidth >= space.sovereigncast.hopp.ui.components.MEDIUM_WIDTH
            Column(if (wide) Modifier.widthIn(max = 600.dp).heightIn(max = 800.dp).fillMaxSize() else Modifier.fillMaxSize()) {
                Skip()
                Pages(Modifier.weight(1f).fillMaxWidth())
                Dots()
                Buttons()
            }
        }
    }
}
