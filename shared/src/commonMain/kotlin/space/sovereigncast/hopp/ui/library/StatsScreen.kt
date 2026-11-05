package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.ListeningRepository
import space.sovereigncast.hopp.data.ListeningStats
import space.sovereigncast.hopp.data.StatsRange
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.stats_boosts
import space.sovereigncast.hopp.resources.stats_chapters
import space.sovereigncast.hopp.resources.stats_comments
import space.sovereigncast.hopp.resources.stats_days
import space.sovereigncast.hopp.resources.stats_episodes
import space.sovereigncast.hopp.resources.stats_empty
import space.sovereigncast.hopp.resources.stats_estimate
import space.sovereigncast.hopp.resources.stats_given
import space.sovereigncast.hopp.resources.stats_heard_all
import space.sovereigncast.hopp.resources.stats_heard_month
import space.sovereigncast.hopp.resources.stats_heard_year
import space.sovereigncast.hopp.resources.stats_hours
import space.sovereigncast.hopp.resources.stats_minutes
import space.sovereigncast.hopp.resources.stats_never_given
import space.sovereigncast.hopp.resources.stats_p20
import space.sovereigncast.hopp.resources.stats_per_hour
import space.sovereigncast.hopp.resources.stats_private
import space.sovereigncast.hopp.resources.stats_range_all
import space.sovereigncast.hopp.resources.stats_range_month
import space.sovereigncast.hopp.resources.stats_saved
import space.sovereigncast.hopp.resources.stats_saved_skips
import space.sovereigncast.hopp.resources.stats_saved_speed
import space.sovereigncast.hopp.resources.stats_share
import space.sovereigncast.hopp.resources.stats_share_text
import space.sovereigncast.hopp.resources.stats_since
import space.sovereigncast.hopp.resources.stats_streak
import space.sovereigncast.hopp.resources.stats_streaming
import space.sovereigncast.hopp.resources.stats_supported
import space.sovereigncast.hopp.resources.stats_title
import space.sovereigncast.hopp.resources.stats_top_episodes
import space.sovereigncast.hopp.resources.stats_top_shows
import space.sovereigncast.hopp.resources.stats_transcripts
import space.sovereigncast.hopp.resources.stats_value
import space.sovereigncast.hopp.resources.stats_zaps
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.components.formatDate
import space.sovereigncast.hopp.ui.components.formatSats
import space.sovereigncast.hopp.ui.theme.Hopp

class StatsViewModel(private val listening: ListeningRepository) : ViewModel() {
    private val _range = MutableStateFlow(StatsRange.Month)
    val range: StateFlow<StatsRange> = _range.asStateFlow()
    private val _stats = MutableStateFlow<ListeningStats?>(null)
    val stats: StateFlow<ListeningStats?> = _stats.asStateFlow()

    init { load() }

    fun pick(range: StatsRange) { _range.value = range; load() }

    private fun load() = viewModelScope.launch {
        val range = _range.value
        runCatching { listening.stats(range) }.getOrNull()?.let { if (_range.value == range) _stats.value = it }
    }
}

