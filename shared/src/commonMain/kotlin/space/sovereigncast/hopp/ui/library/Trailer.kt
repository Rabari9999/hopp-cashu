package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.model.Trailer
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.player.RemoteClip
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.show_trailer
import space.sovereigncast.hopp.ui.components.HoppIcons

/**
 * The show's trailer (`podcast:trailer`, phase 2): the newest one the feed
 * names — a feed may keep one per season. Not an episode of the feed: it plays
 * as a Sovereigncast clip does, and the episode from before comes back paused.
 */
fun latestTrailer(trailers: List<Trailer>): Trailer? =
    trailers.withIndex().maxWithOrNull(compareBy({ it.value.publishedAt ?: Long.MIN_VALUE }, { it.index }))?.value

/** A line as the show's funding link is — the row of buttons above is full on a phone. */
@Composable
fun TrailerLine(trailers: List<Trailer>, showTitle: String, artworkUrl: String?) {
    val trailer = latestTrailer(trailers) ?: return
    val player: PlayerController = koinInject()
    val label = stringResource(Res.string.show_trailer)
    Row(
        Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
            .clickable { player.playRemoteClip(RemoteClip(trailer.url, trailer.title?.takeIf { it.isNotBlank() } ?: showTitle, showTitle, artworkUrl, trailer = true)) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HoppIcons.Play, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
