package space.sovereigncast.hopp.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose resources keep a string as written: unlike Android's, they do not
 * unescape `\'`, and the app showed "that\'s you" (found 2026-10-10). Android's
 * own res/values in androidApp still need the escape; these must not have it.
 */
class StringResourcesTest {
    private val dirs = listOf("values", "values-de")
    private fun file(dir: String) = File("src/commonMain/composeResources/$dir/strings.xml")

    @Test
    fun noEscapedApostrophes() {
        dirs.forEach { d ->
            val bad = file(d).readLines().filter { "\\'" in it }
            assertTrue(bad.isEmpty(), "$d: ${bad.joinToString("\n")}")
        }
    }

    @Test
    fun bothLanguagesHaveTheSameStrings() {
        fun names(d: String) = Regex("""<string name="([^"]+)"""").findAll(file(d).readText()).map { it.groupValues[1] }.toSet()
        assertEquals(names("values") - names("values-de"), emptySet(), "missing in German")
        assertEquals(names("values-de") - names("values"), emptySet(), "missing in English")
    }
}
