package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.data.MAX_SKIP_SECONDS
import space.sovereigncast.hopp.data.Show
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.per_show_custom
import space.sovereigncast.hopp.resources.per_show_default
import space.sovereigncast.hopp.resources.per_show_like_all
import space.sovereigncast.hopp.resources.per_show_none
import space.sovereigncast.hopp.resources.per_show_outro_chip
import space.sovereigncast.hopp.resources.per_show_intro_chip
import space.sovereigncast.hopp.resources.per_show_search
import space.sovereigncast.hopp.resources.per_show_text
import space.sovereigncast.hopp.resources.show_playback
import space.sovereigncast.hopp.resources.show_playback_note
import space.sovereigncast.hopp.resources.show_skip_intro
import space.sovereigncast.hopp.resources.show_skip_intro_hint
import space.sovereigncast.hopp.resources.show_skip_off
import space.sovereigncast.hopp.resources.show_skip_outro
import space.sovereigncast.hopp.resources.show_skip_outro_hint
import space.sovereigncast.hopp.resources.show_skip_rule
import space.sovereigncast.hopp.resources.show_speed_default
import space.sovereigncast.hopp.resources.speed_title
import space.sovereigncast.hopp.ui.player.formatSpeed
import space.sovereigncast.hopp.ui.theme.Hopp

/** Whether [this] plays other than every show: its own speed, or seconds skipped. */
val Show.hasOwnPlayback get() = playbackSpeed != null || skipIntroSeconds > 0 || skipOutroSeconds > 0

/**
 * Every show's own playback in one place (maintainer 2026-10-26: in the show's menu alone it
 * is easily missed): those with their own first, each with what it does differently, then
 * the rest. A tap opens the same sheet the show's menu does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerShowPage(vm: SettingsViewModel) {
    val shows by vm.shows.collectAsState()
    val settings by vm.current.collectAsState()
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    val found = query.trim().let { q -> if (q.isEmpty()) shows else shows.filter { it.title.contains(q, ignoreCase = true) } }
    val (own, rest) = found.partition { it.hasOwnPlayback }
    val defaultSpeed = settings?.defaultSpeed ?: 1f
    val like = stringResource(Res.string.per_show_default, formatSpeed(defaultSpeed))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                stringResource(Res.string.per_show_text), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            )
        }
        if (shows.isEmpty()) item {
            Text(stringResource(Res.string.per_show_none), Modifier.padding(16.dp), color = colors.onSurfaceVariant)
        }
        // Worth it only with more shows than a screen holds.
        if (shows.size > 8) item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(Res.string.per_show_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
            )
        }
        if (own.isNotEmpty()) {
            item { Heading(stringResource(Res.string.per_show_custom, own.size)) }
            items(own, key = { it.id }) { s ->
                ShowRow(s, onClick = { open = s.id }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        s.playbackSpeed?.let { Badge("${formatSpeed(it)}×") }
                        if (s.skipIntroSeconds > 0) Badge(stringResource(Res.string.per_show_intro_chip, s.skipIntroSeconds))
                        if (s.skipOutroSeconds > 0) Badge(stringResource(Res.string.per_show_outro_chip, s.skipOutroSeconds))
                    }
                }
            }
        }
        if (rest.isNotEmpty()) {
            item { Heading(stringResource(Res.string.per_show_like_all, rest.size)) }
            items(rest, key = { it.id }) { s ->
                ShowRow(s, onClick = { open = s.id }) {
                    Text(like, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
    // By id, so the sheet shows each change as the list does.
    shows.firstOrNull { it.id == open }?.let { s ->
        ShowPlaybackSheet(
            s, onSpeed = { vm.setShowSpeed(s.id, it) }, onIntro = { vm.setShowSkip(s.id, intro = it) },
            onOutro = { vm.setShowSkip(s.id, outro = it) },
        ) { open = null }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun ShowRow(show: Show, onClick: () -> Unit, detail: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(show.imageUrl, 48.dp, corner = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(show.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail()
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Badge(text: String) = Text(
    text, Modifier.clip(RoundedCornerShape(10.dp)).background(Hopp.colors.value.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp),
    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
)

/**
 * The show's own playback (maintainer 2026-10-26): its speed, and the seconds skipped at the
 * start and the end of each episode — AntennaPod's "skip intro / ending". On every device (sync).
 * From the show's menu and from the settings' "Per show".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ShowPlaybackSheet(show: Show, onSpeed: (Float?) -> Unit, onIntro: (Int) -> Unit, onOutro: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Open in full: half open, the end's choices lay below the fold.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.show_playback), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(Res.string.show_playback_note, show.title), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.speed_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = show.playbackSpeed == null, onClick = { onSpeed(null) }, label = { Text(stringResource(Res.string.show_speed_default)) })
                    PlayerController.SPEED_CHOICES.forEach { v ->
                        val own = show.playbackSpeed
                        FilterChip(selected = own != null && kotlin.math.abs(own - v) < 0.01f, onClick = { onSpeed(v) }, label = { Text("${formatSpeed(v)}×") })
                    }
                }
            }
            SkipChoice(stringResource(Res.string.show_skip_intro), stringResource(Res.string.show_skip_intro_hint), show.skipIntroSeconds, onIntro)
            SkipChoice(stringResource(Res.string.show_skip_outro), stringResource(Res.string.show_skip_outro_hint), show.skipOutroSeconds, onOutro)
            Text(
                stringResource(Res.string.show_skip_rule), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHigh).padding(12.dp),
            )
        }
    }
}

@Composable
private fun SkipChoice(title: String, hint: String, seconds: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val off = stringResource(Res.string.show_skip_off)
        space.sovereigncast.hopp.ui.value.ChoicesOrOwn(SKIP_CHOICES, seconds, 0..MAX_SKIP_SECONDS, title, "s", { if (it == 0) off else "$it s" }, onPick)
    }
}

/** What a show skips most often: nothing, a jingle, an intro with an ad. */
private val SKIP_CHOICES = listOf(0, 10, 20, 30, 45, 60, 90)
