package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.Show
import space.sovereigncast.hopp.data.Tags
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.home_edit_done
import space.sovereigncast.hopp.resources.tag_delete
import space.sovereigncast.hopp.resources.tag_delete_confirm
import space.sovereigncast.hopp.resources.tag_rename
import space.sovereigncast.hopp.resources.tag_shows
import space.sovereigncast.hopp.resources.tags_add
import space.sovereigncast.hopp.resources.tags_new
import space.sovereigncast.hopp.resources.tags_note
import space.sovereigncast.hopp.resources.tags_title
import space.sovereigncast.hopp.resources.tags_which
import space.sovereigncast.hopp.resources.tags_create
import space.sovereigncast.hopp.resources.per_show_search
import androidx.compose.foundation.layout.heightIn

/**
 * A show's tags (maintainer 2026-11-03, as in AntennaPod): every tag the listener has, picked or not with a
 * tap, and a new one — any number per show, only the listener's own. From the show's menu.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagsSheet(show: Show, allTags: List<String>, onTags: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf("") }
    val add = { val t = adding.trim(); if (t.isNotEmpty()) { onTags(show.tags + t); adding = "" } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.tags_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(show.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val shown = Tags.clean(allTags + show.tags)
            if (shown.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { t ->
                    val on = show.tags.any { it.equals(t, ignoreCase = true) }
                    TagChip(t, on, onClick = { onTags(if (on) show.tags.filterNot { it.equals(t, ignoreCase = true) } else show.tags + t) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    adding, { adding = it.replace("\n", "").take(Tags.MAX_LENGTH) }, Modifier.weight(1f),
                    placeholder = { Text(stringResource(Res.string.tags_new)) }, singleLine = true,
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                )
                OutlinedButton(onClick = add, enabled = adding.isNotBlank()) { Text(stringResource(Res.string.tags_add)) }
            }
            Text(stringResource(Res.string.tags_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.home_edit_done)) }
        }
    }
}

/**
 * One tag's own page — from a long press on it in the library (maintainer 2026-11-03): every show, ticked where
 * it has the tag, so many get it at once; the tag renamed or deleted on all of them. Back when it is gone.
 */
@Composable
fun TagScreen(tag: String, onGone: () -> Unit, podcasts: PodcastRepository = koinInject()) {
    val shows by podcasts.shows.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(tag) }
    var draft by rememberSaveable { mutableStateOf(tag) }
    var confirm by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val has = { s: Show -> s.tags.any { it.equals(name, ignoreCase = true) } }
    val sorted = shows.sortedWith(compareByDescending<Show> { has(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it.replace("\n", "").take(Tags.MAX_LENGTH) }, Modifier.weight(1f), singleLine = true)
                val renamed = draft.trim()
                OutlinedButton(onClick = { scope.launch { podcasts.renameTag(name, renamed); name = renamed } },
                    enabled = renamed.isNotEmpty() && renamed != name) { Text(stringResource(Res.string.tag_rename)) }
            }
        }
        item {
            Text(stringResource(Res.string.tag_shows) + " · " + shows.count(has), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
        items(sorted, key = { it.id }) { s ->
            val on = has(s)
            val toggle = { scope.launch { podcasts.setTags(s.id, if (on) s.tags.filterNot { it.equals(name, ignoreCase = true) } else s.tags + name) }; Unit }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Cover(s.imageUrl, 40.dp, corner = 8.dp)
                Text(s.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Checkbox(on, onCheckedChange = { toggle() })
            }
        }
        item {
            TextButton(onClick = { confirm = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Text(stringResource(Res.string.tag_delete), color = colors.error)
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Text(stringResource(Res.string.tag_delete_confirm, name)) },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { podcasts.renameTag(name, null); onGone() } }) { Text(stringResource(Res.string.tag_delete), color = colors.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * A new tag from the library (maintainer 2026-11-03): its name and the shows that get it, in one step —
 * then the library shows them under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTagSheet(shows: List<Show>, onCreate: (String, Set<Long>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    var query by remember { mutableStateOf("") }
    val found = query.trim().let { q -> if (q.isEmpty()) shows else shows.filter { it.title.contains(q, ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.tags_new), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(name, { name = it.replace("\n", "").take(Tags.MAX_LENGTH) }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(Res.string.tags_new)) })
            Text(stringResource(Res.string.tags_which), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Worth it only with more shows than a screen holds — as "Per show".
            if (shows.size > 8) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(Res.string.per_show_search)) })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(found, key = { it.id }) { s ->
                    val on = s.id in picked
                    Row(
                        Modifier.fillMaxWidth().clickable { picked = if (on) picked - s.id else picked + s.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Cover(s.imageUrl, 36.dp, corner = 8.dp)
                        Text(s.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Checkbox(on, onCheckedChange = { picked = if (on) picked - s.id else picked + s.id })
                    }
                }
            }
            Button(onClick = { onCreate(name, picked) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.tags_create) + if (picked.isNotEmpty()) " · " + picked.size else "")
            }
        }
    }
}
