package space.sovereigncast.hopp.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.first
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.R
import space.sovereigncast.hopp.data.DownloadRepository
import space.sovereigncast.hopp.data.DownloadState
import space.sovereigncast.hopp.data.EpisodeItem
import space.sovereigncast.hopp.data.EpisodeRepository
import space.sovereigncast.hopp.data.PodcastRepository
import space.sovereigncast.hopp.data.QueueRepository

/**
 * What Android Auto (and any other media browser) sees, ADR 0012: New, Up
 * next, Downloads and the Library with its shows. Browsable ids are fixed
 * strings or `show:<id>`; playable ones are `ep:<id>`, resolved to the
 * engine's plain episode id when they are played.
 */
class MediaTree(
    private val context: Context,
    private val podcasts: PodcastRepository,
    private val episodes: EpisodeRepository,
    private val queue: QueueRepository,
    private val downloads: DownloadRepository,
) {
    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name))

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(NEW, context.getString(R.string.auto_new)),
            folder(QUEUE, context.getString(R.string.auto_queue)),
            folder(DOWNLOADS, context.getString(R.string.auto_downloads)),
            folder(LIBRARY, context.getString(R.string.auto_library)),
        )
        parentId == NEW -> episodes.inbox.first().map(::playable)
        parentId == QUEUE -> queue.items.first().map(::playable)
        parentId == DOWNLOADS -> downloads.downloads.first().filter { it.downloadState == DownloadState.DONE }.map(::playable)
        parentId == LIBRARY -> podcasts.shows.first().map { folder(SHOW + it.id, it.title, it.imageUrl, MediaMetadata.MEDIA_TYPE_PODCAST) }
        parentId.startsWith(SHOW) -> parentId.removePrefix(SHOW).toLongOrNull()
            ?.let { id -> episodes.ofPodcast(id).first().take(SHOW_EPISODES).map(::playable) }.orEmpty()
        else -> emptyList()
    }

    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> root()
        mediaId.startsWith(EP) -> episode(mediaId)?.let(::playable)
        else -> children(ROOT).firstOrNull { it.mediaId == mediaId }
    }

    /** The item as the player needs it — with its address, under the
     *  engine's id — and where to start. */
    suspend fun resolve(mediaId: String): Pair<MediaItem, Long>? {
        val e = episode(mediaId) ?: return null
        val url = e.playableUrl ?: return null
        val item = MediaItem.Builder()
            .setMediaId(e.id.toString())
            .setUri(url)
            .setMediaMetadata(metadata(e, playable = true))
            .build()
        val start = if (e.played) 0L else e.positionMs
        // The show's skipped start, as PlayerController.open has it — the car starts here, not there.
        val show = podcasts.show(e.podcastId).first()
        val intro = (show?.skipIntroSeconds ?: 0) * 1000L
        return item to if (show != null && start < intro && PlayerController.longEnough(show, (e.durationSeconds ?: 0) * 1000L)) intro else start
    }

    /** What a "resume" button on a headset or in the car continues. */
    suspend fun lastPlayed(): String? = episodes.inProgress.first().firstOrNull()?.let { EP + it.id }

    private suspend fun episode(mediaId: String): EpisodeItem? =
        (mediaId.removePrefix(EP).toLongOrNull())?.let { episodes.item(it) }

    private fun playable(e: EpisodeItem) = MediaItem.Builder().setMediaId(EP + e.id).setMediaMetadata(metadata(e, playable = true)).build()

    private fun metadata(e: EpisodeItem, playable: Boolean) = MediaMetadata.Builder()
        .setTitle(e.title)
        .setArtist(e.podcastTitle)
        .setArtworkUri((e.imageUrl ?: e.podcastImageUrl)?.let(Uri::parse))
        .setIsBrowsable(false)
        .setIsPlayable(playable)
        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
        .build()

    private fun folder(id: String, title: String, image: String? = null, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtworkUri(image?.let(Uri::parse))
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).build(),
        ).build()

    companion object {
        const val ROOT = "root"
        const val NEW = "new"
        const val QUEUE = "queue"
        const val DOWNLOADS = "downloads"
        const val LIBRARY = "library"
        const val SHOW = "show:"
        const val EP = "ep:"
        const val SHOW_EPISODES = 100
    }
}
