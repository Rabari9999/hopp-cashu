package space.sovereigncast.hopp.ui.library

import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.RadioButton
import space.sovereigncast.hopp.player.PlayerController
import space.sovereigncast.hopp.resources.about_build
import space.sovereigncast.hopp.resources.about_build_checking
import space.sovereigncast.hopp.resources.about_build_signed
import space.sovereigncast.hopp.resources.about_build_differs
import space.sovereigncast.hopp.resources.about_build_unsigned
import space.sovereigncast.hopp.resources.about_build_unknown
import space.sovereigncast.hopp.resources.about_build_event
import space.sovereigncast.hopp.resources.about_build_note
import space.sovereigncast.hopp.resources.settings_language_browser
import space.sovereigncast.hopp.ui.player.formatSpeed
import space.sovereigncast.hopp.resources.stats_entry_note
import space.sovereigncast.hopp.resources.stats_title
import space.sovereigncast.hopp.resources.settings_updates
import space.sovereigncast.hopp.resources.settings_updates_note
import space.sovereigncast.hopp.resources.settings_default_speed
import space.sovereigncast.hopp.resources.settings_default_speed_note
import space.sovereigncast.hopp.resources.settings_new_to_queue
import space.sovereigncast.hopp.resources.settings_auto_downloads
import space.sovereigncast.hopp.resources.settings_auto_downloads_note
import space.sovereigncast.hopp.resources.settings_new_to_queue_note
import space.sovereigncast.hopp.resources.nostr_title
import space.sovereigncast.hopp.resources.nostr_entry_none
import space.sovereigncast.hopp.resources.settings_downloads_delete_confirm
import space.sovereigncast.hopp.resources.settings_downloads_delete
import space.sovereigncast.hopp.resources.settings_downloads_browse
import space.sovereigncast.hopp.resources.settings_downloads_used
import space.sovereigncast.hopp.resources.cancel
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import space.sovereigncast.hopp.resources.settings_notifications_open_ios
import space.sovereigncast.hopp.resources.settings_notifications_system_ios
import space.sovereigncast.hopp.resources.settings_home_note
import space.sovereigncast.hopp.resources.settings_home
import space.sovereigncast.hopp.resources.home_customize
import androidx.compose.material.icons.rounded.Home
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.data.Settings
import space.sovereigncast.hopp.platform.AppInfo
import space.sovereigncast.hopp.platform.PlaybackEngine
import space.sovereigncast.hopp.resources.*
import space.sovereigncast.hopp.ui.LocalPlatformActions
import space.sovereigncast.hopp.ui.components.HoppIcons
import space.sovereigncast.hopp.ui.components.formatBytes
import space.sovereigncast.hopp.ui.theme.Hopp

/** The pages behind the settings overview (device feedback 2026-10-10). */
enum class SettingsPage(val title: StringResource) {
    Playback(Res.string.settings_playback),
    Appearance(Res.string.settings_appearance),
    PerShow(Res.string.per_show_title),
    Home(Res.string.home_customize),
    Downloads(Res.string.settings_downloads_refresh),
    Notifications(Res.string.settings_notifications),
    Directories(Res.string.settings_directories),
    Subscriptions(Res.string.settings_subscriptions),
    Nostr(Res.string.nostr_title),
    SharedShows(Res.string.contacts_shared_title),
    About(Res.string.settings_about_title),
}

/**
 * The settings as an overview of their parts (device feedback 2026-10-10: one
 * long page hid the wallet and its payment history): value for value first —
 * wallet and history as rows of their own — then the app, then Hopp itself.
 * Each row says what is behind it.
 */
