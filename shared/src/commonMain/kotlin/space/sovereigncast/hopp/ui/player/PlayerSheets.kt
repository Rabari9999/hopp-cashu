package space.sovereigncast.hopp.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import space.sovereigncast.hopp.model.Person
import space.sovereigncast.hopp.ui.components.PersonRow
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.sc_no_suggestions
import space.sovereigncast.hopp.resources.sc_suggestions
import space.sovereigncast.hopp.resources.sheet_people
import space.sovereigncast.hopp.sovereigncast.ScTarget
import space.sovereigncast.hopp.ui.sovereigncast.CommunityViewModel
import space.sovereigncast.hopp.ui.sovereigncast.PollCommunity
import space.sovereigncast.hopp.ui.sovereigncast.ReleaseBoostingCard
import space.sovereigncast.hopp.ui.sovereigncast.ReleaseBoostingSheet
import space.sovereigncast.hopp.ui.sovereigncast.ScPaySheet
import space.sovereigncast.hopp.ui.sovereigncast.SuggestionRow
import space.sovereigncast.hopp.ui.sovereigncast.SuggestionSheet
import space.sovereigncast.hopp.ui.sovereigncast.SuggestButton
import space.sovereigncast.hopp.ui.sovereigncast.UnlockCard
import space.sovereigncast.hopp.ui.sovereigncast.UnlockSheet
import space.sovereigncast.hopp.sovereigncast.Boost2UnlockEpisode
import space.sovereigncast.hopp.sovereigncast.ReleaseBoostingRound
import space.sovereigncast.hopp.sovereigncast.Suggestion

/** Who is in the episode (`podcast:person`), with picture and link; a tap opens their page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleSheet(persons: List<Person>, show: String? = null, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(Res.string.sheet_people), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            items(persons) { p -> PersonRow(p, Modifier.padding(horizontal = 8.dp), show = show) }
        }
    }
}

/**
 * The show's community while listening (ADR 0019): the running action and the
 * open suggestions, with the same details and payment as on the show page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunitySheet(podcastId: Long, onDismiss: () -> Unit) {
    val community: CommunityViewModel = koinViewModel(key = "community-$podcastId") { parametersOf(podcastId) }
    PollCommunity(community)
    val layer by community.layer.collectAsState()
    val round by community.round.collectAsState()
    val unlocks by community.unlocks.collectAsState()
    val suggestions by community.suggestions.collectAsState()
    val votes by community.votes.collectAsState()
    var paying by remember { mutableStateOf<Pair<ScTarget, String>?>(null) }
    var details by remember { mutableStateOf<Any?>(null) }
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.heightIn(max = 640.dp)) {
            round?.let { r -> item(key = "rb") { ReleaseBoostingCard(r, onOpen = { details = r }) { paying = ScTarget.ReleaseBoosting to r.episode.title } } }
            itemsIndexed(unlocks.filterNot { it.unlocked }, key = { i, _ -> "b2u$i" }) { _, u ->
                UnlockCard(u, onOpen = { details = u }) { paying = ScTarget.Boost2Unlock(u.episode.episodeGuid) to u.episode.title }
            }
            if (layer?.show?.capabilities?.suggestions == true) {
                item(key = "sug-h") {
                    Text(stringResource(Res.string.sc_suggestions), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
                }
                val list = suggestions
                when {
                    list == null -> item(key = "sug-loading") { CircularProgressIndicator(Modifier.padding(16.dp)) }
                    list.isEmpty() -> item(key = "sug-none") { Text(stringResource(Res.string.sc_no_suggestions), Modifier.padding(16.dp), color = colors.onSurfaceVariant) }
                    else -> items(list, key = { "sug" + it.id }) { sg ->
                        val l = layer
                        SuggestionRow(sg, voted = l != null && "${l.show.podcastGuid}/${sg.id}" in votes, onOpen = { details = sg },
                            onBoost = { paying = ScTarget.SuggestionBoost(sg.id) to sg.title }) { community.vote(sg) }
                    }
                }
                item(key = "sug-new") { SuggestButton { paying = ScTarget.NewSuggestion() to "" } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    when (val d = details) {
        is ReleaseBoostingRound -> ReleaseBoostingSheet(d, layer?.show, onBoost = {
            details = null; paying = ScTarget.ReleaseBoosting to d.episode.title
        }) { details = null }
        is Boost2UnlockEpisode -> UnlockSheet(d, layer?.show, onUnlock = {
            details = null; paying = ScTarget.Boost2Unlock(d.episode.episodeGuid) to d.episode.title
        }) { details = null }
        is Suggestion -> {
            val sg = suggestions?.firstOrNull { it.id == d.id } ?: d
            val l = layer
            SuggestionSheet(sg, voted = l != null && "${l.show.podcastGuid}/${sg.id}" in votes, onVote = { community.vote(sg) },
                onBoost = { details = null; paying = ScTarget.SuggestionBoost(sg.id) to sg.title }) { details = null }
        }
    }
    paying?.let { (target, title) ->
        ScPaySheet(community, target, title, satsPerMinute = round?.satsPerMinute?.takeIf { target is ScTarget.ReleaseBoosting }) { paying = null }
    }
}
