package space.sovereigncast.hopp.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import space.sovereigncast.hopp.MainActivity
import space.sovereigncast.hopp.R
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.SettingsRepository
import space.sovereigncast.hopp.playback.QuickBoost
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.value.WalletRepository

/** What the widget shows: the episode playing or last heard, and whether a boost button belongs there. */
data class WidgetNow(
    val title: String,
    val show: String,
    val chapter: String?,
    val artworkUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    /** The quick boost's amount, with a wallet; null: no boost button. */
    val boostSats: Int?,
    val skipBackSeconds: Int,
    val skipForwardSeconds: Int,
)

/**
 * The player on the home screen (0.3.1, maintainer 2026-10-26): looks and acts like the lock
 * screen — cover, show, chapter, title, time left, and back, play, forward, next chapter and the
 * quick boost. Four cells wide; one high it keeps title, play and boost. The playback service
 * tells it what changed ([WidgetState]); before anything played since the start, it shows the
 * episode last heard, paused.
 */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val lastHeard = if (WidgetState.now.value == null) WidgetState.lastHeard() else null
        provideContent { Live(lastHeard) }
    }

    /**
     * What plays, followed while the widget's session lasts (device feedback 2026-10-26: paused
     * from the widget, its button stayed "pause" — an update only recomposes a running session,
     * and what was read once when it started stayed as it was). [lastHeard]: until anything plays.
     */
    @Composable
    internal fun Live(lastHeard: WidgetNow?) {
        val live by WidgetState.now.collectAsState()
        val now = live ?: lastHeard
        val url = now?.artworkUrl
        val cover by produceState(Artwork.cached(url), url) { value = url?.let { Artwork.load(it) } }
        Content(now, cover)
    }

    /** What the widget draws for [now] — apart, for the tests. */
    @Composable
    internal fun Content(now: WidgetNow?, cover: Bitmap?) {
        val context = LocalContext.current
        val large = LocalSize.current.height >= LARGE.height
        Box(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(SURFACE).padding(if (large) 14.dp else 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (now == null) {
                Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.size(44.dp).cornerRadius(22.dp).background(BOOST), contentAlignment = Alignment.Center) {
                        Image(ImageProvider(R.drawable.ic_boost), null, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(ON_BOOST))
                    }
                    Spacer(GlanceModifier.width(12.dp))
                    Text(context.getString(R.string.widget_nothing), style = TextStyle(color = MUTED, fontSize = 14.sp))
                }
                return@Box
            }
            if (large) Column(GlanceModifier.fillMaxWidth()) {
                Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                    Cover(cover, 64)
                    Spacer(GlanceModifier.width(12.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(listOfNotNull(now.show, now.chapter).joinToString(" · "), maxLines = 1, style = TextStyle(color = MUTED, fontSize = 12.sp))
                        Text(now.title, maxLines = 2, style = TextStyle(color = ON_SURFACE, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        if (now.durationMs > 0) Text(
                            context.getString(R.string.widget_left, ((now.durationMs - now.positionMs).coerceAtLeast(0) / 60_000).toInt()),
                            style = TextStyle(color = MUTED, fontSize = 12.sp),
                        )
                    }
                }
                Spacer(GlanceModifier.height(10.dp))
                if (now.durationMs > 0) LinearProgressIndicator(
                    progress = (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp), color = ON_SURFACE, backgroundColor = TRACK,
                )
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(R.drawable.ic_widget_back, context.getString(R.string.skip_back, now.skipBackSeconds), actionRunCallback<SkipBack>())
                    Spacer(GlanceModifier.defaultWeight())
                    PlayButton(now.playing, 56)
                    Spacer(GlanceModifier.defaultWeight())
                    Button(R.drawable.ic_widget_forward, context.getString(R.string.skip_forward, now.skipForwardSeconds), actionRunCallback<SkipForward>())
                    Spacer(GlanceModifier.defaultWeight())
                    Button(R.drawable.ic_widget_next, context.getString(R.string.next_chapter), actionRunCallback<NextChapter>())
                    now.boostSats?.let { sats ->
                        Spacer(GlanceModifier.defaultWeight())
                        Row(
                            GlanceModifier.height(44.dp).cornerRadius(22.dp).background(BOOST).padding(horizontal = 14.dp)
                                .clickable(actionRunCallback<Boost>()).semantics { contentDescription = context.getString(R.string.quick_boost) + " $sats sats" },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(ImageProvider(R.drawable.ic_boost), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(ON_BOOST))
                            Spacer(GlanceModifier.width(6.dp))
                            Text(sats.toString(), style = TextStyle(color = ON_BOOST, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            } else Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                    Cover(cover, 48)
                    Spacer(GlanceModifier.width(10.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Text(now.title, maxLines = 1, style = TextStyle(color = ON_SURFACE, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        Text(now.show, maxLines = 1, style = TextStyle(color = MUTED, fontSize = 12.sp))
                    }
                }
                PlayButton(now.playing, 48)
                if (now.boostSats != null) {
                    Spacer(GlanceModifier.width(8.dp))
                    Box(
                        GlanceModifier.size(48.dp).cornerRadius(24.dp).background(BOOST).clickable(actionRunCallback<Boost>())
                            .semantics { contentDescription = context.getString(R.string.quick_boost) },
                        contentAlignment = Alignment.Center,
                    ) { Image(ImageProvider(R.drawable.ic_boost), null, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(ON_BOOST)) }
                }
            }
        }
    }

    @Composable
    private fun Cover(cover: Bitmap?, size: Int) {
        if (cover != null) Image(ImageProvider(cover), null, GlanceModifier.size(size.dp).cornerRadius(12.dp))
        else Box(GlanceModifier.size(size.dp).cornerRadius(12.dp).background(TRACK)) {}
    }

    @Composable
    private fun PlayButton(playing: Boolean, size: Int) {
        val context = LocalContext.current
        Box(
            GlanceModifier.size(size.dp).cornerRadius((size / 2).dp).background(ON_SURFACE).clickable(actionRunCallback<TogglePlay>())
                .semantics { contentDescription = context.getString(if (playing) R.string.widget_pause else R.string.widget_play) },
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play), null,
                GlanceModifier.size((size * 0.42).dp), colorFilter = ColorFilter.tint(SURFACE))
        }
    }

    @Composable
    private fun Button(icon: Int, label: String, action: androidx.glance.action.Action) {
        Box(GlanceModifier.size(44.dp).clickable(action).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            Image(ImageProvider(icon), null, GlanceModifier.size(26.dp), colorFilter = ColorFilter.tint(ON_SURFACE))
        }
    }

    companion object {
        val SMALL = DpSize(180.dp, 60.dp)
        val LARGE = DpSize(250.dp, 140.dp)
        // Hopp's colours (ui/theme/Color.kt), light and dark.
        private val SURFACE = ColorProvider(day = Color(0xFFF6F5F2), night = Color(0xFF1C1C1A))
        private val ON_SURFACE = ColorProvider(day = Color(0xFF0A0A0A), night = Color(0xFFECEBE7))
        private val MUTED = ColorProvider(day = Color(0xFF5E5D59), night = Color(0xFFA3A19B))
        private val TRACK = ColorProvider(day = Color(0xFFD6D4CE), night = Color(0xFF3A3935))
        private val BOOST = ColorProvider(day = Color(0xFFF7931A), night = Color(0xFFF7931A))
        private val ON_BOOST = ColorProvider(day = Color(0xFF0A0A0A), night = Color(0xFF0A0A0A))
    }
}

class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

/** The widget's buttons do what the lock screen's do. */
private val player: PlayerController get() = KoinPlatform.getKoin().get()

class TogglePlay : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = withContext(Dispatchers.Main) { player.togglePlay() }
}

class SkipBack : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = withContext(Dispatchers.Main) { player.skipBack() }
}

class SkipForward : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = withContext(Dispatchers.Main) { player.skipForward() }
}

