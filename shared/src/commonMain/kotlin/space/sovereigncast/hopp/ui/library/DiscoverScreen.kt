package space.sovereigncast.hopp.ui.library

import space.sovereigncast.hopp.resources.discover_all
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import space.sovereigncast.hopp.directory.Category
import space.sovereigncast.hopp.ui.theme.Hopp
import space.sovereigncast.hopp.resources.discover_for_you
import space.sovereigncast.hopp.resources.discover_for_you_note
import space.sovereigncast.hopp.resources.discover_recommended_by
import space.sovereigncast.hopp.resources.discover_because
import space.sovereigncast.hopp.resources.discover_trending
import space.sovereigncast.hopp.resources.discover_today
import space.sovereigncast.hopp.resources.discover_new_value
import space.sovereigncast.hopp.resources.discover_new_value_note
import space.sovereigncast.hopp.resources.discover_categories_title
import space.sovereigncast.hopp.resources.discover_categories_note
import space.sovereigncast.hopp.resources.discover_explore_empty
import space.sovereigncast.hopp.resources.discover_mine
import space.sovereigncast.hopp.ui.components.MEDIUM_WIDTH
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import space.sovereigncast.hopp.resources.discover_add_button
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.directory.DirectoryShow
import space.sovereigncast.hopp.ui.sovereigncast.ScBadge
import space.sovereigncast.hopp.resources.sc_badge
import space.sovereigncast.hopp.directory.Source
import space.sovereigncast.hopp.directory.feedKey
import space.sovereigncast.hopp.net.FeedFetcher
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.discover_failed
import space.sovereigncast.hopp.resources.discover_field
import space.sovereigncast.hopp.resources.discover_no_results
import space.sovereigncast.hopp.resources.discover_open_feed
import space.sovereigncast.hopp.resources.discover_results
import space.sovereigncast.hopp.resources.discover_suggestions
import space.sovereigncast.hopp.resources.discover_recent
import space.sovereigncast.hopp.resources.discover_recent_clear
import space.sovereigncast.hopp.resources.discover_privacy
import space.sovereigncast.hopp.resources.discover_unavailable
import space.sovereigncast.hopp.resources.discover_value_only
import space.sovereigncast.hopp.resources.opml_import
import space.sovereigncast.hopp.resources.opml_section
import space.sovereigncast.hopp.resources.opml_text
import space.sovereigncast.hopp.resources.result_imported
import space.sovereigncast.hopp.resources.show_subscribed
import space.sovereigncast.hopp.ui.LocalPlatformActions

