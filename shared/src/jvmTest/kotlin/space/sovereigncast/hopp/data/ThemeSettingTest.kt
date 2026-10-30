package space.sovereigncast.hopp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Maintainer 2026-10-30: light or dark to be chosen, not only the system's — kept on this device. */
class ThemeSettingTest {
    @Test
    fun theChoiceIsKeptAndTheSystemsIsTheDefault() {
        assertEquals(Settings.THEME_SYSTEM, Settings().theme)
        assertEquals(Settings.THEME_DARK, Settings.from(Settings(theme = Settings.THEME_DARK).toMap()).theme)
        assertEquals(Settings.THEME_SYSTEM, Settings.from(mapOf("theme" to "purple")).theme, "an unknown value is the system's")
        assertFalse("theme" in Settings.SYNCED, "each device its own")
    }
}
