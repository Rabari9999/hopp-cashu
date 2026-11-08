package space.sovereigncast.hopp.ui.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import space.sovereigncast.hopp.identity.IdentityRepository
import space.sovereigncast.hopp.identity.NostrIdentity
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.nostr_sign_in_short
import space.sovereigncast.hopp.resources.nostr_sign_out
import space.sovereigncast.hopp.resources.nostr_title
import space.sovereigncast.hopp.resources.profile_edit
import space.sovereigncast.hopp.resources.profile_menu
import space.sovereigncast.hopp.resources.stats_title

/**
 * The listener's picture at the top — beside the cogwheel, or at the rail's foot — with a menu: who,
 * the Nostr page, the listening statistics, and signing out (maintainer 2026-11-03: three steps deep it
 * was too far, above all in a browser on someone else's computer). In the apps only while signed in —
 * Hopp has no account; in the browser, signed out, it leads to signing in. Since 2026-11-03 sync's state
 * sits at the picture and leads the menu (maintainer): its row opens the sync window, ↻ starts a round,
 * and with sync off it asks to turn it on.
 */
@Composable
fun ProfileButton(
    onNostr: () -> Unit, onStats: () -> Unit, identities: IdentityRepository = koinInject(),
    settingsRepo: space.sovereigncast.hopp.data.SettingsRepository = koinInject(),
    sync: space.sovereigncast.hopp.sync.SyncManager = koinInject(),
) {
    val me by identities.identity.collectAsState()
    val signedIn = me
    if (signedIn == null && identities.keysInApp) return
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var syncSheet by remember { mutableStateOf(false) }
    val syncOn = settingsRepo.settings.collectAsState(null).value?.nostrSync == true
    val status by sync.status.collectAsState()
    val syncing = status.takeIf { syncOn && signedIn != null }
    val label = if (signedIn == null) stringResource(Res.string.nostr_sign_in_short)
        else stringResource(Res.string.profile_menu) + (syncing?.let { " · " + space.sovereigncast.hopp.ui.library.syncStateLabel(it) } ?: "")
    Box {
        IconButton(onClick = { if (signedIn == null) onNostr() else open = true }, modifier = Modifier.testTag("profile-button").semantics { contentDescription = label }) {
            if (signedIn == null) Icon(Icons.Rounded.Person, null)
            else if (syncing != null) space.sovereigncast.hopp.ui.library.SyncBadge(syncing, 28.dp) { Picture(signedIn, 28.dp, null) }
            else Picture(signedIn, 28.dp, null)
        }
        if (signedIn != null) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // The head opens the profile: one entry less (maintainer 2026-11-08), the pencil says so.
            val edit = stringResource(Res.string.profile_edit)
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = edit) { open = false; ProfileEditRequest.request(); onNostr() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(42.dp)) {
                    Picture(signedIn, 38.dp, null)
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)
                            .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Edit, null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.surface) }
                }
                Column {
                    Text(signedIn.name ?: "Nostr", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(signedIn.npub.take(12) + "…" + signedIn.npub.takeLast(6), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            space.sovereigncast.hopp.ui.library.SyncMenuRow(
                syncing, kotlin.time.Clock.System.now().toEpochMilliseconds(),
                onOpen = { open = false; syncSheet = true },
                onSyncNow = { sync.requestRound() },
                onTurnOn = { open = false; onNostr() },
            )
            HorizontalDivider(Modifier.padding(top = 4.dp))
            DropdownMenuItem(text = { Text(stringResource(Res.string.nostr_title)) }, onClick = { open = false; onNostr() })
            DropdownMenuItem(text = { Text(stringResource(Res.string.stats_title)) }, onClick = { open = false; onStats() })
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.nostr_sign_out), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) },
                onClick = { open = false; confirm = true },
            )
        }
    }
    // The Nostr page's model only now: made at the start, it would look for a profile and a library on the relays each time.
    if (syncSheet) {
        val now by androidx.compose.runtime.produceState(kotlin.time.Clock.System.now().toEpochMilliseconds()) {
            while (true) { kotlinx.coroutines.delay(30_000); value = kotlin.time.Clock.System.now().toEpochMilliseconds() }
        }
        space.sovereigncast.hopp.ui.library.SyncSheet(
            status, now, onSyncNow = { sync.requestRound() }, onSettings = { syncSheet = false; onNostr() },
            onRewrite = { sync.requestRound(rewrite = it) },
        ) { syncSheet = false }
    }
    if (confirm && signedIn != null) SignOutDialog(signedIn, koinViewModel<NostrIdentityViewModel>()) { confirm = false }
}

@Composable
private fun Picture(me: NostrIdentity, size: Dp, description: String?) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val picture = me.picture
        if (picture != null) coil3.compose.AsyncImage(picture, description, Modifier.size(size), contentScale = ContentScale.Crop)
        else Text((me.name ?: "N").take(1).uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
