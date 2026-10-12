package space.sovereigncast.hopp.sovereigncast

import io.ktor.client.request.url
import space.sovereigncast.hopp.net.limited
import space.sovereigncast.hopp.net.getLimited
import space.sovereigncast.hopp.net.LimitedResponse
import space.sovereigncast.hopp.net.Limits
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The Listener API v1 of a Sovereigncast instance (ADR 0010; the contract is
 * podcast_manager/api_specs/listener-v1.yaml in nodesignal/podcasting_tools).
 * Phase 1 uses what ADR 0018 puts there: meta, the show, Release Boosting,
 * Boost2Unlock, the open suggestions with free votes, invoices and their
 * status, and the rss::payment metadata for boosts — and, read-only, the
 * leaderboard (device feedback 2026-10-09).
 */

@Serializable data class ApiMeta(@SerialName("api_versions") val apiVersions: List<String>, val instance: Instance) {
    @Serializable data class Instance(val id: String, val software: String, val version: String)
}

@Serializable data class Capabilities(
    @SerialName("release_boosting") val releaseBoosting: Boolean = false,
    val boost2unlock: Boolean = false,
    @SerialName("halving_clip") val halvingClip: Boolean = false,
    val suggestions: Boolean = false,
    val leaderboard: Boolean = false,
    @SerialName("top_episodes") val topEpisodes: Boolean = false,
    @SerialName("payment_metadata") val paymentMetadata: Boolean = false,
)

@Serializable data class ScShow(
    @SerialName("podcast_guid") val podcastGuid: String,
    val title: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("feed_url") val feedUrl: String,
    @SerialName("community_page_url") val communityPageUrl: String? = null,
    val capabilities: Capabilities,
    val amounts: Amounts = Amounts(),
    @SerialName("poll_after_seconds") val pollAfterSeconds: Int = 3600,
) {
    @Serializable data class Amounts(@SerialName("suggestion_min_sats") val suggestionMinSats: Int = 1)
}