/**
 * What the listener heard and gave (maintainer 2026-10-26, after Pocket Casts' statistics): the
 * time, the time saved, the top shows and episodes, Value for Value per show, what Podcasting 2.0
 * was used for — every device together when sync is on, counted nowhere else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(vm: StatsViewModel = koinViewModel()) {
    val range by vm.range.collectAsState()
    val stats by vm.stats.collectAsState()
    val colors = MaterialTheme.colorScheme
    val year = space.sovereigncast.hopp.ui.components.currentYear()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(range == StatsRange.Month, { vm.pick(StatsRange.Month) }, { Text(stringResource(Res.string.stats_range_month)) })
            FilterChip(range == StatsRange.Year, { vm.pick(StatsRange.Year) }, { Text(year.toString()) })
            FilterChip(range == StatsRange.All, { vm.pick(StatsRange.All) }, { Text(stringResource(Res.string.stats_range_all)) })
        }
        val s = stats?.takeIf { it.range == range }
        if (s == null) { CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally)); return@Column }
        if (s.heardMs == 0L && s.givenMsat == 0L && s.estimatedBeforeMs == null) {
            Text(stringResource(Res.string.stats_empty), color = colors.onSurfaceVariant)
            return@Column
        }

        Card {
            Text(when (range) {
                StatsRange.Month -> stringResource(Res.string.stats_heard_month)
                StatsRange.Year -> stringResource(Res.string.stats_heard_year, year)
                StatsRange.All -> stringResource(Res.string.stats_heard_all)
            }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Text(listenDuration(s.heardMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
            // The days only once there is a tenth of one: "0.0 days of listening" said nothing (store screenshots 2026-11-04).
            val days = s.heardMs / 86_400_000.0
            Text(listOfNotNull(
                if (days >= 0.1) stringResource(Res.string.stats_days, decimal(days)) else null,
                org.jetbrains.compose.resources.pluralStringResource(Res.plurals.stats_episodes, s.episodes, s.episodes),
            ).joinToString(" · "), color = colors.onSurfaceVariant)
            Bars(s)
            if (s.longestStreakDays > 1) Text(stringResource(Res.string.stats_streak, s.longestStreakDays), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            s.since?.let { Text(stringResource(Res.string.stats_since, formatDate(it)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            s.estimatedBeforeMs?.let { Text(stringResource(Res.string.stats_estimate, listenDuration(it)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }

        if (s.savedBySpeedMs + s.savedBySkipsMs >= 60_000) Card {
            Text(stringResource(Res.string.stats_saved), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(listenDuration(s.savedBySpeedMs + s.savedBySkipsMs), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Line(stringResource(Res.string.stats_saved_speed), listenDuration(s.savedBySpeedMs))
            if (s.savedBySkipsMs > 0) Line(stringResource(Res.string.stats_saved_skips), listenDuration(s.savedBySkipsMs))
        }

        val heardShows = s.topShows.filter { it.heardMs > 0 }
        if (heardShows.isNotEmpty()) Card {
            Text(stringResource(Res.string.stats_top_shows), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val most = heardShows.first().heardMs.coerceAtLeast(1)
            heardShows.take(5).forEachIndexed { i, show ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(show.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listenDuration(show.heardMs))
                    }
                    Box(Modifier.fillMaxWidth(show.heardMs.toFloat() / most).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i == 0) Hopp.colors.value else Hopp.colors.value.copy(alpha = 0.35f)))
                }
            }
        }

        if (s.topEpisodes.isNotEmpty()) Card {
            Text(stringResource(Res.string.stats_top_episodes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            s.topEpisodes.take(5).forEachIndexed { i, e ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${i + 1}", fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (e.showTitle.isNotEmpty()) Text(e.showTitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Text(listenDuration(e.heardMs), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }

        if (s.givenMsat > 0 || heardShows.isNotEmpty()) ValueCard(s)

        if (s.chapters + s.transcripts + s.comments > 0) Card {
            Text(stringResource(Res.string.stats_p20), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(s.chapters, stringResource(Res.string.stats_chapters))
                Tile(s.transcripts, stringResource(Res.string.stats_transcripts))
                Tile(s.comments, stringResource(Res.string.stats_comments))
            }
        }

        Text(stringResource(Res.string.stats_private), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        val actions = LocalPlatformActions.current
        val share = stringResource(Res.string.stats_share_text, listenDuration(s.heardMs), formatSats(s.givenMsat / 1000))
        val title = stringResource(Res.string.stats_title)
        Button(
            onClick = { actions.shareText(share, title) }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Hopp.colors.value, contentColor = Hopp.colors.onValue),
        ) { Text(stringResource(Res.string.stats_share), fontWeight = FontWeight.Bold) }
    }
}

/** Value for Value: what went to the shows, how, and per hour heard — in the value colours, dark. */
@Composable
private fun ValueCard(s: ListeningStats) {
    val on = Color(0xFFF6F5F2)
    val muted = Color(0xFFA3A19B)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF0A0A0A)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.stats_value), color = on, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column {
            Text(formatSats(s.givenMsat / 1000) + " sats", color = Hopp.colors.value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text(stringResource(Res.string.stats_given), color = muted, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Res.string.stats_boosts to s.boostMsat, Res.string.stats_streaming to s.streamMsat, Res.string.stats_zaps to s.zapMsat).forEach { (label, msat) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF232220)).padding(10.dp)) {
                    Text(stringResource(label), color = muted, style = MaterialTheme.typography.labelMedium)
                    Text(formatSats(msat / 1000), color = on, fontWeight = FontWeight.Bold)
                }
            }
        }
        s.topShows.sortedByDescending { it.givenMsat }.take(5).forEach { show ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(show.title, Modifier.weight(1f), color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(8.dp))
                if (show.givenMsat > 0) {
                    Text(formatSats(show.givenMsat / 1000), color = on, fontWeight = FontWeight.Bold)
                    if (show.heardMs >= 600_000) Text(" · " + stringResource(Res.string.stats_per_hour, formatSats(show.givenMsat / 1000 * 3_600_000 / show.heardMs)), color = muted, style = MaterialTheme.typography.bodySmall)
                } else Text(stringResource(Res.string.stats_never_given), color = muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (s.showsHeard > 0) Text(stringResource(Res.string.stats_supported, s.showsSupported, s.showsHeard), color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** The range's bars: days of the month, months of the year, the years. */
@Composable
private fun Bars(s: ListeningStats) {
    val most = s.bars.maxOfOrNull { it.heardMs }?.coerceAtLeast(1) ?: return
    Row(
        Modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(if (s.bars.size > 20) 2.dp else 6.dp), verticalAlignment = Alignment.Bottom,
    ) {
        s.bars.forEachIndexed { i, b ->
            Box(Modifier.weight(1f).fillMaxHeight((b.heardMs.toFloat() / most).coerceAtLeast(0.02f)).clip(RoundedCornerShape(3.dp))
                .background(if (i == s.bars.lastIndex) Hopp.colors.value else Hopp.colors.value.copy(alpha = 0.35f)))
        }
    }
    if (s.bars.size <= 12) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.bars.forEach { Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
)

@Composable
private fun Line(label: String, value: String) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f))
    Text(value, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Tile(n: Long, label: String) = Column(
    Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 10.dp),
) {
    Text(formatSats(n), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "45 min", "3.5 h", "212 h". */
@Composable
/** Listening time in minutes, then hours — the statistics and the playlist's length. */
internal fun listenDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> stringResource(Res.string.stats_minutes, minutes.toInt())
        minutes < 600 -> stringResource(Res.string.stats_hours, decimal(minutes / 60.0))
        else -> stringResource(Res.string.stats_hours, formatSats(minutes / 60))
    }
}

/** One decimal, in the language's way: "8,8" or "8.8". */
private fun decimal(x: Double): String {
    val tenths = kotlin.math.round(x * 10).toLong()
    val sep = space.sovereigncast.hopp.ui.components.Languages.of(space.sovereigncast.hopp.ui.components.Languages.current()).decimal
    return "${tenths / 10}$sep${tenths % 10}"
}