@Composable
fun SettingsScreen(
    onWallet: () -> Unit = {},
    onHistory: () -> Unit = {},
    onStats: () -> Unit = {},
    onSupport: () -> Unit = {},
    onPage: (SettingsPage) -> Unit = {},
    onPrivacy: () -> Unit = {},
    onCrashReport: (() -> Unit)? = null,
    vm: SettingsViewModel = koinViewModel(),
) {
    val wallet by vm.walletLabel.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        val actions = LocalPlatformActions.current
        // Where Hopp pays nothing (the browser for now) there is no value group: the stats go with the app's
        // pages, and neither a download page nor one for what payments report is offered (UX review 2026-10-30).
        val stats = @Composable { Entry(Icons.Rounded.Star, stringResource(Res.string.stats_title), stringResource(Res.string.stats_entry_note), onClick = onStats) }
        if (actions.canPay) {
            Group(stringResource(Res.string.settings_group_value))
            Entry(HoppIcons.Hop, stringResource(Res.string.settings_wallet),
                wallet?.let { stringResource(Res.string.settings_wallet_connected, it) } ?: stringResource(Res.string.settings_wallet_none), accent = true, onClick = onWallet)
            Entry(Icons.Rounded.DateRange, stringResource(Res.string.wallet_history), stringResource(Res.string.settings_history_note), onClick = onHistory)
            stats()
        }

        Group(stringResource(Res.string.settings_group_app))
        Entry(Icons.Rounded.PlayArrow, stringResource(Res.string.settings_playback), stringResource(Res.string.settings_playback_note)) { onPage(SettingsPage.Playback) }
        Entry(Icons.Rounded.Home, stringResource(Res.string.settings_home), stringResource(Res.string.settings_home_note)) { onPage(SettingsPage.Home) }
        Entry(HoppIcons.Contrast, stringResource(Res.string.settings_appearance), stringResource(Res.string.settings_appearance_note)) { onPage(SettingsPage.Appearance) }
        if (actions.canDownload) Entry(HoppIcons.Download, stringResource(Res.string.settings_downloads_refresh), stringResource(Res.string.settings_downloads_note)) { onPage(SettingsPage.Downloads) }
        else Entry(Icons.Rounded.Refresh, stringResource(Res.string.settings_updates), stringResource(Res.string.settings_updates_note)) { onPage(SettingsPage.Downloads) }
        if (actions.canPay && actions.canNotify) Entry(Icons.Rounded.Notifications, stringResource(Res.string.settings_notifications), stringResource(Res.string.settings_notifications_note)) { onPage(SettingsPage.Notifications) }
        if (!actions.canPay) stats()
        // Hopp's own language, apart from the phone's (device feedback 2026-10-11) — the system's page, Android 13+.
        if (actions.canPickLanguage) Entry(HoppIcons.Globe, stringResource(Res.string.settings_language), stringResource(Res.string.settings_language_current)) { actions.openLanguageSettings() }
        // In the browser, which has no such page, Hopp's own choice (maintainer 2026-10-28).
        actions.appLanguages?.let { languages ->
            var choose by remember { mutableStateOf(false) }
            val browser = stringResource(Res.string.settings_language_browser)
            Entry(HoppIcons.Globe, stringResource(Res.string.settings_language), actions.appLanguage?.let { languages[it] } ?: browser) { choose = true }
            if (choose) AlertDialog(
                onDismissRequest = { choose = false },
                title = { Text(stringResource(Res.string.settings_language)) },
                text = {
                    Column {
                        (listOf<String?>(null) + languages.keys).forEach { code ->
                            Row(
                                Modifier.fillMaxWidth().clickable { choose = false; if (code != actions.appLanguage) actions.setAppLanguage(code) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = code == actions.appLanguage, onClick = null)
                                Text(code?.let { languages[it] } ?: browser, Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(Res.string.cancel)) } },
            )
        }
        Entry(Icons.Rounded.Search, stringResource(Res.string.settings_directories), stringResource(Res.string.settings_directories_note)) { onPage(SettingsPage.Directories) }
        Entry(Icons.AutoMirrored.Rounded.List, stringResource(Res.string.settings_subscriptions), stringResource(Res.string.settings_subscriptions_note)) { onPage(SettingsPage.Subscriptions) }

        Group(stringResource(Res.string.settings_group_hopp))
        // A donation to Hopp, from the wallet connected here (maintainer 2026-11-02) — where Hopp pays at all.
        if (actions.canPay) Entry(Icons.Rounded.Favorite, stringResource(Res.string.wallet_support), stringResource(Res.string.settings_support_note), accent = true, onClick = onSupport)
        // The optional identity (ADR 0021): its name once there is one.
        val identity by koinInject<space.sovereigncast.hopp.identity.IdentityRepository>().identity.collectAsState()
        Entry(Icons.Rounded.Person, stringResource(Res.string.nostr_title),
            identity?.let { it.name ?: it.npub.take(16) + "…" } ?: stringResource(Res.string.nostr_entry_none)) { onPage(SettingsPage.Nostr) }
        Entry(Icons.Rounded.Lock, stringResource(Res.string.privacy_title), stringResource(Res.string.settings_privacy_note), onClick = onPrivacy)
        // Only while a report waits (ADR 0015).
        if (onCrashReport != null) Entry(Icons.Rounded.Warning, stringResource(Res.string.crash_title), stringResource(Res.string.settings_crash_note), onClick = onCrashReport)
        Entry(Icons.Rounded.Info, stringResource(Res.string.settings_about_title), stringResource(Res.string.settings_about_note)) { onPage(SettingsPage.About) }
    }
}

@Composable
private fun Group(text: String) = Text(
    text, Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun Entry(icon: ImageVector, title: String, note: String, accent: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (accent) Hopp.colors.valueText else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One page of the settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPageScreen(page: SettingsPage, onTour: () -> Unit = {}, onPage: (SettingsPage) -> Unit = {}, vm: SettingsViewModel = koinViewModel()) {
    val app: AppInfo = koinInject()
    val engine: PlaybackEngine = koinInject()
    val settings by vm.current.collectAsState()
    val actions = LocalPlatformActions.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val s = settings ?: return
    // Home's sections are dragged, which wants a lazy list of its own rather than this column.
    if (page == SettingsPage.Home) {
        val titles by vm.showTitles.collectAsState()
        HomeSectionsEditor(s, titles) { change -> vm.update(change) }
        return
    }
    if (page == SettingsPage.Nostr) { space.sovereigncast.hopp.ui.identity.NostrIdentityPage(onSharedShows = { onPage(SettingsPage.SharedShows) }); return }
    if (page == SettingsPage.PerShow) { PerShowPage(vm); return }
    if (page == SettingsPage.SharedShows) { SharedShowsPage(vm); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (page) {
            SettingsPage.Home, SettingsPage.Nostr, SettingsPage.PerShow, SettingsPage.SharedShows -> Unit
            SettingsPage.Playback -> {
                // Every show plays at this speed until the listener gives it one of its own
                // in the player (device feedback 2026-10-19).
                Label(stringResource(Res.string.settings_default_speed))
                Choices(PlayerController.SPEED_CHOICES, s.defaultSpeed, { "${formatSpeed(it)}×" }) { v -> vm.update { it.copy(defaultSpeed = v) } }
                Note(stringResource(Res.string.settings_default_speed_note))
                // Right below the speed every show has, the shows that differ (maintainer 2026-10-26:
                // in the show's menu alone it was easily missed) — a row like the toggles below.
                val shows by vm.shows.collectAsState()
                val own = shows.count { it.hasOwnPlayback }
                Row(Modifier.fillMaxWidth().clickable { onPage(SettingsPage.PerShow) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.per_show_title))
                        Text(
                            stringResource(Res.string.per_show_note) + if (own > 0) " · " + pluralStringResource(Res.plurals.per_show_count, own, own) else "",
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
                }
                Label(stringResource(Res.string.settings_skip_back))
                Choices(Settings.skipBackChoices, s.skipBackSeconds, { stringResource(Res.string.settings_seconds, it) }) { v -> vm.update { it.copy(skipBackSeconds = v) } }
                Label(stringResource(Res.string.settings_skip_forward))
                Choices(Settings.skipForwardChoices, s.skipForwardSeconds, { stringResource(Res.string.settings_seconds, it) }) { v -> vm.update { it.copy(skipForwardSeconds = v) } }
                if (engine.supportsSkipSilence) Toggle(stringResource(Res.string.skip_silence), stringResource(Res.string.settings_skip_silence_note), s.skipSilence) { on ->
                    vm.update { it.copy(skipSilence = on) }
                }
                if (engine.supportsVolumeBoost) Toggle(stringResource(Res.string.volume_boost), stringResource(Res.string.settings_volume_boost_note), s.volumeBoost) { on ->
                    vm.update { it.copy(volumeBoost = on) }
                }
            }
            SettingsPage.Downloads -> {
              if (actions.canDownload) {
                Section(stringResource(Res.string.settings_downloads))
                Label(stringResource(Res.string.settings_storage))
                Choices(Settings.storageChoicesMb, s.storageLimitMb, { mb ->
                    if (mb == 0) stringResource(Res.string.settings_unlimited) else formatBytes(mb * 1024L * 1024L)
                }) { v -> vm.update { it.copy(storageLimitMb = v) } }
                Note(stringResource(Res.string.settings_downloads_text))
                // One switch for all shows (device feedback 2026-10-22); a show's own in its menu comes on top.
                Toggle(stringResource(Res.string.settings_auto_downloads), stringResource(Res.string.settings_auto_downloads_note), s.autoDownloads) { on ->
                    vm.setAutoDownloads(on)
                }
                // Up top with the downloads, where it was before (maintainer 2026-11-03: under refresh it was easily missed).
                Toggle(stringResource(Res.string.settings_new_to_queue), stringResource(Res.string.settings_new_to_queue_note), s.newToQueue) { on ->
                    vm.update { it.copy(newToQueue = on) }
                }
                // What the episodes take, and all of them gone at once (device
                // feedback 2026-10-14; where they are said more than it helped).
                val bytes by vm.downloadBytes.collectAsState()
                val count by vm.downloadCount.collectAsState()
                var confirmDelete by remember { mutableStateOf(false) }
                Label(pluralStringResource(Res.plurals.settings_downloads_used, count.toInt(), formatBytes(bytes), count.toInt()))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (actions.canBrowseDownloads) OutlinedButton(onClick = { actions.browseDownloads() }) { Text(stringResource(Res.string.settings_downloads_browse)) }
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = count > 0) { Text(stringResource(Res.string.settings_downloads_delete)) }
                }
                if (confirmDelete) AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    text = { Text(stringResource(Res.string.settings_downloads_delete_confirm, count.toInt(), formatBytes(bytes))) },
                    confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAllDownloads() }) { Text(stringResource(Res.string.settings_downloads_delete)) } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.cancel)) } },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
              }
                // How often Hopp looks for new episodes by itself (ADR 0013; device feedback 2026-10-09).
                Section(stringResource(Res.string.settings_refresh))
                Choices(Settings.refreshChoicesHours, s.refreshHours, { h ->
                    when (h) {
                        0 -> stringResource(Res.string.refresh_off)
                        1 -> stringResource(Res.string.refresh_hourly)
                        24 -> stringResource(Res.string.refresh_daily)
                        else -> stringResource(Res.string.refresh_hours, h)
                    }
                }) { v -> vm.update { it.copy(refreshHours = v) } }
                // Where Hopp looks only while open, the listener chose the network themselves.
                val whileOpen = org.koin.compose.koinInject<space.sovereigncast.hopp.data.FeedRefresh>().whileOpen
                if (s.refreshHours > 0 && !whileOpen) Toggle(stringResource(Res.string.settings_refresh_wifi), stringResource(Res.string.settings_refresh_wifi_note), s.refreshWifiOnly) { on ->
                    vm.update { it.copy(refreshWifiOnly = on) }
                }
                Note(stringResource(if (whileOpen) Res.string.settings_refresh_text_open else Res.string.settings_refresh_text))
                // What a refresh finds goes to "Up next" — in the browser too, which has no downloads block (maintainer 2026-11-03).
                if (!actions.canDownload) Toggle(stringResource(Res.string.settings_new_to_queue), stringResource(Res.string.settings_new_to_queue_note), s.newToQueue) { on ->
                    vm.update { it.copy(newToQueue = on) }
                }
            }
            SettingsPage.Notifications -> {
                // Hopp has no push yet (ADR 0007): the messages are what a payment came to —
                // each case on its own (device feedback 2026-10-19).
                @Composable fun feedback(title: StringResource, value: String, set: (Settings, String) -> Settings) {
                    Section(stringResource(title))
                    Choices(Settings.feedbackChoices, value, { f ->
                        stringResource(when (f) {
                            Settings.FEEDBACK_OFF -> Res.string.feedback_off
                            Settings.FEEDBACK_ALWAYS -> Res.string.feedback_always
                            else -> Res.string.feedback_errors
                        })
                    }) { v -> vm.update { set(it, v) } }
                }
                if (actions.canPay) {
                    feedback(Res.string.settings_quick_boost_feedback, s.quickBoostFeedback) { it, v -> it.copy(quickBoostFeedback = v) }
                    feedback(Res.string.settings_boost_feedback, s.boostFeedback) { it, v -> it.copy(boostFeedback = v) }
                    feedback(Res.string.settings_zap_feedback, s.zapFeedback) { it, v -> it.copy(zapFeedback = v) }
                    Note(stringResource(Res.string.settings_boost_feedback_note))
                }
                // Each system in its own words (device feedback 2026-10-13: iOS read of Android).
                Note(stringResource(if (app.isIos) Res.string.settings_notifications_system_ios else Res.string.settings_notifications_system))
                OutlinedButton(onClick = { actions.openNotificationSettings() }) {
                    Text(stringResource(if (app.isIos) Res.string.settings_notifications_open_ios else Res.string.settings_notifications_open))
                }
            }
            // Light or dark, or as the system says (maintainer 2026-10-30: not to be chosen in Hopp Web).
            SettingsPage.Appearance -> {
                Section(stringResource(Res.string.settings_theme))
                Choices(Settings.themeChoices, s.theme, { t ->
                    stringResource(when (t) {
                        Settings.THEME_LIGHT -> Res.string.settings_theme_light
                        Settings.THEME_DARK -> Res.string.settings_theme_dark
                        else -> Res.string.settings_theme_system
                    })
                }) { v -> vm.update { it.copy(theme = v) } }
            }
            SettingsPage.Directories -> {
                Note(stringResource(Res.string.settings_pi_note))
                Toggle(stringResource(Res.string.settings_fyyd), stringResource(Res.string.settings_sees_searches), s.fyydSearch) { on -> vm.update { it.copy(fyydSearch = on) } }
                Toggle(stringResource(Res.string.settings_apple), stringResource(Res.string.settings_sees_searches), s.appleSearch) { on -> vm.update { it.copy(appleSearch = on) } }
            }
            // Moving over from another podcast app (maintainer, 2026-10-21): where its export is,
            // then one button; taking the subscriptions along below.
            SettingsPage.Subscriptions -> {
                // A file handed over from another app: asked first, at the top.
                var incoming by remember { mutableStateOf<SettingsViewModel.OpmlFile?>(null) }
                LaunchedEffect(Unit) { space.sovereigncast.hopp.ui.IncomingLinks.takeOpmlDraft()?.let { incoming = vm.opmlFile(it) } }
                incoming?.let { file ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.secondaryContainer).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(Res.string.opml_incoming_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(when {
                            file.shows == 0 -> stringResource(Res.string.opml_incoming_none)
                            file.already > 0 -> stringResource(Res.string.opml_incoming_some_here, file.shows, file.already)
                            else -> pluralStringResource(Res.plurals.opml_incoming_count, file.shows, file.shows)
                        })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (file.shows > file.already) Button(onClick = { vm.importOpml(file.text); incoming = null }) { Text(stringResource(Res.string.opml_incoming_import)) }
                            TextButton(onClick = { incoming = null }) { Text(stringResource(if (file.shows > file.already) Res.string.cancel else Res.string.opml_incoming_close)) }
                        }
                    }
                }
                Section(stringResource(Res.string.opml_section))
                Note(stringResource(Res.string.move_text))
                listOf(
                    "AntennaPod" to Res.string.move_antennapod, "Pocket Casts" to Res.string.move_pocketcasts,
                    "Podcast Addict" to Res.string.move_podcastaddict, "Overcast" to Res.string.move_overcast,
                ).forEach { (app, path) ->
                    Column {
                        Text(app, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(path), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                Note(stringResource(Res.string.move_other))
                val importState by vm.import.collectAsState()
                Button(onClick = { actions.pickOpml { vm.importOpml(it) } }, enabled = importState != AddState.Working) { Text(stringResource(Res.string.move_import)) }
                if (importState == AddState.Working) CircularProgressIndicator(Modifier.padding(4.dp))
                (importState as? AddState.Imported)?.let { Text(stringResource(Res.string.result_imported, it.subscribed, it.already, it.failed)) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Section(stringResource(Res.string.move_export_title))
                Note(stringResource(Res.string.move_export_text))
                OutlinedButton(onClick = { scope.launch { actions.exportOpml(vm.exportOpml()) } }) { Text(stringResource(Res.string.opml_export)) }
            }
            SettingsPage.About -> {
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.settings_about), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(Res.string.settings_version, app.version), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    WebBuildInfo()
                } }
                OutlinedButton(onClick = onTour) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.onboarding_again))
                }
                TextButton(onClick = { runCatching { uri.openUri(SOURCE) } }) { Text(stringResource(Res.string.settings_source)) }
                TextButton(onClick = { runCatching { uri.openUri(WEBSITE) } }) { Text(stringResource(Res.string.settings_website)) }
            }
        }
    }
}

