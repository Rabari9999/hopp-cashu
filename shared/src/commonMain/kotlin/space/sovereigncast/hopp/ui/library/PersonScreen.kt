package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import space.sovereigncast.hopp.ui.components.LocalPageTitle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import space.sovereigncast.hopp.data.PeopleRepository
import space.sovereigncast.hopp.data.PersonEpisode
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.directory.DirectoryRepository
import space.sovereigncast.hopp.directory.PersonHit
import space.sovereigncast.hopp.directory.feedKey
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.ui.components.PersonLink
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatTime
import space.sovereigncast.hopp.ui.components.roleLabel
import space.sovereigncast.hopp.ui.theme.Hopp

/** Who a person page is about: what the place it was opened from knew of them. */
@Serializable
data class PersonRef(val name: String, val img: String? = null, val href: String? = null, val role: String? = null, val show: String? = null)

/** What the Podcast Index found: nothing yet, its answer, no answer, or no key in this build. */
sealed interface Elsewhere {
    data object Loading : Elsewhere
    data class Found(val shows: List<List<PersonHit>>) : Elsewhere
    data object Failed : Elsewhere
    data object Unavailable : Elsewhere
}

class PersonViewModel(
    private val name: String,
    private val people: PeopleRepository,
    private val directory: DirectoryRepository,
    private val podcasts: PodcastRepository,
) : ViewModel() {
    private val _library = MutableStateFlow<List<PersonEpisode>?>(null)
    /** The episodes in the subscribed shows naming them; null while read. */
    val library: StateFlow<List<PersonEpisode>?> = _library.asStateFlow()
    private val _elsewhere = MutableStateFlow<Elsewhere>(Elsewhere.Loading)
    val elsewhere: StateFlow<Elsewhere> = _elsewhere.asStateFlow()
    /** Feed keys of what is subscribed: those shows are under "In your subscriptions" already. */
    val subscribed: StateFlow<Set<String>> = state(podcasts.shows.map { l -> l.map { feedKey(it.feedUrl) }.toSet() }, emptySet())
    private val _subscribing = MutableStateFlow<Set<String>>(emptySet())
    val subscribing: StateFlow<Set<String>> = _subscribing.asStateFlow()

    init {
        // Read again when the subscriptions change: a show subscribed here moves up into them
        // (review 2026-10-27: it left "In other podcasts" and appeared nowhere).
        viewModelScope.launch {
            podcasts.shows.map { l -> l.map { it.id }.toSet() }.distinctUntilChanged().collectLatest {
                _library.value = runCatching { people.episodes(name) }.getOrDefault(emptyList())
            }
        }
        viewModelScope.launch {
            _elsewhere.value = runCatching { directory.byPerson(name) }.fold(
                { hits -> hits?.let { h -> Elsewhere.Found(h.groupBy { feedKey(it.feedUrl) }.values.map { g -> g.distinctBy { it.guid } }) } ?: Elsewhere.Unavailable },
                { Elsewhere.Failed },
            )
        }
    }

    fun subscribe(feedUrl: String) {
        val key = feedKey(feedUrl)
        if (key in _subscribing.value) return
        _subscribing.update { it + key }
        viewModelScope.launch { podcasts.subscribe(feedUrl); _subscribing.update { it - key } }
    }

    private fun <T> state(flow: kotlinx.coroutines.flow.Flow<T>, initial: T) =
        flow.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), initial)
}

/**
 * A person across podcasts (maintainer 2026-10-26, after AntennaPod-like requests): the
 * episodes of the subscribed shows naming them, by show, then what the Podcast Index finds
 * in other podcasts. Found by name — namesakes can be among them, and the page says so,
 * as it says that the name went to the index.
 */