@Serializable data class UpcomingEpisode(
    @SerialName("episode_guid") val episodeGuid: String,
    @SerialName("episode_nr") val episodeNr: Int? = null,
    val title: String,
    val description: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable data class Chain(
    @SerialName("block_sats") val blockSats: Int,
    val blocks: Int,
    @SerialName("mined_blocks") val minedBlocks: Int,
    @SerialName("halving_block") val halvingBlock: Int,
)

/** A clip that waits at a mark; [title] and [url] only once it is unlocked (no spoilers). */
@Serializable data class ClipState(
    val unlocked: Boolean,
    @SerialName("at_block") val atBlock: Int? = null,
    @SerialName("at_sats") val atSats: Long? = null,
    val title: String = "",
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val url: String? = null,
)

@Serializable data class ReleaseBoostingRound(
    val episode: UpcomingEpisode,
    @SerialName("goal_sats") val goalSats: Long,
    @SerialName("raised_sats") val raisedSats: Long,
    @SerialName("sats_per_minute") val satsPerMinute: Int,
    @SerialName("publish_at") val publishAt: String,
    val chain: Chain? = null,
    val clip: ClipState? = null,
)

@Serializable data class ReleaseBoosting(val round: ReleaseBoostingRound? = null, @SerialName("poll_after_seconds") val pollAfterSeconds: Int = 60)

@Serializable data class Boost2UnlockEpisode(
    val episode: UpcomingEpisode,
    @SerialName("goal_sats") val goalSats: Long,
    @SerialName("raised_sats") val raisedSats: Long,
    val unlocked: Boolean,
    val link: String? = null,
    val clip: ClipState? = null,
)

@Serializable data class Boost2UnlockList(val episodes: List<Boost2UnlockEpisode>, @SerialName("poll_after_seconds") val pollAfterSeconds: Int = 60)

@Serializable data class Suggestion(
    val id: Long,
    val title: String,
    val description: String = "",
    val username: String = "",
    @SerialName("amount_sats") val amountSats: Long = 0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable data class SuggestionList(val suggestions: List<Suggestion>)
@Serializable data class VoteRequest(@SerialName("voter_id") val voterId: String)
@Serializable data class VoteResult(@SerialName("already_voted") val alreadyVoted: Boolean)

/** An episode a boost went to; no [episodeGuid] for a boost to the whole show. */
@Serializable data class EpisodeRef(@SerialName("episode_guid") val episodeGuid: String? = null, val title: String = "")

@Serializable data class CommunityBoost(
    val rank: Int? = null,
    @SerialName("amount_sats") val amountSats: Long,
    val sender: String = "",
    val message: String = "",
    val episode: EpisodeRef = EpisodeRef(),
)

@Serializable data class Medals(val gold: Int = 0, val silver: Int = 0, val bronze: Int = 0)

/** The all-time ranking carries no amounts — placement, trend and medals only. */
@Serializable data class Booster(
    val rank: Int,
    val sender: String = "",
    /** new, up, down or same */
    val trend: String = "same",
    @SerialName("prev_rank") val prevRank: Int? = null,
    val legendary: Boolean = false,
    val medals: Medals = Medals(),
)

@Serializable data class Leaderboard(
    /** "2026-10": the month [topBoosts] covers. */
    val month: String,
    @SerialName("top_boosts") val topBoosts: List<CommunityBoost> = emptyList(),
    @SerialName("recent_boosts") val recentBoosts: List<CommunityBoost> = emptyList(),
    @SerialName("alltime_boosters") val alltimeBoosters: List<Booster> = emptyList(),
    @SerialName("poll_after_seconds") val pollAfterSeconds: Int = 300,
)

@Serializable data class BoostRequest(
    @SerialName("amount_sats") val amountSats: Long,
    val message: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
)

@Serializable data class Invoice(
    @SerialName("payment_hash") val paymentHash: String,
    val bolt11: String,
    @SerialName("amount_sats") val amountSats: Long,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable data class PaymentStatus(
    @SerialName("payment_hash") val paymentHash: String,
    val status: String,
    @SerialName("poll_after_seconds") val pollAfterSeconds: Int = 2,
)

@Serializable data class PaymentMetadataRequest(
    val action: String,
    @SerialName("app_name") val appName: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("episode_guid") val episodeGuid: String? = null,
    val message: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("recipient_name") val recipientName: String? = null,
    @SerialName("recipient_address") val recipientAddress: String? = null,
    @SerialName("value_msat") val valueMsat: Long,
    @SerialName("value_msat_total") val valueMsatTotal: Long,
    val position: Long? = null,
)

@Serializable data class PaymentMetadataLink(val id: String, val url: String, @SerialName("comment_prefix") val commentPrefix: String)

@Serializable private data class ErrorBody(val error: ErrorDetail) {
    @Serializable data class ErrorDetail(val code: String, val message: String = "")
}

/** An answer the API gave as its error shape; [code] is what the app shows text for. */
class ListenerApiException(val code: String, message: String, val status: Int) : Exception("$code: $message")

/** One instance's Listener API; [base] is the public listener URL from the feed marker. */
class ListenerApi(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun url(base: String, path: String) = base.trimEnd('/') + "/api/listener" + path
    private fun seg(s: String) = s.encodeURLPathPart()

    private fun <T> read(response: LimitedResponse, serializer: KSerializer<T>): T {
        val text = response.text()
        if (!response.status.isSuccess()) {
            val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
            throw ListenerApiException(err?.code ?: "http_${response.status.value}", err?.message ?: text.take(200), response.status.value)
        }
        return json.decodeFromString(serializer, text)
    }

    private suspend fun <T> get(base: String, path: String, serializer: KSerializer<T>): T = read(http.getLimited(url(base, path), Limits.API), serializer)

    private suspend fun <B, T> post(base: String, path: String, body: B, bodySerializer: KSerializer<B>, serializer: KSerializer<T>): T =
        url(base, path).let { target ->
            read(http.limited(Limits.API) {
            url(target); method = io.ktor.http.HttpMethod.Post
            contentType(ContentType.Application.Json); setBody(json.encodeToString(bodySerializer, body))
            }, serializer)
        }

    suspend fun meta(base: String) = get(base, "/meta", ApiMeta.serializer())
    suspend fun show(base: String, guid: String) = get(base, "/v1/shows/by-guid/${seg(guid)}", ScShow.serializer())
    suspend fun releaseBoosting(base: String, guid: String) = get(base, "/v1/shows/${seg(guid)}/release-boosting", ReleaseBoosting.serializer())
    suspend fun boost2unlock(base: String, guid: String) = get(base, "/v1/shows/${seg(guid)}/boost2unlock", Boost2UnlockList.serializer())
    suspend fun suggestions(base: String, guid: String) = get(base, "/v1/shows/${seg(guid)}/suggestions", SuggestionList.serializer())
    suspend fun leaderboard(base: String, guid: String) = get(base, "/v1/shows/${seg(guid)}/leaderboard", Leaderboard.serializer())
    suspend fun payment(base: String, hash: String) = get(base, "/v1/payments/${seg(hash)}", PaymentStatus.serializer())

    suspend fun vote(base: String, guid: String, suggestionId: Long, voterId: String) =
        post(base, "/v1/shows/${seg(guid)}/suggestions/$suggestionId/votes", VoteRequest(voterId), VoteRequest.serializer(), VoteResult.serializer())

    suspend fun releaseBoostingInvoice(base: String, guid: String, request: BoostRequest) =
        post(base, "/v1/shows/${seg(guid)}/release-boosting/invoices", request, BoostRequest.serializer(), Invoice.serializer())

    suspend fun boost2unlockInvoice(base: String, guid: String, episodeGuid: String, request: BoostRequest) =
        post(base, "/v1/shows/${seg(guid)}/boost2unlock/${seg(episodeGuid)}/invoices", request, BoostRequest.serializer(), Invoice.serializer())

    suspend fun paymentMetadata(base: String, guid: String, request: PaymentMetadataRequest) =
        post(base, "/v1/shows/${seg(guid)}/payment-metadata", request, PaymentMetadataRequest.serializer(), PaymentMetadataLink.serializer())
}
