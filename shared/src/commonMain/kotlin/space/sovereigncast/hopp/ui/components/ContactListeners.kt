package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.contacts.Contact
import space.sovereigncast.hopp.contacts.ContactsRepository
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.contacts_listen
import space.sovereigncast.hopp.resources.contacts_acted
import space.sovereigncast.hopp.resources.contacts_no_recommendation
import space.sovereigncast.hopp.resources.contacts_title
import space.sovereigncast.hopp.resources.contacts_did_share
import space.sovereigncast.hopp.resources.contacts_did_boost
import space.sovereigncast.hopp.resources.contacts_did_comment

/**
 * Which of the listener's Nostr contacts listen to a show (ADR 0024, 7): their avatars, a count,
 * and that a subscription is no recommendation. Nothing unless the listener looks at it — and so
 * nothing without a Nostr identity.
 */
@Composable
fun ContactListeners(guid: String?, feedUrl: String, modifier: Modifier = Modifier, contacts: ContactsRepository = koinInject()) {
    val state by contacts.state.collectAsState()
    if (state.note?.seeing != true) return
    val who = state.read?.listeners(guid, feedUrl).orEmpty()
    if (who.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Overlapping, as Zapstore's "stacked or zapped by".
            Box {
                who.take(MAX_FACES).forEachIndexed { i, c -> Face(c, Modifier.offset(x = (i * 16).dp)) }
            }
            // Boosted or commented on in public by one of them: said so (ADR 0024, 6).
            val acted = guid != null && who.any { c -> c.signals.any { it.show.equals(guid, ignoreCase = true) } }
            Text(
                pluralStringResource(if (acted) Res.plurals.contacts_acted else Res.plurals.contacts_listen, who.size, who.size),
                Modifier.padding(start = (16 * (minOf(who.size, MAX_FACES) - 1) + 30).dp),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            )
        }
        Text(stringResource(Res.string.contacts_no_recommendation), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (open) WhoSheet(who, guid, feedUrl) { open = false }
}

/** Who of the contacts, and what each did: shares the subscription, boosted, commented — with their Nostr profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhoSheet(who: List<Contact>, guid: String?, feedUrl: String, onDismiss: () -> Unit) {
    val actions = space.sovereigncast.hopp.ui.LocalPlatformActions.current
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.contacts_title), style = MaterialTheme.typography.titleLarge)
            who.forEach { c ->
                val mine = c.signals.filter { guid != null && it.show.equals(guid, ignoreCase = true) }
                val what = listOfNotNull(
                    stringResource(Res.string.contacts_did_share).takeIf { c.shows.any { s -> s.url == feedUrl || (guid != null && s.guid.equals(guid, ignoreCase = true)) } },
                    stringResource(Res.string.contacts_did_boost).takeIf { mine.any { it.boost } },
                    stringResource(Res.string.contacts_did_comment).takeIf { mine.any { !it.boost } },
                ).joinToString(" · ")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { actions.openNostr(space.sovereigncast.hopp.nostr.npub(c.pubkey)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Face(c, Modifier, 36)
                    Column(Modifier.weight(1f)) {
                        Text(c.name ?: space.sovereigncast.hopp.nostr.npub(c.pubkey).take(14) + "…", fontWeight = FontWeight.Medium)
                        Text(what, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(stringResource(Res.string.contacts_no_recommendation), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Face(c: Contact, modifier: Modifier, size: Int = 24) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.size(size.dp).clip(CircleShape).border(1.5.dp, colors.surface, CircleShape).background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (c.picture != null) coil3.compose.AsyncImage(c.picture, c.name, Modifier.size(size.dp), contentScale = ContentScale.Crop)
        else Text((c.name ?: "N").take(1).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private const val MAX_FACES = 5