@Composable
fun PersonScreen(
    person: PersonRef,
    onEpisode: (Long) -> Unit,
    onPreview: (feedUrl: String, guid: String?, title: String, imageUrl: String?) -> Unit,
    vm: PersonViewModel = koinViewModel(key = "person:" + person.name) { parametersOf(person.name) },
) {
    val library by vm.library.collectAsState()
    val elsewhere by vm.elsewhere.collectAsState()
    val subscribed by vm.subscribed.collectAsState()
    val subscribing by vm.subscribing.collectAsState()
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(setOf<Long>()) }
    // The name in the top bar only once the head with it has scrolled away (maintainer 2026-10-27).
    val list = rememberLazyListState()
    val pageTitle = LocalPageTitle.current
    val nameGone = with(LocalDensity.current) { 64.dp.roundToPx() }
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > nameGone } }
    DisposableEffect(scrolled, person.name) {
        pageTitle.text = person.name.takeIf { scrolled }
        onDispose { if (pageTitle.text == person.name) pageTitle.text = null }
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header(person) }

        item { Section(stringResource(Res.string.person_in_library), library?.let { pluralStringResource(Res.plurals.person_episodes, it.size, it.size) }) }
        when (val l = library) {
            null -> item { Loading() }
            else -> {
                if (l.isEmpty()) item { Quiet(stringResource(Res.string.person_none_library, person.name)) }
                l.groupBy { it.item.podcastId }.values.forEach { group ->
                    val first = group.first().item
                    val id = first.podcastId
                    item(key = "l$id") {
                        ShowLine(first.podcastTitle.orEmpty(), first.podcastImageUrl,
                            listOfNotNull(roleLabel(group.first().role), pluralStringResource(Res.plurals.person_episodes, group.size, group.size)).joinToString(" · "))
                    }
                    val shown = if (id in open) group else group.take(PER_SHOW)
                    items(shown, key = { "e" + it.item.id }) { e -> EpisodeRow(e.item, showPodcast = false, onClick = { onEpisode(e.item.id) }) }
                    if (group.size > PER_SHOW && id !in open) item(key = "m$id") {
                        TextButton(onClick = { open = open + id }, Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(Res.string.person_show_all, group.size), color = Hopp.colors.valueText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (elsewhere != Elsewhere.Unavailable) {
            item { Section(stringResource(Res.string.person_elsewhere), "Podcast Index") }
            when (val e = elsewhere) {
                Elsewhere.Loading -> item { Loading() }
                Elsewhere.Failed -> item { Quiet(stringResource(Res.string.person_failed)) }
                is Elsewhere.Found -> {
                    val shows = e.shows.filter { feedKey(it.first().feedUrl) !in subscribed }
                    if (shows.isEmpty()) item { Quiet(stringResource(Res.string.person_none_elsewhere)) }
                    shows.forEach { hits ->
                        val show = hits.first()
                        val key = feedKey(show.feedUrl)
                        item(key = "s$key") {
                            ShowLine(show.feedTitle, show.feedImage, show.feedAuthor, onClick = { onPreview(show.feedUrl, null, show.feedTitle, show.feedImage) }) {
                                when (key) {
                                    in subscribing -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                                    in subscribed -> Icon(Icons.Rounded.Check, null, Modifier.padding(12.dp))
                                    else -> IconButton(onClick = { vm.subscribe(show.feedUrl) }) {
                                        Icon(Icons.Rounded.Add, stringResource(Res.string.person_subscribe, show.feedTitle))
                                    }
                                }
                            }
                        }
                        items(hits.take(PER_SHOW), key = { "h$key/" + it.guid }) { h ->
                            Column(
                                Modifier.fillMaxWidth().clickable { onPreview(h.feedUrl, h.guid, h.feedTitle, h.feedImage) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                listOfNotNull(h.publishedAt?.let { formatDate(it) }?.takeIf { it.isNotEmpty() }, h.durationSeconds?.let { formatTime(it * 1000) }).takeIf { it.isNotEmpty() }?.let {
                                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Elsewhere.Unavailable -> Unit
            }
            item {
                Text(
                    stringResource(Res.string.person_note, person.name), Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Episodes shown per show before "show all". */
private const val PER_SHOW = 3

@Composable
private fun Header(person: PersonRef) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(80.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(person.name.trim().take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = colors.onSurfaceVariant)
            if (person.img != null) AsyncImage(person.img, null, Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(person.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val role = roleLabel(person.role)
            when {
                role != null && person.show != null -> stringResource(Res.string.person_role_at, role, person.show)
                else -> role ?: person.show
            }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            person.href?.let { PersonLink(it) }
        }
    }
}

@Composable
private fun Section(title: String, note: String?) = Row(
    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom,
) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun ShowLine(title: String, imageUrl: String?, note: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Column {
        HorizontalDivider(Modifier.padding(top = 4.dp))
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Cover(imageUrl, 32.dp, corner = 6.dp)
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            trailing()
        }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun Quiet(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
