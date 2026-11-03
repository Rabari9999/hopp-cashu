package space.sovereigncast.hopp.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import space.sovereigncast.hopp.data.Changelog
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.whats_new_all
import space.sovereigncast.hopp.resources.whats_new_count_fixes
import space.sovereigncast.hopp.resources.whats_new_count_new
import space.sovereigncast.hopp.resources.whats_new_dismiss
import space.sovereigncast.hopp.resources.whats_new_fixes
import space.sovereigncast.hopp.resources.whats_new_in_work
import space.sovereigncast.hopp.resources.whats_new_line
import space.sovereigncast.hopp.resources.whats_new_line_beta
import space.sovereigncast.hopp.resources.whats_new_new
import space.sovereigncast.hopp.resources.whats_new_title
import space.sovereigncast.hopp.resources.whats_new_yours
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.Languages
import space.sovereigncast.hopp.ui.components.formatDate

/** The changelog in the listener's language — German in German, English otherwise (Spanish has none yet). */
@Composable
private fun releases(): List<Changelog.Release> {
    val german = Languages.current() == "de"
    return remember(german) { Changelog.releases(german) }
}

/** What the installed version brought, in the listener's language; null when the changelog does not name it. */
@Composable
internal fun yourRelease(app: AppInfo = koinInject()): Changelog.Release? {
    val all = releases()
    return remember(all, app.version) { Changelog.yours(all, app.version, app.isWeb || Changelog.between(app.version)) }
}

/** "12 new features, 4 fixes" — the row in About. */
@Composable
internal fun whatsNewSummary(r: Changelog.Release): String = listOfNotNull(
    r.newCount.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.whats_new_count_new, it, it) },
    r.fixCount.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.whats_new_count_fixes, it, it) },
).joinToString(", ")

/**
 * "What's new" (maintainer 2026-11-03): the installed version open at the top, marked as the listener's,
 * the older ones below to open one by one, and the whole history on GitLab at the end. A build between
 * releases (a beta, Hopp Web) shows what is in the works as its own.
 */
@Composable
fun WhatsNewPage(app: AppInfo = koinInject()) {
    val all = releases()
    val yours = yourRelease(app)
    val uri = LocalUriHandler.current
    val shown = all.filter { !it.unreleased || it == yours }
    var open by rememberSaveable { mutableStateOf(setOfNotNull((yours ?: shown.firstOrNull())?.version)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { r ->
            val isOpen = r.version in open
            val toggle = { open = if (isOpen) open - r.version else open + r.version }
            ReleaseBlock(r, mine = r == yours, open = isOpen, onToggle = toggle)
        }
        val german = Languages.current() == "de"
        TextButton(onClick = { runCatching { uri.openUri(if (german) CHANGELOG_DE else CHANGELOG_EN) } }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(Res.string.whats_new_all) + " ↗")
        }
    }
}

@Composable
private fun ReleaseBlock(r: Changelog.Release, mine: Boolean, open: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 0f else -90f)
    val title = if (r.unreleased) stringResource(Res.string.whats_new_in_work) else r.version
    val date = r.date?.let { d -> runCatching { formatDate(kotlinx.datetime.LocalDate.parse(d)) }.getOrNull() ?: d }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).then(if (mine) Modifier.background(colors.surfaceVariant) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = title, onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { heading(); stateDescription = if (open) "open" else "closed" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (mine) Text(
                stringResource(Res.string.whats_new_yours),
                Modifier.clip(RoundedCornerShape(50)).background(space.sovereigncast.hopp.ui.theme.Hopp.colors.value).padding(horizontal = 8.dp, vertical = 2.dp),
                color = space.sovereigncast.hopp.ui.theme.Hopp.colors.onValue, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            )
            Text(date.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Icon(HoppIcons.ChevronDown, null, Modifier.size(22.dp).rotate(turn), tint = colors.onSurfaceVariant)
        }
        if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            r.sections.forEach { s ->
                Text(stringResource(if (s.fixes) Res.string.whats_new_fixes else Res.string.whats_new_new), Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                s.items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text(buildAnnotatedString {
                            item.area?.let { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$it: ") } }
                            append(item.text)
                        }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (!mine) HorizontalDivider(color = colors.outlineVariant)
    }
}

/** Once after an update, under Home's greeting (maintainer 2026-11-03): gone after a tap or ✕. */
@Composable
internal fun WhatsNewLine(r: Changelog.Release, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant)
            .clickable(onClick = onOpen).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✦", color = space.sovereigncast.hopp.ui.theme.Hopp.colors.valueText, fontWeight = FontWeight.Bold)
        Text(
            (if (r.unreleased) stringResource(Res.string.whats_new_line_beta) else stringResource(Res.string.whats_new_line, r.version)) +
                " · " + stringResource(Res.string.whats_new_title) + " ›",
            Modifier.weight(1f).padding(start = 10.dp, top = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
        )
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(Res.string.whats_new_dismiss), Modifier.size(18.dp), tint = colors.onSurfaceVariant) }
    }
}

private const val CHANGELOG_EN = "https://gitlab.com/nodesignal/hopp/-/blob/main/CHANGELOG.md"
private const val CHANGELOG_DE = "https://gitlab.com/nodesignal/hopp/-/blob/main/CHANGELOG.de.md"
