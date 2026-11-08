package space.sovereigncast.hopp.model

import kotlinx.serialization.Serializable

/**
 * A parsed feed: what Hopp keeps of an RSS document with the iTunes and
 * Podcasting 2.0 namespaces (feature matrix, docs/feature-matrix.md).
 *
 * Plain values only — no network, no database. Times are epoch seconds (UTC),
 * durations and offsets seconds.
 */
@Serializable
data class Feed(
    val podcast: Podcast,
    val episodes: List<Episode>,
    /** What the parser had to tolerate or skip; empty for a clean feed. */
    val warnings: List<String> = emptyList(),
)

@Serializable
data class Podcast(
    val title: String? = null,
    val link: String? = null,
    val description: String? = null,
    val language: String? = null,
    val author: String? = null,
    val imageUrl: String? = null,
    val explicit: Boolean? = null,
    val podcastGuid: String? = null,
    val locked: Boolean? = null,
    val medium: String? = null,
    val updateFrequency: UpdateFrequency? = null,
    val usesPodping: Boolean = false,
    val funding: List<Funding> = emptyList(),
    val txt: List<TxtRecord> = emptyList(),
    val persons: List<Person> = emptyList(),
    val value: ValueBlock? = null,
    val podroll: List<RemoteItem> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    /** `itunes:category` as the feed names them, top level and below, in order — what the
     *  Podcast Index files the show under too (device feedback 2026-10-19). */
    val categories: List<String> = emptyList(),
)

@Serializable
data class Episode(
    /** `<guid>`, else the enclosure URL — the spec's own fallback. */
    val guid: String,
    val title: String? = null,
    val description: String? = null,
    val link: String? = null,
    val publishedAt: Long? = null,
    val enclosure: Enclosure? = null,
    val durationSeconds: Long? = null,
    val imageUrl: String? = null,
    val season: Int? = null,
    val episodeNumber: Double? = null,
    val episodeType: String? = null,
    val explicit: Boolean? = null,
    val chaptersUrl: String? = null,
    /** Podlove's chapters, written into the feed itself. */
    val chapters: List<space.sovereigncast.hopp.feed.Chapter> = emptyList(),
    val transcripts: List<TranscriptRef> = emptyList(),
    val persons: List<Person> = emptyList(),
    val soundbites: List<Soundbite> = emptyList(),
    val value: ValueBlock? = null,
    val socialInteracts: List<SocialInteract> = emptyList(),
)

@Serializable
data class Enclosure(val url: String, val length: Long? = null, val type: String? = null)
@Serializable
data class UpdateFrequency(val text: String?, val rrule: String?, val complete: Boolean = false)
@Serializable
data class Funding(val url: String, val text: String?) {
    /** The Lightning address behind a `lightning:` link (`lightning:nodesignal@getalby.com`),
     *  paid inside Hopp when a wallet is connected (device feedback 2026-10-15); null otherwise. */
    val lightningAddress: String? get() = url.trim().takeIf { it.startsWith("lightning:", ignoreCase = true) }
        ?.substring("lightning:".length)?.trim()?.takeIf { LN_ADDRESS.matches(it) }

    private companion object { val LN_ADDRESS = Regex("""[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+""") }
}
@Serializable
data class TxtRecord(val purpose: String?, val value: String)
@Serializable
data class Person(val name: String, val role: String?, val group: String?, val img: String?, val href: String?)
@Serializable
data class TranscriptRef(val url: String, val type: String, val language: String?, val rel: String?)
@Serializable
data class Soundbite(val startTime: Double, val duration: Double, val title: String?)
@Serializable
data class SocialInteract(val uri: String, val protocol: String, val accountId: String?, val priority: Int?)
@Serializable
data class Trailer(val title: String?, val url: String, val publishedAt: Long?, val length: Long?, val type: String?, val season: Int?)

@Serializable
data class ValueBlock(
    val type: String,
    val method: String,
    /** BTC decimal as written in the feed ("0.00000005"). */
    val suggested: String? = null,
    val recipients: List<ValueRecipient> = emptyList(),
    val timeSplits: List<ValueTimeSplit> = emptyList(),
)

@Serializable
data class ValueRecipient(
    val name: String?,
    val type: String,
    val address: String,
    val split: Int,
    val fee: Boolean = false,
    val customKey: String? = null,
    val customValue: String? = null,
)

@Serializable
data class ValueTimeSplit(
    val startTime: Double,
    val duration: Double,
    val remoteStartTime: Double? = null,
    val remotePercentage: Int? = null,
    val recipients: List<ValueRecipient> = emptyList(),
    val remoteItem: RemoteItem? = null,
)

@Serializable
data class RemoteItem(val feedGuid: String, val itemGuid: String? = null, val feedUrl: String? = null, val medium: String? = null)
