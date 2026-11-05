package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.favorites_title
import space.sovereigncast.hopp.resources.home_boosted
import space.sovereigncast.hopp.resources.home_recent
import space.sovereigncast.hopp.resources.home_section_move
import space.sovereigncast.hopp.resources.home_sections_reset
import space.sovereigncast.hopp.resources.home_sections_text
import space.sovereigncast.hopp.resources.home_take_part
import space.sovereigncast.hopp.resources.home_your_shows
import space.sovereigncast.hopp.resources.library_continue
import space.sovereigncast.hopp.resources.nav_new
import space.sovereigncast.hopp.resources.take_part_hidden_title
import space.sovereigncast.hopp.resources.take_part_hidden_note
import space.sovereigncast.hopp.resources.take_part_hidden_unknown_show
import space.sovereigncast.hopp.resources.take_part_hidden_all
import space.sovereigncast.hopp.resources.take_part_hidden_suggestions
import space.sovereigncast.hopp.resources.take_part_hidden_round
import space.sovereigncast.hopp.resources.take_part_hidden_unlock
import space.sovereigncast.hopp.resources.take_part_show_again
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.resources.queue_title

/**
 * Home's sections, which the listener may arrange and hide (device feedback
 * 2026-10-13: with many subscriptions Home grew long and mixed). The keys are
 * the ones Home already folds sections by (`homeCollapsed`); the greeting is
 * not one of them — it stays on top.
 */
object HomeSections {
    const val NEW = "new"
    /** "Continue listening", gone into the playlist ([QUEUE]) on 2026-11-03 (Dennis, maintainer): a saved
     *  order's place of it is the playlist's. */
    const val CONTINUE = "continue"
    const val YOUR_SHOWS = "yourshows"
    const val TAKE_PART = "shows"
    const val FAVORITES = "favorites"
    const val BOOSTED = "boosted"
    const val RECENT = "recent"
    const val QUEUE = "queue"

    /** Hopp's own order since 0.4.0: what to take part in on top, always (maintainer 2026-11-03), then
     *  listening — the playlist: what plays and what comes next (Dennis, 2026-11-02 and -03) — what is
     *  new, the shows, what was heard last. */
    val default = listOf(TAKE_PART, QUEUE, NEW, YOUR_SHOWS, FAVORITES, BOOSTED, RECENT)

    /** In a large window, the left column (maintainer 2026-11-03): what is listened to; the rest goes right. */
    val listening = setOf(QUEUE, NEW, RECENT)
    /** From this width Home is two columns. */
    val TWO_COLUMNS = 1000.dp

    /** Hopp's own order before 0.4.0: kept for a listener who had it, "Up next" put in after "Continue". */
    val before033 = listOf(TAKE_PART, YOUR_SHOWS, CONTINUE, NEW, FAVORITES, BOOSTED, RECENT)

    val titles: Map<String, StringResource> = mapOf(
        NEW to Res.string.nav_new,
        QUEUE to Res.string.queue_title,
        YOUR_SHOWS to Res.string.home_your_shows,
        TAKE_PART to Res.string.home_take_part,
        FAVORITES to Res.string.favorites_title,
        BOOSTED to Res.string.home_boosted,
        RECENT to Res.string.home_recent,
    )

    /**
     * The order Home shows its sections in: the listener's, once they have moved one —
     * a section Hopp adds later goes where Hopp's own order puts it among them, one it
     * has dropped ("From your shows", 2026-10-13) drops out — and otherwise Hopp's.
     */
    fun order(saved: List<String>): List<String> {
        val own = default
        // The playlist where "Continue listening" was, it having been the first of the two.
        val merged = if (CONTINUE in saved) saved.filter { it != QUEUE }.map { if (it == CONTINUE) QUEUE else it } else saved
        val known = merged.filter { it in titles }.distinct()
        if (known.isEmpty()) return own
        val result = known.toMutableList()
        own.forEachIndexed { i, key ->
            if (key in result) return@forEachIndexed
            val before = own.take(i).lastOrNull { it in result }
            result.add(if (before == null) 0 else result.indexOf(before) + 1, key)
        }
        return result
    }
}

/** The settings page: drag to arrange, switch to hide, and back to Hopp's order; below,
 *  what was hidden under "Take part", each to show again ([showTitles] by podcast:guid). */
@Composable
fun HomeSectionsEditor(settings: Settings, showTitles: Map<String, String> = emptyMap(), note: String? = null, onChange: ((Settings) -> Settings) -> Unit) {
    val saved = HomeSections.order(settings.homeOrder)
    var order by remember { mutableStateOf(saved) }
    LaunchedEffect(settings.homeOrder) { order = HomeSections.order(settings.homeOrder) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    // The first item is the explanation; the sections start at index 1.
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index !in 1..order.size || to.index !in 1..order.size) return@rememberReorderableLazyListState
        order = order.toMutableList().apply { add(to.index - 1, removeAt(from.index - 1)) }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "text") {
            Text(
                note ?: stringResource(Res.string.home_sections_text), Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
        items(order, key = { it }) { key ->
            ReorderableItem(reorderable, key = key) { dragging ->
                Surface(tonalElevation = if (dragging) 4.dp else 0.dp) {
                    val title = stringResource(HomeSections.titles.getValue(key))
                    val moveLabel = stringResource(Res.string.home_section_move, title)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = {},
                            modifier = Modifier.draggableHandle(onDragStopped = { onChange { it.copy(homeOrder = order) } })
                                .semantics { contentDescription = moveLabel },
                        ) { Icon(HoppIcons.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = key !in settings.homeHidden,
                            onCheckedChange = { on -> onChange { s -> s.copy(homeHidden = if (on) s.homeHidden - key else s.homeHidden + key) } },
                            modifier = Modifier.semantics { contentDescription = title },
                        )
                    }
                }
            }
        }
        item(key = "reset") {
            if (settings.homeOrder.isNotEmpty() || settings.homeHidden.isNotEmpty()) OutlinedButton(
                onClick = { onChange { it.copy(homeOrder = emptyList(), homeHidden = emptySet()) } },
                modifier = Modifier.padding(16.dp),
            ) { Text(stringResource(Res.string.home_sections_reset)) }
        }
        // Newest first: the one just hidden by mistake is at the top.
        val hiddenHere = settings.takePartHidden.reversed().mapNotNull { k -> TakePart.parse(k)?.let { k to it } }
        if (hiddenHere.isNotEmpty()) {
            item(key = "tp-title") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                    Text(stringResource(Res.string.take_part_hidden_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.take_part_hidden_note), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(hiddenHere, key = { "tp-${it.first}" }) { (key, entry) ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(showTitles[entry.podcastGuid] ?: stringResource(Res.string.take_part_hidden_unknown_show), style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(when (entry.scope) {
                            TakePart.Scope.Show -> Res.string.take_part_hidden_all
                            TakePart.Scope.Suggestions -> Res.string.take_part_hidden_suggestions
                            TakePart.Scope.Round -> Res.string.take_part_hidden_round
                            TakePart.Scope.Unlock -> Res.string.take_part_hidden_unlock
                        }), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onChange { it.copy(takePartHidden = it.takePartHidden - key) } }) {
                        Text(stringResource(Res.string.take_part_show_again))
                    }
                }
            }
        }
    }
}
