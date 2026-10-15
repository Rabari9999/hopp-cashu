package space.sovereigncast.hopp.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.db.HoppDatabase

/** The listener's settings, with the defaults of ADR 0012. */
data class Settings(
    val skipBackSeconds: Int = 10,
    val skipForwardSeconds: Int = 30,
    /** Android only (ADR 0012); iOS ignores it. */
    val skipSilence: Boolean = false,
    /** Space for downloads; 0 = no limit. */
    val storageLimitMb: Int = 2_048,
    /** Extra directories, off by default — each sees the searches (ADR 0008). */
    val fyydSearch: Boolean = false,
    val appleSearch: Boolean = false,
    /** What boosts and streaming may spend per day together (ADR 0005); Hopp
     *  enforces it on top of any limit set in the wallet. */
    val dailyBudgetSats: Int = 5_000,
    /** Streaming sats — off until the listener turns it on (ADR 0005). */
    val streaming: Boolean = false,
    /** Sats per minute; 0 = what the show suggests. */
    val streamRateSats: Int = 0,
    /** Sent with boosts if set; empty = anonymous. */
    val senderName: String = "",
    /** The boost button on the lock screen and in the notification sends this
     *  at once, without a dialog; 0 = no button. */
    val quickBoostSats: Int = 100,
    /** "Support Hopp" (ADR 0005): a visible share of boosts and streaming,
     *  in percent; 0 = off, which is the default. */
    val supportHoppPercent: Int = 0,
    /** Hopp's anonymous id for free votes (Listener API voter_id) — random,
     *  made once, nothing to do with anything else the listener does. */
    val voterId: String = "",
    /** Suggestions this listener voted for: "<podcast guid>/<id>". */
    val votes: Set<String> = emptySet(),
    /** The last search terms, newest first (at most [RECENT_SEARCHES]). */
    val recentSearches: List<String> = emptyList(),
    /** Background feed refresh every this many hours; 0 = off, pull to refresh only. */
    val refreshHours: Int = 1,
    /** Background refresh only on Wi-Fi (or any unmetered network). */
    val refreshWifiOnly: Boolean = false,
    /** Home's sections the listener folded away: "new", "continue", "recent", "boosted". */
    val homeCollapsed: Set<String> = emptySet(),
    /** What a quick boost from the lock screen reports (device feedback 2026-10-10). */
    val boostFeedback: String = FEEDBACK_ERRORS,
    /** The first-start tour has been shown (or skipped). */
    val onboardingDone: Boolean = false,
    /** The library's shows as a list rather than cards (device feedback 2026-10-13). */
    val libraryAsList: Boolean = false,
    /** Home's sections in the listener's order (keys of HomeSections); empty = Hopp's own
     *  (device feedback 2026-10-13). */
    val homeOrder: List<String> = emptyList(),
    /** Home's sections the listener does not want at all. */
    val homeHidden: Set<String> = emptySet(),
    /** With a Nostr identity, still vote and suggest without it (ADR 0021, 7). */
    val scAnonymous: Boolean = false,
) {
    companion object {
        val skipBackChoices = listOf(5, 10, 15, 30)
        val skipForwardChoices = listOf(10, 15, 30, 45, 60)
        val storageChoicesMb = listOf(1_024, 2_048, 5_120, 10_240, 0)
        val budgetChoicesSats = listOf(1_000, 5_000, 10_000, 21_000, 50_000)
        val streamRateChoicesSats = listOf(0, 1, 5, 10, 21, 50)
        const val RECENT_SEARCHES = 4
        val supportChoices = listOf(0, 1, 2, 5)
        /** Where "Support Hopp" goes — the project's Lightning address. */
        const val SUPPORT_ADDRESS = "sovereigncast@getalby.com"
        val quickBoostChoicesSats = listOf(0, 21, 100, 500, 1_000)
        val refreshChoicesHours = listOf(0, 1, 3, 6, 12, 24)
        const val FEEDBACK_OFF = "off"
        const val FEEDBACK_ERRORS = "errors"
        const val FEEDBACK_ALWAYS = "always"
        val feedbackChoices = listOf(FEEDBACK_OFF, FEEDBACK_ERRORS, FEEDBACK_ALWAYS)

        internal fun from(values: Map<String, String>): Settings {
            val d = Settings()
            return Settings(
                skipBackSeconds = values["skip_back"]?.toIntOrNull() ?: d.skipBackSeconds,
                skipForwardSeconds = values["skip_forward"]?.toIntOrNull() ?: d.skipForwardSeconds,
                skipSilence = values["skip_silence"]?.toBooleanStrictOrNull() ?: d.skipSilence,
                storageLimitMb = values["storage_limit_mb"]?.toIntOrNull() ?: d.storageLimitMb,
                fyydSearch = values["fyyd_search"]?.toBooleanStrictOrNull() ?: d.fyydSearch,
                appleSearch = values["apple_search"]?.toBooleanStrictOrNull() ?: d.appleSearch,
                dailyBudgetSats = values["daily_budget_sats"]?.toIntOrNull() ?: d.dailyBudgetSats,
                streaming = values["streaming"]?.toBooleanStrictOrNull() ?: d.streaming,
                streamRateSats = values["stream_rate_sats"]?.toIntOrNull() ?: d.streamRateSats,
                senderName = values["sender_name"] ?: d.senderName,
                quickBoostSats = values["quick_boost_sats"]?.toIntOrNull() ?: d.quickBoostSats,
                supportHoppPercent = values["support_hopp_percent"]?.toIntOrNull() ?: d.supportHoppPercent,
                voterId = values["voter_id"] ?: d.voterId,
                votes = values["votes"]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: d.votes,
                recentSearches = values["recent_searches"]?.split('\n')?.filter { it.isNotBlank() } ?: d.recentSearches,
                refreshHours = values["refresh_hours"]?.toIntOrNull() ?: d.refreshHours,
                refreshWifiOnly = values["refresh_wifi_only"]?.toBooleanStrictOrNull() ?: d.refreshWifiOnly,
                homeCollapsed = values["home_collapsed"]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: d.homeCollapsed,
                boostFeedback = values["boost_feedback"]?.takeIf { it in feedbackChoices } ?: d.boostFeedback,
                onboardingDone = values["onboarding_done"]?.toBooleanStrictOrNull() ?: d.onboardingDone,
                libraryAsList = values["library_as_list"]?.toBooleanStrictOrNull() ?: d.libraryAsList,
                homeOrder = values["home_order"]?.split('\n')?.filter { it.isNotBlank() } ?: d.homeOrder,
                homeHidden = values["home_hidden"]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: d.homeHidden,
                scAnonymous = values["sc_anonymous"]?.toBooleanStrictOrNull() ?: d.scAnonymous,
            )
        }
    }

    /** Whether a boost's result is worth a notification ([boostFeedback]). */
    fun reportsBoost(ok: Boolean): Boolean = when (boostFeedback) {
        FEEDBACK_OFF -> false
        FEEDBACK_ERRORS -> !ok
        else -> true
    }

    internal fun toMap() = mapOf(
        "skip_back" to skipBackSeconds.toString(),
        "skip_forward" to skipForwardSeconds.toString(),
        "skip_silence" to skipSilence.toString(),
        "storage_limit_mb" to storageLimitMb.toString(),
        "fyyd_search" to fyydSearch.toString(),
        "apple_search" to appleSearch.toString(),
        "daily_budget_sats" to dailyBudgetSats.toString(),
        "streaming" to streaming.toString(),
        "stream_rate_sats" to streamRateSats.toString(),
        "sender_name" to senderName,
        "quick_boost_sats" to quickBoostSats.toString(),
        "support_hopp_percent" to supportHoppPercent.toString(),
        "voter_id" to voterId,
        "votes" to votes.joinToString("\n"),
        "recent_searches" to recentSearches.joinToString("\n"),
        "refresh_hours" to refreshHours.toString(),
        "refresh_wifi_only" to refreshWifiOnly.toString(),
        "home_collapsed" to homeCollapsed.joinToString("\n"),
        "boost_feedback" to boostFeedback,
        "onboarding_done" to onboardingDone.toString(),
        "library_as_list" to libraryAsList.toString(),
        "home_order" to homeOrder.joinToString("\n"),
        "home_hidden" to homeHidden.joinToString("\n"),
        "sc_anonymous" to scAnonymous.toString(),
    )
}

class SettingsRepository(private val db: HoppDatabase) {
    val settings: Flow<Settings> = db.settingQueries.selectAll().asFlow().mapToList(Dispatchers.IO)
        .map { rows -> Settings.from(rows.associate { it.key to it.value_ }) }

    suspend fun current(): Settings = settings.first()

    suspend fun update(change: (Settings) -> Settings) = withContext(Dispatchers.IO) {
        val before = Settings.from(db.settingQueries.selectAll().executeAsList().associate { it.key to it.value_ })
        val after = change(before)
        db.transaction { after.toMap().forEach { (k, v) -> if (before.toMap()[k] != v) db.settingQueries.put(k, v) } }
    }
}
