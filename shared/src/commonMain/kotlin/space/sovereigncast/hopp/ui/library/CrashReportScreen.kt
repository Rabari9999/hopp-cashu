package space.sovereigncast.hopp.ui.library

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.http.encodeURLParameter
import org.jetbrains.compose.resources.stringResource
import space.sovereigncast.hopp.resources.Res
import space.sovereigncast.hopp.resources.crash_delete
import space.sovereigncast.hopp.resources.crash_intro
import space.sovereigncast.hopp.resources.crash_issue
import space.sovereigncast.hopp.resources.crash_share
import space.sovereigncast.hopp.ui.LocalPlatformActions

/** Where an issue can be filed — the repository is public. */
private const val ISSUES = "https://gitlab.com/nodesignal/hopp/-/issues/new"

/**
 * The last crash report, exactly as it would leave the phone (ADR 0015):
 * the listener reads it, then shares it themselves — through any app, or as
 * a GitLab issue — or deletes it. Hopp sends nothing on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashReportScreen(report: String?, onDelete: () -> Unit) {
    val actions = LocalPlatformActions.current
    val uri = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.crash_intro), style = MaterialTheme.typography.bodyMedium)
        val text = report ?: return@Column
        SelectionContainer {
            Text(
                text,
                Modifier.fillMaxWidth().border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.shareText(text, "Hopp crash report") }) { Text(stringResource(Res.string.crash_share)) }
            OutlinedButton(onClick = {
                val title = text.lineSequence().firstOrNull { it.startsWith("Caused by") || it.contains("Exception") || it.contains("Error") }
                    ?.take(120) ?: "Crash"
                val body = "```\n" + text.take(4_000) + "\n```"
                runCatching { uri.openUri("$ISSUES?issue[title]=${title.encodeURLParameter()}&issue[description]=${body.encodeURLParameter()}") }
            }) { Text(stringResource(Res.string.crash_issue)) }
            TextButton(onClick = onDelete) { Text(stringResource(Res.string.crash_delete)) }
        }
    }
}
