package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.contacts.ContactsRepository
import space.sovereigncast.hopp.data.Show
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.contacts_hidden_group
import space.sovereigncast.hopp.resources.contacts_hidden_note
import space.sovereigncast.hopp.resources.contacts_share_all
import space.sovereigncast.hopp.resources.contacts_share_none
import space.sovereigncast.hopp.resources.contacts_shared_group
import space.sovereigncast.hopp.resources.contacts_shared_text
import space.sovereigncast.hopp.resources.contacts_shared_text_off
import space.sovereigncast.hopp.resources.per_show_none
import space.sovereigncast.hopp.resources.per_show_search
import space.sovereigncast.hopp.resources.take_part_undo

/**
 * Every show and whether it is shared with mutual Nostr contacts (ADR 0024, 4a), in one place
 * (maintainer 2026-11-02: in each show's menu alone it meant opening every show). Shown with
 * sharing off too: a show left out before anything goes out never goes out. A switch here sets
 * what the show's menu does, and syncs with the subscriptions.
 */
@Composable
fun SharedShowsPage(vm: SettingsViewModel, contacts: ContactsRepository = koinInject()) {
    val shows by vm.shows.collectAsState()
    val state by contacts.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    // What "all" or "none" changed, to take back: the ids and how they were.
    var before by remember { mutableStateOf<Map<Long, Boolean>?>(null) }
    val found = query.trim().let { q -> if (q.isEmpty()) shows else shows.filter { it.title.contains(q, ignoreCase = true) } }
    val (hidden, shared) = found.partition { it.contactsHidden }
    val all = { on: Boolean ->
        before = shows.associate { it.id to !it.contactsHidden }
        vm.setSharedWithContacts(shows.filter { it.contactsHidden == on }.map { it.id }, on)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(if (state.note?.sharing == true) Res.string.contacts_shared_text else Res.string.contacts_shared_text_off),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            )
        }
        if (shows.isEmpty()) item {
            Text(stringResource(Res.string.per_show_none), Modifier.padding(16.dp), color = colors.onSurfaceVariant)
        }
        if (shows.size > 1) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { all(true) }, enabled = shows.any { it.contactsHidden }) { Text(stringResource(Res.string.contacts_share_all)) }
                TextButton(onClick = { all(false) }, enabled = shows.any { !it.contactsHidden }) { Text(stringResource(Res.string.contacts_share_none)) }
                before?.let { was ->
                    TextButton(onClick = {
                        was.entries.groupBy({ it.value }, { it.key }).forEach { (on, ids) -> vm.setSharedWithContacts(ids, on) }
                        before = null
                    }) { Text(stringResource(Res.string.take_part_undo)) }
                }
            }
        }
        // Worth it only with more shows than a screen holds — as "Per show".
        if (shows.size > 8) item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(Res.string.per_show_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
            )
        }
        if (shared.isNotEmpty()) {
            item { Group(stringResource(Res.string.contacts_shared_group, shared.size)) }
            items(shared, key = { it.id }) { s -> SharedRow(s, null) { on -> before = null; vm.setSharedWithContacts(listOf(s.id), on) } }
        }
        if (hidden.isNotEmpty()) {
            item { Group(stringResource(Res.string.contacts_hidden_group, hidden.size)) }
            items(hidden, key = { it.id }) { s -> SharedRow(s, stringResource(Res.string.contacts_hidden_note)) { on -> before = null; vm.setSharedWithContacts(listOf(s.id), on) } }
        }
    }
}

@Composable
private fun Group(text: String) = Text(
    text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun SharedRow(show: Show, note: String?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(show.contactsHidden) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(show.imageUrl, 44.dp, corner = 10.dp)
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(show.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(!show.contactsHidden, onCheckedChange = onChange)
    }
}
