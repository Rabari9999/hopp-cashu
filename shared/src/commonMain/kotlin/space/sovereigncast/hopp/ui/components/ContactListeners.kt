package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.background
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
import space.sovereigncast.hopp.resources.contacts_no_recommendation

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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Overlapping, as Zapstore's "stacked or zapped by".
            Box {
                who.take(MAX_FACES).forEachIndexed { i, c -> Face(c, Modifier.offset(x = (i * 16).dp)) }
            }
            Text(
                pluralStringResource(Res.plurals.contacts_listen, who.size, who.size),
                Modifier.padding(start = (16 * (minOf(who.size, MAX_FACES) - 1) + 30).dp),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            )
        }
        Text(stringResource(Res.string.contacts_no_recommendation), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Face(c: Contact, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.size(24.dp).clip(CircleShape).border(1.5.dp, colors.surface, CircleShape).background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (c.picture != null) coil3.compose.AsyncImage(c.picture, c.name, Modifier.size(24.dp), contentScale = ContentScale.Crop)
        else Text((c.name ?: "N").take(1).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

private const val MAX_FACES = 5