/** Discover (ADR 0008): search as you type over the Podcast Index (and fyyd
 *  and Apple when switched on), a V4V filter, a feed address opened directly,
 *  recommendations from the subscribed shows' podroll, OPML import. Every
 *  show opens its page first — subscribing happens there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(onPreview: (String, DirectoryShow?) -> Unit, vm: DiscoverViewModel = koinViewModel(), settingsVm: SettingsViewModel = koinViewModel()) {
    val query by vm.query.collectAsState()
    val valueOnly by vm.valueOnly.collectAsState()
    val scOnly by vm.scOnly.collectAsState()
    // Read so the badges below follow the list once it arrives.
    val scShows by vm.scShows.collectAsState()
    val search by vm.search.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val forYou by vm.forYou.collectAsState()
    val myCategories by vm.myCategories.collectAsState()
    val category by vm.category.collectAsState()
    val trending by vm.trending.collectAsState()
    val newValue by vm.newValue.collectAsState()
    var allCategories by remember { mutableStateOf(false) }
    val subscribed by vm.subscribed.collectAsState()
    val subscribing by vm.subscribing.collectAsState()
    val importState by vm.import.collectAsState()
    val recent by vm.recent.collectAsState()
    val settings by settingsVm.current.collectAsState()
    val actions = LocalPlatformActions.current
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val q = query.trim()
    val address = q.takeIf { '.' in it }?.let(FeedFetcher::normalize)

    // Larger rows from a tablet's width (device feedback 2026-10-13, ADR 0020).
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      if (allCategories) CategorySheet(myCategories, onSelect = { vm.selectCategory(it); allCategories = false }, onDismiss = { allCategories = false })
      CompositionLocalProvider(LocalLargeRows provides (maxWidth >= MEDIUM_WIDTH)) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = vm::setQuery, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(Res.string.discover_field)) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Clear, stringResource(Res.string.cancel)) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focus.clearFocus()
                            if (!searchable(q) && address != null) onPreview(address, null) else vm.remember(q)
                        }),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = valueOnly, onClick = { vm.setValueOnly(!valueOnly) }, label = { Text("⚡ " + stringResource(Res.string.discover_value_only)) })
                        // Only shows on the curated list (device feedback 2026-10-10) — no
                        // directory knows the feed marker, see ScDirectory. Always there,
                        // not only once the list has arrived (device feedback 2026-10-11).
                        FilterChip(selected = scOnly, onClick = { vm.setScOnly(!scOnly) }, label = { Text("✓ " + stringResource(Res.string.sc_badge)) })
                        if (address != null) Button(onClick = { onPreview(address, null) }) { Text(stringResource(Res.string.discover_open_feed)) }
                    }
                    settings?.let { s ->
                        val sources = listOfNotNull("Podcast Index", "fyyd".takeIf { s.fyydSearch && !valueOnly }, "Apple".takeIf { s.appleSearch && !valueOnly })
                        Text(stringResource(Res.string.discover_privacy, sources.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }

            // What Hopp knows that starts to match, at once (device feedback 2026-10-14).
            val suggested = if (searchable(q)) suggestions else emptyList()
            if (suggested.isNotEmpty()) {
                item { Header(stringResource(Res.string.discover_suggestions)) }
                items(suggested, key = { "s" + it.feedUrl }) { show ->
                    DirectoryRow(show, feedKey(show.feedUrl) in subscribed, sovereigncast = scShows != null && vm.isSovereigncast(show),
                        busy = feedKey(show.feedUrl) in subscribing, onSubscribe = { vm.subscribe(show) { onPreview(show.feedUrl, show) } }) {
                        vm.remember(q); onPreview(show.feedUrl, show)
                    }
                }
            }
            val shownAbove = suggested.map { feedKey(it.feedUrl) }.toSet()
            when (val s = search) {
                SearchUi.Searching -> item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                is SearchUi.Done -> {
                    val r = s.result
                    val rest = r.shows.filterNot { feedKey(it.feedUrl) in shownAbove }
                    when {
                        r.unavailable -> item { Note(stringResource(Res.string.discover_unavailable)) }
                        rest.isEmpty() && suggested.isEmpty() && r.failed.isEmpty() -> item { Note(stringResource(Res.string.discover_no_results)) }
                    }
                    if (r.failed.isNotEmpty()) item { Note(stringResource(Res.string.discover_failed, r.failed.joinToString(", ") { it.label })) }
                    if (suggested.isNotEmpty() && rest.isNotEmpty()) item { Header(stringResource(Res.string.discover_results)) }
                    items(rest, key = { "r" + it.feedUrl }) { show ->
                        DirectoryRow(show, feedKey(show.feedUrl) in subscribed, sovereigncast = scShows != null && vm.isSovereigncast(show),
                        busy = feedKey(show.feedUrl) in subscribing, onSubscribe = { vm.subscribe(show) { onPreview(show.feedUrl, show) } }) {
                            vm.remember(q); onPreview(show.feedUrl, show)
                        }
                    }
                }
                SearchUi.Idle -> if (q.isEmpty()) {
                    if (recent.isNotEmpty()) item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.discover_recent), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { vm.clearRecent() }) { Text(stringResource(Res.string.discover_recent_clear)) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                recent.forEach { term -> SuggestionChip(onClick = { vm.setQuery(term) }, label = { Text(term) }) }
                            }
                        }
                    }
                    // Categories, "For you", what is popular and new with value (device feedback 2026-10-19).
                    // Categories first, as coloured tiles (device feedback 2026-10-19, variant A).
                    item { Header(stringResource(Res.string.discover_categories_title), action = stringResource(Res.string.discover_all) + " ›") { allCategories = true } }
                    item { CategoryTiles(myCategories, category, vm::selectCategory) }
                    if (category == null && forYou.isNotEmpty()) {
                        item { Header(stringResource(Res.string.discover_for_you), stringResource(Res.string.discover_for_you_note)) }
                        item {
                            Carousel(forYou.map { it.show }, key = "f") { i, show ->
                                val r = forYou[i]
                                Card(show, when {
                                    r.recommendedBy != null -> stringResource(Res.string.discover_recommended_by, r.recommendedBy)
                                    r.category != null -> stringResource(Res.string.discover_because, categoryLabel(r.category))
                                    else -> null
                                }) { onPreview(show.feedUrl, show) }
                            }
                        }
                    }
                    // New value shows before the popular ones (device feedback 2026-10-19).
                    newValue?.takeIf { it.isNotEmpty() }?.let { list ->
                        item { Header(stringResource(Res.string.discover_new_value), stringResource(Res.string.discover_new_value_note)) }
                        item {
                            Carousel(list, key = "v") { _, show ->
                                Card(show, Category.of(show.categories).firstOrNull()?.let { "⚡ " + categoryLabel(it) } ?: "⚡ V4V", value = true) { onPreview(show.feedUrl, show) }
                            }
                        }
                    }
                    item { Header(stringResource(Res.string.discover_trending), listOfNotNull(category?.let { categoryLabel(it) }, stringResource(Res.string.discover_today)).joinToString(" · ")) }
                    when (val list = trending) {
                        null -> item { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                        else -> {
                            if (list.isEmpty()) item { Note(stringResource(Res.string.discover_explore_empty)) }
                            itemsIndexed(list.take(TRENDING), key = { _, it -> "t" + it.feedUrl }) { i, show ->
                                DirectoryRow(show, feedKey(show.feedUrl) in subscribed, sovereigncast = scShows != null && vm.isSovereigncast(show), rank = i + 1,
                                    busy = feedKey(show.feedUrl) in subscribing, onSubscribe = { vm.subscribe(show) { onPreview(show.feedUrl, show) } }) { onPreview(show.feedUrl, show) }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(Res.string.opml_section), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.opml_text), color = colors.onSurfaceVariant)
                            OutlinedButton(onClick = { actions.pickOpml { vm.importOpml(it) } }, enabled = importState != AddState.Working) {
                                Text(stringResource(Res.string.opml_import))
                            }
                            if (importState == AddState.Working) CircularProgressIndicator(Modifier.padding(4.dp))
                            (importState as? AddState.Imported)?.let { Text(stringResource(Res.string.result_imported, it.subscribed, it.already, it.failed)) }
                        }
                    }
                }
            }
        }
      }
    }
}

private val Source.label get() = when (this) { Source.PodcastIndex -> "Podcast Index"; Source.Fyyd -> "fyyd"; Source.Apple -> "Apple" }

@Composable
private fun Note(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

@Composable
private fun Header(text: String, note: String? = null, action: String? = null, onAction: () -> Unit = {}) = Row(
    Modifier.fillMaxWidth().padding(start = 16.dp, end = if (action != null) 4.dp else 16.dp, top = 16.dp, bottom = 4.dp),
    verticalAlignment = if (action != null) Alignment.CenterVertically else Alignment.Bottom,
) {
    Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (action != null) TextButton(onClick = onAction) { Text(action) }
}

/** How many popular shows the list shows. */
private const val TRENDING = 10

