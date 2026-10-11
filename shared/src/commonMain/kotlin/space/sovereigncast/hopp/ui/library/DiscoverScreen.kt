package space.sovereigncast.hopp.ui.library

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
import space.sovereigncast.hopp.resources.discover_podroll
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
    val podroll by vm.podroll.collectAsState()
    val subscribed by vm.subscribed.collectAsState()
    val importState by vm.import.collectAsState()
    val recent by vm.recent.collectAsState()
    val settings by settingsVm.current.collectAsState()
    val actions = LocalPlatformActions.current
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val q = query.trim()
    val address = q.takeIf { '.' in it }?.let(FeedFetcher::normalize)

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

        when (val s = search) {
            SearchUi.Searching -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is SearchUi.Done -> {
                val r = s.result
                when {
                    r.unavailable -> item { Note(stringResource(Res.string.discover_unavailable)) }
                    r.shows.isEmpty() && r.failed.isEmpty() -> item { Note(stringResource(Res.string.discover_no_results)) }
                }
                if (r.failed.isNotEmpty()) item { Note(stringResource(Res.string.discover_failed, r.failed.joinToString(", ") { it.label })) }
                items(r.shows, key = { "r" + it.feedUrl }) { show ->
                    DirectoryRow(show, feedKey(show.feedUrl) in subscribed, sovereigncast = scShows != null && vm.isSovereigncast(show)) {
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
                if (podroll.isNotEmpty()) {
                    item { Header(stringResource(Res.string.discover_podroll)) }
                    items(podroll, key = { "p" + it.feedUrl }) { show -> DirectoryRow(show, false, sovereigncast = vm.isSovereigncast(show)) { onPreview(show.feedUrl, show) } }
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

private val Source.label get() = when (this) { Source.PodcastIndex -> "Podcast Index"; Source.Fyyd -> "fyyd"; Source.Apple -> "Apple" }

@Composable
private fun Note(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

@Composable
private fun Header(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))

@Composable
private fun DirectoryRow(show: DirectoryShow, subscribed: Boolean, sovereigncast: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(show.imageUrl, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(show.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                show.author,
                "⚡ V4V".takeIf { show.hasValue == true },
                show.source.label.takeIf { show.source != Source.PodcastIndex },
                stringResource(Res.string.show_subscribed).takeIf { subscribed },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sovereigncast) Row(Modifier.padding(top = 3.dp)) { ScBadge() }
        }
    }
}
