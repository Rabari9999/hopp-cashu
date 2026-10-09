package space.sovereigncast.hopp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.model.Person
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.person_role_cohost
import space.sovereigncast.hopp.resources.person_role_editor
import space.sovereigncast.hopp.resources.person_role_guest
import space.sovereigncast.hopp.resources.person_role_host
import space.sovereigncast.hopp.resources.person_role_producer
import space.sovereigncast.hopp.resources.person_group_audio_post
import space.sovereigncast.hopp.resources.person_group_audio_production
import space.sovereigncast.hopp.resources.person_group_cast
import space.sovereigncast.hopp.resources.person_group_visuals
import space.sovereigncast.hopp.resources.person_group_writing

/** The namespace's person taxonomy, as far as feeds use it; anything else as written. */
private val ROLES: Map<String, StringResource> = mapOf(
    "host" to Res.string.person_role_host, "co-host" to Res.string.person_role_cohost, "guest" to Res.string.person_role_guest,
    "producer" to Res.string.person_role_producer, "editor" to Res.string.person_role_editor,
)
private val GROUPS: Map<String, StringResource> = mapOf(
    "cast" to Res.string.person_group_cast, "writing" to Res.string.person_group_writing,
    "audio production" to Res.string.person_group_audio_production, "audio post-production" to Res.string.person_group_audio_post,
    "visuals" to Res.string.person_group_visuals,
)

@Composable
private fun label(value: String?, known: Map<String, StringResource>): String? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return known[v.lowercase()]?.let { stringResource(it) } ?: v.replaceFirstChar { it.uppercase() }
}

/** Where a person's link goes, as a reader recognises it: "primal.net". */
internal fun linkHost(href: String): String? =
    Regex("^https?://(?:www\\.)?([^/?#]+)", RegexOption.IGNORE_CASE).find(href.trim())?.groupValues?.get(1)?.lowercase()

/**
 * One `podcast:person` with everything the feed gives (device feedback
 * 2026-10-09): picture (or the initial), name, role and group, and where
 * the link leads — a tap follows it.
 */
@Composable
fun PersonRow(p: Person, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    val href = p.href?.takeIf { linkHost(it) != null }
    Row(
        modifier.fillMaxWidth().then(if (href != null) Modifier.clickable { runCatching { uri.openUri(href) } } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(p.name.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            if (p.img != null) AsyncImage(p.img, null, Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOfNotNull(label(p.role, ROLES), label(p.group, GROUPS)).distinct().takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            href?.let { linkHost(it) }?.let { host ->
                Text("$host ↗", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
            }
        }
    }
}