class NextChapter : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = withContext(Dispatchers.Main) { player.next() }
}

class Boost : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = withContext(Dispatchers.Main) { QuickBoost.send(widgetScope) }
}

private val widgetScope = MainScope()

/** What plays, as the playback service last said — and the updates it asks for. */
object WidgetState {
    val now = MutableStateFlow<WidgetNow?>(null)

    /** Before anything played since the start: the episode last heard, paused where it was. */
    suspend fun lastHeard(): WidgetNow? = runCatching {
        val koin = KoinPlatform.getKoin()
        val item = koin.get<EpisodeRepository>().inProgress.first().firstOrNull() ?: return null
        val s = koin.get<SettingsRepository>().current()
        WidgetNow(item.title, item.podcastTitle.orEmpty(), null, item.imageUrl ?: item.podcastImageUrl, item.positionMs,
            (item.durationSeconds ?: 0) * 1000, playing = false, boostSats(s.quickBoostSats), s.skipBackSeconds, s.skipForwardSeconds)
    }.getOrNull()

    fun boostSats(quickBoostSats: Int): Int? =
        quickBoostSats.takeIf { it > 0 && KoinPlatform.getKoin().get<WalletRepository>().wallet.value != null }

    /** [next] shown on every Hopp widget, if it differs from what they show. */
    suspend fun show(context: Context, next: WidgetNow?) {
        if (now.value == next) return
        now.value = next
        runCatching { PlayerWidget().updateAll(context) }
    }
}

/** The cover, small enough for a widget's update — the last one kept, as it rarely changes. */
internal object Artwork {
    private var last: Pair<String, Bitmap>? = null

    /** The cover of [url], if it is the one loaded last. */
    fun cached(url: String?): Bitmap? = last?.takeIf { it.first == url }?.second

    suspend fun load(url: String): Bitmap? {
        last?.takeIf { it.first == url }?.let { return it.second }
        val bytes = withTimeoutOrNull(8_000) {
            runCatching { KoinPlatform.getKoin().get<HttpClient>().get(url).readRawBytes() }.getOrNull()
        } ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }?.also { last = url to it }
    }

    private const val SIZE = 192
}