/**
 * Every main category as a coloured tile, two rows scrolled sideways — the
 * listener's own first, with an orange dot. A tap filters what is new and
 * popular below; the chosen tile is outlined, and tapped again it lets go.
 */
@Composable
private fun CategoryTiles(mine: List<Category>, selected: Category?, onSelect: (Category?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val large = LocalLargeRows.current
    val dark = colors.surface.luminance() < 0.5f
    val ordered = mine + Category.entries.filter { it !in mine }
    val state = rememberLazyGridState()
    // One chosen in the sheet or on a show's page comes into view.
    LaunchedEffect(selected) { selected?.let { state.animateScrollToItem(ordered.indexOf(it).coerceAtLeast(0)) } }
    val tile = if (large) 64.dp else 52.dp
    val dot = stringResource(Res.string.discover_mine)
    LazyHorizontalGrid(
        GridCells.Fixed(2), Modifier.fillMaxWidth().height(tile * 2 + 8.dp), state = state,
        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ordered, key = { it.name }) { c ->
            val chosen = c == selected
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.width(if (large) 156.dp else 124.dp).fillMaxHeight().clip(shape)
                    .background(colors.surface).background(c.hue.copy(alpha = if (dark) 0.32f else 0.2f))
                    .then(if (chosen) Modifier.border(2.dp, colors.onSurface, shape) else Modifier)
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onSelect(if (chosen) null else c) })
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { if (c in mine) stateDescription = dot },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(categoryLabel(c), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (c in mine) Box(Modifier.padding(start = 6.dp, bottom = 5.dp).size(7.dp).clip(CircleShape).background(Hopp.colors.value))
            }
        }
    }
}

