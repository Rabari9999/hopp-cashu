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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.show_pick_episode
import space.sovereigncast.hopp.ui.components.EXPANDED_WIDTH

/** How wide the show's own column is beside an episode. */
private val SHOW_COLUMN = 420.dp

/**
 * A show and one of its episodes side by side once the content is wide enough
 * (ADR 0020, step 2): the show's page on the left, unchanged — a row there picks
 * the episode on the right instead of opening a page over it. Narrower, the show's
 * page alone, its rows opening the episode as before.
 *
 * The pick is the page's own state, not a step on the back stack: back leaves
 * the show, as it does on a phone from the show's page.
 */
@Composable
fun ShowListDetail(
    podcastId: Long,
    show: @Composable () -> Unit,
    episode: @Composable (id: Long, onGone: () -> Unit) -> Unit,
) {
    // Kept outside the wide branch: opening now playing beside the content narrows
    // it to the show alone, and closing it again brings the episode back.
    var picked by rememberSaveable(podcastId) { mutableStateOf<Long?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < EXPANDED_WIDTH) { show(); return@BoxWithConstraints }
        val outer = LocalEpisodeNav.current
        val pick = remember(outer) { EpisodeNav(open = { picked = it.id }, play = outer.play) }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(SHOW_COLUMN).fillMaxHeight()) {
                CompositionLocalProvider(LocalEpisodeNav provides pick) { show() }
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val id = picked
                if (id == null) Text(
                    stringResource(Res.string.show_pick_episode),
                    Modifier.align(Alignment.Center).padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                ) else key(id) { episode(id) { picked = null } }
            }
        }
    }
}
