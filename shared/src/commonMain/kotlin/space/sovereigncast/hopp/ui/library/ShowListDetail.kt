package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.show_pick_episode
import space.sovereigncast.hopp.ui.components.EXPANDED_WIDTH
import space.sovereigncast.hopp.ui.components.LocalPageBack

/** How wide the show's own column is beside an episode. */
/** The episode shown beside the list (side by side), marked in it; null elsewhere. */
val LocalShownEpisode = androidx.compose.runtime.compositionLocalOf<Long?> { null }

/** The show's column: 45 % of the page, between these — at a fixed 420 dp its titles broke after two
 *  lines on a desktop while half the window stood empty (UX review 2026-10-30). */
private val SHOW_COLUMN_MIN = 420.dp
private val SHOW_COLUMN_MAX = 560.dp

/**
 * A show and one of its episodes side by side once the content is wide enough
 * (ADR 0020, step 2): the show's page on the left, unchanged — a row there picks
 * the episode on the right instead of opening a page over it. Narrower, the show's
 * page alone, its rows opening the episode as before.
 *
 * The pick is the page's own state, not a step on the back stack: back leaves
 * the show, as it does on a phone from the show's page.
 *
 * When the page narrows to one column with an episode picked — now playing
 * opening beside it, the tablet turned upright — that column shows the episode,
 * not the show (device feedback 2026-10-13: the episode one was looking at
 * vanished behind the show when its player opened); back then returns to the show.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class) // BackHandler
@Composable
fun ShowListDetail(
    podcastId: Long,
    /** The show's newest episode: shown on the right until one is picked, rather than an empty half. */
    latest: Long? = null,
    show: @Composable () -> Unit,
    episode: @Composable (id: Long, onGone: () -> Unit) -> Unit,
) {
    // Kept outside the wide branch: it outlives the page narrowing and widening again.
    var picked by rememberSaveable(podcastId) { mutableStateOf<Long?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < EXPANDED_WIDTH) {
            val id = picked
            BackHandler(enabled = id != null) { picked = null }
            if (id != null) {
                val pageBack = LocalPageBack.current
                DisposableEffect(pageBack) {
                    val step = { picked = null }
                    pageBack.step = step
                    onDispose { if (pageBack.step === step) pageBack.step = null }
                }
            }
            if (id == null) show() else key(id) { episode(id) { picked = null } }
            return@BoxWithConstraints
        }
        val outer = LocalEpisodeNav.current
        val pick = remember(outer) { EpisodeNav(open = { picked = it.id }, play = outer.play) }
        val column = (maxWidth * 0.45f).coerceIn(SHOW_COLUMN_MIN, SHOW_COLUMN_MAX)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(column).fillMaxHeight()) {
                CompositionLocalProvider(LocalEpisodeNav provides pick, LocalShownEpisode provides (picked ?: latest)) { show() }
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // Not picked: the newest, not a step of its own — back still leaves the show.
                val id = picked ?: latest
                if (id == null) Text(
                    stringResource(Res.string.show_pick_episode),
                    Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                ) else key(id) { episode(id) { picked = null } }
            }
        }
    }
}