/** Each category's own colour, laid over the page lightly. */
private val Category.hue: Color get() = when (this) {
    Category.Technology -> Color(0xFF3B6EA5); Category.Education -> Color(0xFF4F8A4B); Category.Business -> Color(0xFFC2873A)
    Category.News -> Color(0xFF7A7770); Category.Society -> Color(0xFFA0559A); Category.Science -> Color(0xFF2E8C8C)
    Category.History -> Color(0xFF8C6A45); Category.Health -> Color(0xFFD0613E); Category.Sports -> Color(0xFFC0443A)
    Category.Comedy -> Color(0xFFD9A62E); Category.Arts -> Color(0xFFB0507A); Category.Music -> Color(0xFF6A55B0)
    Category.Leisure -> Color(0xFF5E9E6E); Category.Kids -> Color(0xFFE08A5A); Category.Fiction -> Color(0xFF59609E)
    Category.TrueCrime -> Color(0xFF6E3B3B); Category.Film -> Color(0xFF4A5A70); Category.Religion -> Color(0xFF8E7BB5)
    Category.Government -> Color(0xFF55708A)
}

/** Shows side by side, scrolled sideways. */
@Composable
private fun Carousel(shows: List<DirectoryShow>, key: String, card: @Composable (Int, DirectoryShow) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(shows, key = { _, it -> key + it.feedUrl }) { i, show -> card(i, show) }
    }
}

/** A large cover, the title and why it is here. */
@Composable
private fun Card(show: DirectoryShow, why: String?, value: Boolean = false, onClick: () -> Unit) {
    val large = LocalLargeRows.current
    val size = if (large) 148.dp else 116.dp
    Column(Modifier.width(size).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(bottom = 4.dp)) {
        Cover(show.imageUrl, size, corner = 16.dp)
        Text(show.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (why != null) Text(why, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (value) Hopp.colors.valueText else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every main category; the listener's first, with an orange dot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(mine: List<Category>, onSelect: (Category) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.discover_categories_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(Res.string.discover_categories_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            val ordered = mine + Category.entries.filter { it !in mine }
            ordered.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { c ->
                        val dot = stringResource(Res.string.discover_mine)
                        Row(
                            Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerLow)
                                .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).clickable { onSelect(c) }.padding(horizontal = 14.dp, vertical = 12.dp)
                                .semantics(mergeDescendants = true) { if (c in mine) stateDescription = dot },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(categoryLabel(c), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (c in mine) Box(Modifier.size(8.dp).clip(CircleShape).background(Hopp.colors.value))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Search results and the podroll a size larger from a tablet's width. */
private val LocalLargeRows = staticCompositionLocalOf { false }

@Composable
private fun DirectoryRow(
    show: DirectoryShow, subscribed: Boolean, sovereigncast: Boolean = false,
    /** Its place among the popular shows. */
    rank: Int? = null,
    /** Subscribe from the list (device feedback 2026-10-15); null: no button. */
    busy: Boolean = false, onSubscribe: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val large = LocalLargeRows.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = if (large) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (large) 16.dp else 12.dp),
    ) {
        if (rank != null) Text("$rank", Modifier.widthIn(min = 18.dp), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
        if (large) Cover(show.imageUrl, 80.dp, corner = 14.dp) else Cover(show.imageUrl, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (large) 2.dp else 0.dp)) {
            Text(show.title, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                // Popular shows by their categories, the rest by author.
                (if (rank != null) Category.of(show.categories).take(2).map { categoryLabel(it) }.joinToString(" · ").ifEmpty { null } else null) ?: show.author,
                "⚡ V4V".takeIf { show.hasValue == true },
                show.source.label.takeIf { show.source != Source.PodcastIndex },
                stringResource(Res.string.show_subscribed).takeIf { subscribed },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sovereigncast) Row(Modifier.padding(top = 3.dp)) { ScBadge() }
        }
        if (onSubscribe != null) when {
            busy -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            subscribed -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, stringResource(Res.string.show_subscribed), tint = colors.onSurfaceVariant)
            }
            else -> IconButton(onClick = onSubscribe) { Icon(Icons.Rounded.Add, stringResource(Res.string.discover_add_button)) }
        }
    }
}
