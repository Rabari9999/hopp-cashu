package space.sovereigncast.hopp.ui.library

import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.sovereigncast.ShowAction

/**
 * What the listener hid under Home's "Take part", one entry at a time (device feedback
 * 2026-10-22): a single round or episode, a show's suggestions, or everything of a show.
 * Kept by the show's podcast:guid, not this device's id, so sync carries it to the
 * listener's other devices. Only Home leaves them out — the show's page shows all.
 */
object TakePart {
    enum class Scope { Show, Suggestions, Round, Unlock }

    data class Entry(val scope: Scope, val podcastGuid: String, val episodeGuid: String? = null)

    fun show(podcastGuid: String) = "show:$podcastGuid"
    fun suggestions(podcastGuid: String) = "suggestions:$podcastGuid"

    /** The action itself: a new round or another episode comes back on its own. */
    fun itself(a: ShowAction): String? = when (a.kind) {
        ShowAction.Kind.ReleaseBoosting -> a.episodeGuid?.let { "rb:${a.podcastGuid}/$it" }
        ShowAction.Kind.Boost2Unlock -> a.episodeGuid?.let { "b2u:${a.podcastGuid}/$it" }
        ShowAction.Kind.Suggestions -> suggestions(a.podcastGuid)
    }

    fun hides(hidden: Collection<String>, a: ShowAction): Boolean =
        a.podcastGuid.isNotEmpty() && (show(a.podcastGuid) in hidden || itself(a)?.let { it in hidden } == true)

    /** [hidden] with [key] as its newest entry, the oldest dropped beyond the limit. */
    fun add(hidden: List<String>, key: String): List<String> = (hidden - key + key).takeLast(Settings.TAKE_PART_HIDDEN_MAX)

    fun parse(key: String): Entry? {
        val (kind, rest) = key.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        return when (kind) {
            "show" -> Entry(Scope.Show, rest)
            "suggestions" -> Entry(Scope.Suggestions, rest)
            "rb", "b2u" -> rest.split('/', limit = 2).takeIf { it.size == 2 }
                ?.let { (g, e) -> Entry(if (kind == "rb") Scope.Round else Scope.Unlock, g, e) }
            else -> null
        }?.takeIf { it.podcastGuid.isNotEmpty() }
    }
}
