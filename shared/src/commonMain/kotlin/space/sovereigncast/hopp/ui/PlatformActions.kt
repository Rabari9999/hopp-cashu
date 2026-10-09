package space.sovereigncast.hopp.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** What only the platform can do for the shared UI — file pickers, sharing. */
interface PlatformActions {
    /** Lets the listener pick an OPML file; calls back with its text. */
    fun pickOpml(onText: (String) -> Unit)
    /** Saves or shares an exported OPML file. */
    fun exportOpml(text: String)
    /** Hands text to the system's share sheet (mail, messenger, …). */
    fun shareText(text: String, subject: String) = Unit
}

object NoPlatformActions : PlatformActions {
    override fun pickOpml(onText: (String) -> Unit) = Unit
    override fun exportOpml(text: String) = Unit
}

val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { NoPlatformActions }
