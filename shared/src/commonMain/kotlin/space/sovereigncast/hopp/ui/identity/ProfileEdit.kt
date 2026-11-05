package space.sovereigncast.hopp.ui.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.identity.NostrIdentity
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.cancel
import space.sovereigncast.hopp.resources.nostr_name
import space.sovereigncast.hopp.resources.profile_address
import space.sovereigncast.hopp.resources.profile_address_hint
import space.sovereigncast.hopp.resources.profile_bad_address
import space.sovereigncast.hopp.resources.profile_boost_name
import space.sovereigncast.hopp.resources.profile_empty_action
import space.sovereigncast.hopp.resources.profile_empty_title
import space.sovereigncast.hopp.resources.profile_failed_relays
import space.sovereigncast.hopp.resources.profile_failed_signer
import space.sovereigncast.hopp.resources.profile_failed_upload
import space.sovereigncast.hopp.resources.profile_note
import space.sovereigncast.hopp.resources.profile_pick
import space.sovereigncast.hopp.resources.profile_picture
import space.sovereigncast.hopp.resources.profile_reading
import space.sovereigncast.hopp.resources.profile_remove_picture
import space.sovereigncast.hopp.resources.profile_retry
import space.sovereigncast.hopp.resources.profile_save
import space.sovereigncast.hopp.resources.profile_signing
import space.sovereigncast.hopp.resources.profile_title
import space.sovereigncast.hopp.resources.profile_unknown
import space.sovereigncast.hopp.resources.profile_uploading
import space.sovereigncast.hopp.ui.LocalPlatformActions

/** "Edit profile" from the profile picture's menu: the Nostr page opens with the window open. */
object ProfileEditRequest {
    private var pending = false
    fun request() { pending = true }
    fun take(): Boolean = pending.also { pending = false }
}

/**
 * A round picture: the one picked ([bytes]), else the one at [url], else the name's first letter —
 * with a pencil when it can be changed ([onClick]).
 */
@Composable
internal fun ProfilePicture(size: Dp, url: String?, bytes: ByteArray?, initial: String, onClick: (() -> Unit)? = null) {
    val label = stringResource(Res.string.profile_picture)
    Box(Modifier.size(size + 6.dp), contentAlignment = Alignment.TopStart) {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bytes != null -> coil3.compose.AsyncImage(bytes, null, Modifier.size(size), contentScale = ContentScale.Crop)
                url != null -> coil3.compose.AsyncImage(url, null, Modifier.size(size), contentScale = ContentScale.Crop)
                else -> Text(initial.take(1).uppercase(), style = if (size > 64.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (onClick != null) Box(
            Modifier.align(Alignment.BottomEnd).offset((-2).dp, (-2).dp).size(if (size > 64.dp) 28.dp else 22.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface).border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Edit, null, Modifier.size(if (size > 64.dp) 15.dp else 12.dp), tint = MaterialTheme.colorScheme.surface) }
    }
}

/** On the Nostr page while the profile has neither name nor picture — typical of a new account from a signer. */
@Composable
internal fun EmptyProfileCard(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) { Text("?", fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.profile_empty_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.profile_empty_action), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The profile window (maintainer 2026-11-03), the same in the apps and the browser: the picture —
 * a photo picked, made square and small without its metadata and uploaded, or an address pasted —
 * the name, and whether it is the boost name too. What the relays hold is read first and kept but
 * name and picture; when it cannot be read, nothing is saved over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSheet(me: NostrIdentity, boostName: String, vm: NostrIdentityViewModel, onDismiss: () -> Unit) {
    val actions = LocalPlatformActions.current
    val colors = MaterialTheme.colorScheme
    val state by vm.editor.collectAsState()
    LaunchedEffect(Unit) { vm.openProfileEditor() }
    val st = state
    LaunchedEffect(st?.done) { if (st?.done == true) { vm.closeProfileEditor(); onDismiss() } }
    var name by remember(me.pubkey) { mutableStateOf(me.name.orEmpty()) }
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var removed by remember { mutableStateOf(false) }
    var addressOpen by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var asBoostName by remember(boostName, me.name) { mutableStateOf(boostName.isBlank() || boostName == me.name) }
    val addressOk = address.isBlank() || (address.trim().startsWith("https://") && ' ' !in address.trim())
    val shownUrl = when {
        removed -> null
        addressOpen && address.isNotBlank() && addressOk -> address.trim()
        else -> st?.picture ?: me.picture
    }
    val close = { vm.closeProfileEditor(); onDismiss() }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.profile_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val pick = { actions.pickProfileImage { picked = it; removed = false; addressOpen = false; address = "" } }
                ProfilePicture(88.dp, shownUrl, picked.takeIf { !removed }, name.ifBlank { "N" }, onClick = if (actions.canPickImage) pick else null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (actions.canPickImage) OutlinedButton(onClick = pick) { Text(stringResource(Res.string.profile_pick)) }
                    OutlinedButton(onClick = { addressOpen = !addressOpen }) { Text(stringResource(Res.string.profile_address)) }
                }
                if ((picked != null || shownUrl != null) && !removed) TextButton(onClick = { removed = true; picked = null; address = ""; addressOpen = false }) {
                    Text(stringResource(Res.string.profile_remove_picture), color = colors.onSurfaceVariant)
                }
            }
            if (addressOpen) OutlinedTextField(
                address, { address = it.trim().take(500); if (it.isNotBlank()) { picked = null; removed = false } }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(Res.string.profile_address_hint)) },
                isError = !addressOk, supportingText = if (!addressOk) ({ Text(stringResource(Res.string.profile_bad_address)) }) else null,
            )
            OutlinedTextField(name, { name = it.replace("\n", "").take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(Res.string.nostr_name)) })
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { asBoostName = !asBoostName },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(asBoostName, onCheckedChange = { asBoostName = it })
                Text(stringResource(Res.string.profile_boost_name), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(Res.string.profile_note), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            when {
                st == null || st.reading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.profile_reading), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                st.unknown -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Res.string.profile_unknown), color = colors.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::openProfileEditor) { Text(stringResource(Res.string.profile_retry)) }
                }
                st.error != null -> Text(
                    when (st.error) {
                        NostrIdentityViewModel.ProfileEditor.Error.Upload -> stringResource(Res.string.profile_failed_upload, st.errorDetail ?: "?")
                        NostrIdentityViewModel.ProfileEditor.Error.Relays -> stringResource(Res.string.profile_failed_relays)
                        NostrIdentityViewModel.ProfileEditor.Error.Signer -> stringResource(Res.string.profile_failed_signer)
                    },
                    color = colors.error, style = MaterialTheme.typography.bodySmall,
                )
            }
            val working = st?.step != null
            Button(
                onClick = {
                    val change = when {
                        removed -> NostrIdentityViewModel.PictureChange.Remove
                        picked != null -> NostrIdentityViewModel.PictureChange.Upload(picked!!)
                        addressOpen && address.isNotBlank() -> NostrIdentityViewModel.PictureChange.Address(address.trim())
                        else -> NostrIdentityViewModel.PictureChange.Keep
                    }
                    vm.saveProfile(name, change, asBoostName)
                },
                enabled = st?.base != null && !working && addressOk && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                    Text(
                        stringResource(if (st?.step == NostrIdentityViewModel.ProfileEditor.Step.Uploading) Res.string.profile_uploading else Res.string.profile_signing),
                        Modifier.padding(start = 10.dp),
                    )
                } else Text(stringResource(Res.string.profile_save), fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.cancel)) }
        }
    }
}