private const val SOURCE = "https://gitlab.com/nodesignal/hopp"
private const val WEBSITE = "https://hopp.sovereigncast.space"

@Composable
private fun Section(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Note(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun Toggle(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Hopp Web's build (ADR 0022, 14): its hash, and whether it is the one Nodesignal signed — checked
 * by the page itself, so said as what it is; the hash is there to compare with the signature by
 * hand. Nothing where no web build runs.
 */
@Composable
private fun WebBuildInfo(check: space.sovereigncast.hopp.web.BuildCheck = org.koin.compose.koinInject()) {
    val colors = MaterialTheme.colorScheme
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val state by androidx.compose.runtime.produceState<Pair<space.sovereigncast.hopp.web.WebBuild, space.sovereigncast.hopp.web.BuildStatus?>?>(null) {
        val build = check.current() ?: return@produceState
        value = build to null
        value = build to check.status(build)
    }
    val (build, status) = state ?: return
    Text(stringResource(Res.string.about_build, build.hash), color = colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
    Text(
        stringResource(when (status) {
            null -> Res.string.about_build_checking
            is space.sovereigncast.hopp.web.BuildStatus.Signed -> Res.string.about_build_signed
            is space.sovereigncast.hopp.web.BuildStatus.Differs -> Res.string.about_build_differs
            space.sovereigncast.hopp.web.BuildStatus.Unsigned -> Res.string.about_build_unsigned
            space.sovereigncast.hopp.web.BuildStatus.Unknown -> Res.string.about_build_unknown
        }),
        color = if (status is space.sovereigncast.hopp.web.BuildStatus.Differs) colors.error else colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (status is space.sovereigncast.hopp.web.BuildStatus.Signed) TextButton(onClick = {
        val ref = space.sovereigncast.hopp.nostr.NoteRef(status.eventId, author = space.sovereigncast.hopp.web.BuildCheck.SIGNER)
        runCatching { uri.openUri("https://njump.me/" + space.sovereigncast.hopp.nostr.nevent(ref, space.sovereigncast.hopp.web.BuildCheck.KIND)) }
    }) { Text(stringResource(Res.string.about_build_event)) }
    Text(stringResource(Res.string.about_build_note), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}
