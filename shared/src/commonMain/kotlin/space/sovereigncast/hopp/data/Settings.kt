package space.sovereigncast.hopp.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import space.sovereigncast.hopp.db.HoppDatabase
import space.sovereigncast.hopp.platform.ioDispatcher
import space.sovereigncast.hopp.sync.SyncDocs
import space.sovereigncast.hopp.sync.SyncJournal

/** The listener's settings, with the defaults of ADR 0012. */
data class Settings(
    val skipBackSeconds: Int = 10,
    val skipForwardSeconds: Int = 30,
    /** Android only (ADR 0012); iOS ignores it. */
    val skipSilence: Boolean = false,
    /** Quiet shows louder (maintainer 2026-10-21) — where the player can ([PlaybackEngine.supportsVolumeBoost]). */
    val volumeBoost: Boolean = false,
    /** The speed of every show whose speed the listener has not set on its own
     *  (device feedback 2026-10-19). */
    val defaultSpeed: Float = 1f,
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
    /** What a boost from the app's sheet reports (device feedback 2026-10-11). */
    val boostFeedback: String = FEEDBACK_ERRORS,
    /** What a quick boost from the lock screen reports (device feedback 2026-10-10). */
    val quickBoostFeedback: String = FEEDBACK_ERRORS,
    /** What a zap on a comment reports (device feedback 2026-10-19). */
    val zapFeedback: String = FEEDBACK_ERRORS,
    /** The first-start tour has been shown (or skipped). */
    val onboardingDone: Boolean = false,
    /** The library's shows as a list rather than cards (device feedback 2026-10-13). */
    val libraryAsList: Boolean = false,
    /** The library's order: newest episode first (the default, device feedback 2026-10-19), A–Z, most unheard. */
    val librarySort: String = SORT_NEWEST,
    /** The library's tag filter (maintainer 2026-11-03): "" all, [TAG_UNTAGGED] the shows without one, else a tag. */
    val libraryTag: String = "",
    /** Light, dark or as the system (the browser) says (maintainer 2026-10-30) — this device's, not synced. */
    val theme: String = THEME_SYSTEM,
    /** The language Discover's popular and new shows are in: a code ("de"), [LANGUAGE_ALL],
     *  or empty for the device's (device feedback 2026-10-19). */
    val discoverLanguage: String = "",
    /** Home's sections in the listener's order (keys of HomeSections); empty = Hopp's own
     *  (device feedback 2026-10-13). */
    val homeOrder: List<String> = emptyList(),
    /** Home's new order of 0.3.3 was looked at once: who had Hopp's old one keeps it (maintainer 2026-11-02). */
    val homeOrder033: Boolean = false,
    /** The "What's new" a line on Home last pointed to — [space.sovereigncast.hopp.data.Changelog.Release.seenKey];
     *  this device's, as the version installed is (maintainer 2026-11-03). */
    val whatsNewSeen: String = "",
    /** The episodes begun that were only under "Continue listening" went into the playlist, once (Dennis,
     *  maintainer 2026-11-03) — this device's: each brings in what it has, and sync joins them. */
    val playlistMerged: Boolean = false,
    /** Home's sections the listener does not want at all. */
    val homeHidden: Set<String> = emptySet(),
    /** Single entries under Home's "Take part" the listener hid, oldest first ([space.sovereigncast.hopp.ui.library.TakePart],
     *  device feedback 2026-10-22). */
    val takePartHidden: List<String> = emptyList(),
    /** With a Nostr identity, still vote and suggest without it (ADR 0021, 7). */
    val scAnonymous: Boolean = false,
    /** The listener was told, once, that what Hopp publishes stays public (ADR 0021, 8). */
    val nostrPublicNoted: Boolean = false,
    /** The backup reminder after the first comment with a key from Hopp was shown (ADR 0021, 9). */
    val nostrBackupReminded: Boolean = false,
    /** People muted on this device — without an identity, or before the relays have the list (ADR 0021, 3). */
    val mutedLocally: Set<String> = emptySet(),
    /** Comments the listener reported or hid: never shown again here. */
    val hiddenComments: Set<String> = emptySet(),
    /** A boost to an episode also as a public Nostr comment, as Fountain does — the
     *  boost dialog's switch starts there (ADR 0021, 6; device feedback 2026-10-16). */
    val nostrBoosts: Boolean = false,
    /** A new episode of a subscribed show goes to the end of "Up next" when a refresh finds it, on every
     *  device and in step with sync (maintainer 2026-11-03: until then a finished download put it there,
     *  on the phone only — the browser downloads nothing and never saw it). Its key is still
     *  "downloads_to_queue": the same choice the listener made before. */
    val newToQueue: Boolean = true,
    /** New episodes of shows that allow it download by themselves, on Wi-Fi only; off: only what the
     *  listener asks for (device feedback 2026-10-22: too much came that was never heard). */
    val autoDownloads: Boolean = true,
    /** Who a zap on a comment names, as in Amethyst (device feedback 2026-10-19): "public",
     *  "private" (only the author sees who sent it) or "anonymous". */
    val zapPrivacy: String = ZAP_PUBLIC,
    /** Subscriptions, progress, "Up next" and listening habits kept in step over Nostr —
     *  off until the listener turns it on (ADR 0021, 5). */
    val nostrSync: Boolean = false,
    /** What sharing offered first last time ([space.sovereigncast.hopp.ui.components.ShareKind]'s name; maintainer
     *  2026-10-26): who always shares the audio link need not switch to it each time. This device only. */
    val shareAs: String = "",
) {
    /** The tour done. The first time — a new install — what this version brought is no news ([whatsNew], the key). */
    fun doneTour(whatsNew: String?): Settings =
        copy(onboardingDone = true, whatsNewSeen = if (!onboardingDone && whatsNew != null) whatsNew else whatsNewSeen)

    companion object {
        val skipBackChoices = listOf(5, 10, 15, 30)
        val skipForwardChoices = listOf(10, 15, 30, 45, 60)
        val storageChoicesMb = listOf(1_024, 2_048, 5_120, 10_240, 0)
        val budgetChoicesSats = listOf(1_000, 5_000, 10_000, 21_000, 50_000)
        val streamRateChoicesSats = listOf(0, 1, 5, 10, 21, 50)
        const val RECENT_SEARCHES = 4
        val supportChoices = listOf(0, 1, 2, 5)
        /** An own share for "Support Hopp" goes up to this. */
        const val MAX_SUPPORT_PERCENT = 50
        /** Where "Support Hopp" goes — the project's Lightning address. */
        const val SUPPORT_ADDRESS = "sovereigncast@getalby.com"
        val quickBoostChoicesSats = listOf(0, 21, 100, 210, 1_000)
        val refreshChoicesHours = listOf(0, 1, 3, 6, 12, 24)
        const val FEEDBACK_OFF = "off"
        const val FEEDBACK_ERRORS = "errors"
        const val FEEDBACK_ALWAYS = "always"
        val feedbackChoices = listOf(FEEDBACK_OFF, FEEDBACK_ERRORS, FEEDBACK_ALWAYS)
        const val LANGUAGE_ALL = "*"
        const val SORT_NEWEST = "newest"
        /** The shows without a tag — not a tag of its own: a tag never starts with a space. */
        const val TAG_UNTAGGED = " untagged"
        const val SORT_TITLE = "title"
        const val SORT_UNHEARD = "unheard"
        val sortChoices = listOf(SORT_NEWEST, SORT_TITLE, SORT_UNHEARD)
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        val themeChoices = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)
        const val ZAP_PUBLIC = "public"
        const val ZAP_PRIVATE = "private"
        const val ZAP_ANONYMOUS = "anonymous"
        val zapPrivacyChoices = listOf(ZAP_PUBLIC, ZAP_PRIVATE, ZAP_ANONYMOUS)

        /** The newest this many entries hidden under "Take part" are kept — ended rounds pile up otherwise. */
        const val TAKE_PART_HIDDEN_MAX = 200

        /** The listening habits that go to the listener's other devices (ADR 0021, 5; maintainer
         *  2026-10-20), with downloads and background refresh since 2026-10-23 — not the wallet,
         *  budgets, storage or anything else of this device. */
        val SYNCED = setOf(
            "skip_back", "skip_forward", "skip_silence", "volume_boost", "default_speed", "sender_name", "quick_boost_sats",
            "home_order", "home_hidden", "home_collapsed", "library_sort", "library_as_list", "take_part_hidden", "library_tag",
            // Downloads, background refresh and what boosts and zaps report (maintainer 2026-10-23).
            "auto_downloads", "downloads_to_queue", "refresh_hours", "refresh_wifi_only",
            "boost_feedback", "quick_boost_feedback", "zap_feedback",
        )

        internal fun from(values: Map<String, String>): Settings {
            val d = Settings()
            return Settings(
                skipBackSeconds = values["skip_back"]?.toIntOrNull() ?: d.skipBackSeconds,
                skipForwardSeconds = values["skip_forward"]?.toIntOrNull() ?: d.skipForwardSeconds,
                skipSilence = values["skip_silence"]?.toBooleanStrictOrNull() ?: d.skipSilence,
                volumeBoost = values["volume_boost"]?.toBooleanStrictOrNull() ?: d.volumeBoost,
                defaultSpeed = values["default_speed"]?.toFloatOrNull()?.takeIf { it > 0f } ?: d.defaultSpeed,
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
                // One choice for all until 2026-10-19: the new ones start where it stood.
                quickBoostFeedback = (values["quick_boost_feedback"] ?: values["boost_feedback"])?.takeIf { it in feedbackChoices } ?: d.quickBoostFeedback,
                zapFeedback = (values["zap_feedback"] ?: values["boost_feedback"])?.takeIf { it in feedbackChoices } ?: d.zapFeedback,
                onboardingDone = values["onboarding_done"]?.toBooleanStrictOrNull() ?: d.onboardingDone,
                libraryAsList = values["library_as_list"]?.toBooleanStrictOrNull() ?: d.libraryAsList,
                librarySort = values["library_sort"]?.takeIf { it in sortChoices } ?: d.librarySort,
                libraryTag = values["library_tag"] ?: d.libraryTag,
                theme = values["theme"]?.takeIf { it in themeChoices } ?: d.theme,
                discoverLanguage = values["discover_language"] ?: d.discoverLanguage,
                homeOrder = values["home_order"]?.split('\n')?.filter { it.isNotBlank() } ?: d.homeOrder,
                homeOrder033 = values["home_order_033"]?.toBooleanStrictOrNull() ?: d.homeOrder033,
                whatsNewSeen = values["whats_new_seen"] ?: d.whatsNewSeen,
                playlistMerged = values["playlist_merged"]?.toBooleanStrictOrNull() ?: d.playlistMerged,
                homeHidden = values["home_hidden"]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: d.homeHidden,
                takePartHidden = values["take_part_hidden"]?.split('\n')?.filter { it.isNotBlank() }?.distinct()?.takeLast(TAKE_PART_HIDDEN_MAX) ?: d.takePartHidden,
                scAnonymous = values["sc_anonymous"]?.toBooleanStrictOrNull() ?: d.scAnonymous,
                nostrPublicNoted = values["nostr_public_noted"]?.toBooleanStrictOrNull() ?: d.nostrPublicNoted,
                nostrBackupReminded = values["nostr_backup_reminded"]?.toBooleanStrictOrNull() ?: d.nostrBackupReminded,
                mutedLocally = values["muted_local"]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: d.mutedLocally,
                nostrBoosts = values["nostr_boosts"]?.toBooleanStrictOrNull() ?: d.nostrBoosts,
                zapPrivacy = values["zap_privacy"]?.takeIf { it in zapPrivacyChoices } ?: d.zapPrivacy,
                newToQueue = values["downloads_to_queue"]?.toBooleanStrictOrNull() ?: d.newToQueue,
                autoDownloads = values["auto_downloads"]?.toBooleanStrictOrNull() ?: d.autoDownloads,
                hiddenComments = values["hidden_comments"]?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: d.hiddenComments,
                nostrSync = values["nostr_sync"]?.toBooleanStrictOrNull() ?: d.nostrSync,
                shareAs = values["share_as"] ?: d.shareAs,
            )
        }
    }

    /** Whether a boost's result is worth a notification ([quickBoostFeedback] from the lock screen, else [boostFeedback]). */
    fun reportsBoost(ok: Boolean, quick: Boolean = false): Boolean = reports(if (quick) quickBoostFeedback else boostFeedback, ok)

    /** Whether a zap's result is worth a notification ([zapFeedback]). */
    fun reportsZap(ok: Boolean): Boolean = reports(zapFeedback, ok)

    private fun reports(feedback: String, ok: Boolean) = when (feedback) {
        FEEDBACK_OFF -> false
        FEEDBACK_ERRORS -> !ok
        else -> true
    }

    internal fun toMap() = mapOf(
        "skip_back" to skipBackSeconds.toString(),
        "skip_forward" to skipForwardSeconds.toString(),
        "skip_silence" to skipSilence.toString(),
        "volume_boost" to volumeBoost.toString(),
        "default_speed" to defaultSpeed.toString(),
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
        "quick_boost_feedback" to quickBoostFeedback,
        "zap_feedback" to zapFeedback,
        "onboarding_done" to onboardingDone.toString(),
        "library_as_list" to libraryAsList.toString(),
        "library_sort" to librarySort,
        "library_tag" to libraryTag,
        "theme" to theme,
        "discover_language" to discoverLanguage,
        "home_order" to homeOrder.joinToString("\n"),
        "home_order_033" to homeOrder033.toString(),
        "whats_new_seen" to whatsNewSeen,
        "playlist_merged" to playlistMerged.toString(),
        "home_hidden" to homeHidden.joinToString("\n"),
        "take_part_hidden" to takePartHidden.joinToString("\n"),
        "sc_anonymous" to scAnonymous.toString(),
        "nostr_public_noted" to nostrPublicNoted.toString(),
        "nostr_backup_reminded" to nostrBackupReminded.toString(),
        "muted_local" to mutedLocally.joinToString("\n"),
        "hidden_comments" to hiddenComments.joinToString("\n"),
        "nostr_boosts" to nostrBoosts.toString(),
        "downloads_to_queue" to newToQueue.toString(),
        "auto_downloads" to autoDownloads.toString(),
        "zap_privacy" to zapPrivacy,
        "nostr_sync" to nostrSync.toString(),
        "share_as" to shareAs,
    )
}

