package space.sovereigncast.hopp.ui.library

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
    Downloads(Res.string.settings_downloads_refresh),
    Notifications(Res.string.settings_notifications),
    Directories(Res.string.settings_directories),
    Subscriptions(Res.string.settings_subscriptions),
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
    onPage: (SettingsPage) -> Unit = {},
    onPrivacy: () -> Unit = {},
    onCrashReport: (() -> Unit)? = null,
    vm: SettingsViewModel = koinViewModel(),
) {
    val wallet by vm.walletLabel.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Group(stringResource(Res.string.settings_group_value))
        Entry(HoppIcons.Hop, stringResource(Res.string.settings_wallet),
            wallet?.let { stringResource(Res.string.settings_wallet_connected, it) } ?: stringResource(Res.string.settings_wallet_none), accent = true, onClick = onWallet)
        Entry(Icons.Rounded.DateRange, stringResource(Res.string.wallet_history), stringResource(Res.string.settings_history_note), onClick = onHistory)

        Group(stringResource(Res.string.settings_group_app))
        Entry(Icons.Rounded.PlayArrow, stringResource(Res.string.settings_playback), stringResource(Res.string.settings_playback_note)) { onPage(SettingsPage.Playback) }
        Entry(HoppIcons.Download, stringResource(Res.string.settings_downloads_refresh), stringResource(Res.string.settings_downloads_note)) { onPage(SettingsPage.Downloads) }
        Entry(Icons.Rounded.Notifications, stringResource(Res.string.settings_notifications), stringResource(Res.string.settings_notifications_note)) { onPage(SettingsPage.Notifications) }
        Entry(Icons.Rounded.Search, stringResource(Res.string.settings_directories), stringResource(Res.string.settings_directories_note)) { onPage(SettingsPage.Directories) }
        Entry(Icons.AutoMirrored.Rounded.List, stringResource(Res.string.settings_subscriptions), stringResource(Res.string.settings_subscriptions_note)) { onPage(SettingsPage.Subscriptions) }

        Group(stringResource(Res.string.settings_group_hopp))
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
fun SettingsPageScreen(page: SettingsPage, onTour: () -> Unit = {}, vm: SettingsViewModel = koinViewModel()) {
    val app: AppInfo = koinInject()
    val engine: PlaybackEngine = koinInject()
    val settings by vm.current.collectAsState()
    val actions = LocalPlatformActions.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val s = settings ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (page) {
            SettingsPage.Playback -> {
                Label(stringResource(Res.string.settings_skip_back))
                Choices(Settings.skipBackChoices, s.skipBackSeconds, { stringResource(Res.string.settings_seconds, it) }) { v -> vm.update { it.copy(skipBackSeconds = v) } }
                Label(stringResource(Res.string.settings_skip_forward))
                Choices(Settings.skipForwardChoices, s.skipForwardSeconds, { stringResource(Res.string.settings_seconds, it) }) { v -> vm.update { it.copy(skipForwardSeconds = v) } }
                if (engine.supportsSkipSilence) Toggle(stringResource(Res.string.skip_silence), stringResource(Res.string.settings_skip_silence_note), s.skipSilence) { on ->
                    vm.update { it.copy(skipSilence = on) }
                }
            }
            SettingsPage.Downloads -> {
                Section(stringResource(Res.string.settings_downloads))
                Label(stringResource(Res.string.settings_storage))
                Choices(Settings.storageChoicesMb, s.storageLimitMb, { mb ->
                    if (mb == 0) stringResource(Res.string.settings_unlimited) else formatBytes(mb * 1024L * 1024L)
                }) { v -> vm.update { it.copy(storageLimitMb = v) } }
                Note(stringResource(Res.string.settings_downloads_text))
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
                if (s.refreshHours > 0) Toggle(stringResource(Res.string.settings_refresh_wifi), stringResource(Res.string.settings_refresh_wifi_note), s.refreshWifiOnly) { on ->
                    vm.update { it.copy(refreshWifiOnly = on) }
                }
                Note(stringResource(Res.string.settings_refresh_text))
            }
            SettingsPage.Notifications -> {
                // Hopp has no push yet (ADR 0007): the one message is a quick boost's result.
                Section(stringResource(Res.string.settings_boost_feedback))
                Choices(Settings.feedbackChoices, s.boostFeedback, { f ->
                    stringResource(when (f) {
                        Settings.FEEDBACK_OFF -> Res.string.feedback_off
                        Settings.FEEDBACK_ALWAYS -> Res.string.feedback_always
                        else -> Res.string.feedback_errors
                    })
                }) { v -> vm.update { it.copy(boostFeedback = v) } }
                Note(stringResource(Res.string.settings_boost_feedback_note))
                Note(stringResource(Res.string.settings_notifications_system))
                OutlinedButton(onClick = { actions.openNotificationSettings() }) { Text(stringResource(Res.string.settings_notifications_open)) }
            }
            SettingsPage.Directories -> {
                Note(stringResource(Res.string.settings_pi_note))
                Toggle(stringResource(Res.string.settings_fyyd), stringResource(Res.string.settings_sees_searches), s.fyydSearch) { on -> vm.update { it.copy(fyydSearch = on) } }
                Toggle(stringResource(Res.string.settings_apple), stringResource(Res.string.settings_sees_searches), s.appleSearch) { on -> vm.update { it.copy(appleSearch = on) } }
            }
            SettingsPage.Subscriptions -> {
                Note(stringResource(Res.string.settings_opml_note))
                OutlinedButton(onClick = { scope.launch { actions.exportOpml(vm.exportOpml()) } }) { Text(stringResource(Res.string.opml_export)) }
            }
            SettingsPage.About -> {
                Text(stringResource(Res.string.settings_about), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(Res.string.settings_version, app.version), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