class SettingsRepository(
    private val db: HoppDatabase,
    /** Listening habits ([Settings.SYNCED]), noted for sync (ADR 0021, 5). */
    private val journal: SyncJournal = SyncJournal.Off,
) {
    val settings: Flow<Settings> = db.settingQueries.selectAll().asFlow().mapToList(ioDispatcher)
        .map { rows -> Settings.from(rows.associate { it.key to it.value_ }) }

    suspend fun current(): Settings = settings.first()

    suspend fun update(change: (Settings) -> Settings) = withContext(ioDispatcher) {
        val before = Settings.from(db.settingQueries.selectAll().awaitAsList().associate { it.key to it.value_ })
        val after = change(before)
        val changed = after.toMap().filter { (k, v) -> before.toMap()[k] != v }
        db.transaction { changed.forEach { (k, v) -> db.settingQueries.put(k, v) } }
        changed.filterKeys { it in Settings.SYNCED }.forEach { (k, v) -> journal.record(SyncDocs.SETTINGS, k, SyncDocs.setting(v)) }
    }

    /** Listening habits from another device (sync): taken as they are, not noted again. */
    suspend fun applySynced(values: Map<String, String>) = withContext(ioDispatcher) {
        db.transaction { values.filterKeys { it in Settings.SYNCED }.forEach { (k, v) -> db.settingQueries.put(k, v) } }
    }

    /** The listening habits as they stand here. */
    suspend fun synced(): Map<String, String> = current().toMap().filterKeys { it in Settings.SYNCED }
}
