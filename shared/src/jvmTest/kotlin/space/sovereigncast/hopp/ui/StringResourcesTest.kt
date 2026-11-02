package space.sovereigncast.hopp.ui

import space.sovereigncast.hopp.ui.components.Languages
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose resources keep a string as written: unlike Android's, they do not
 * unescape `\'`, and the app showed "that\'s you" (found 2026-10-10). Android's
 * own res/values in androidApp still need the escape; these must not have it.
 *
 * Every language's strings against English (preparing Spanish, maintainer 2026-11-02): the same
 * names, plurals and arrays, the same placeholders in each — a translation that drops "%1$d"
 * shows a sentence without its number, one that adds "%2$s" crashes. A language [Languages]
 * marks complete has every string; another may lack some, which then show in English.
 */
class StringResourcesTest {
    private val root = File("src/commonMain/composeResources")
    private val dirs = root.listFiles().orEmpty().filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) && File(it, "strings.xml").exists() }
        .map { it.name }.sorted()
    private fun file(dir: String) = File(root, "$dir/strings.xml")

    /** Each entry's name and kind, with the text of each item: one for a string, one per quantity or element. */
    private fun entries(dir: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(dir))
        val out = linkedMapOf<String, List<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val name = e.getAttribute("name")
            val items = when (e.tagName) {
                "string" -> listOf(e.textContent)
                else -> e.getElementsByTagName("item").let { l -> (0 until l.length).map { l.item(it).textContent } }
            }
            out["${e.tagName}:$name"] = items
        }
        return out
    }

    private fun placeholders(text: String) = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun noEscapedApostrophes() {
        dirs.forEach { d ->
            val bad = file(d).readLines().filter { "\\'" in it }
            assertTrue(bad.isEmpty(), "$d: ${bad.joinToString("\n")}")
        }
    }

    /** One folder per language Hopp lists, and no folder it does not list. */
    @Test
    fun theFoldersAreTheLanguages() {
        val listed = Languages.all.map { if (it.code == "en") "values" else "values-${it.code}" }.sorted()
        assertEquals(listed, dirs)
    }

    @Test
    fun everyLanguageHasEnglishsStringsAndPlaceholders() {
        val english = entries("values")
        for (d in dirs - "values") {
            val code = d.removePrefix("values-")
            val other = entries(d)
            assertEquals(emptySet(), other.keys - english.keys, "$d has strings English does not")
            if (Languages.of(code).complete) assertEquals(emptySet(), english.keys - other.keys, "missing in $d")
            for ((key, items) in other) {
                val en = english.getValue(key)
                // A plural's quantities differ between languages ("one" may say the number in words): all of them together.
                val mine = if (key.startsWith("plurals:")) listOf(items.flatMap(::placeholders).toSortedSet().toList()) else items.map(::placeholders)
                val theirs = if (key.startsWith("plurals:")) listOf(en.flatMap(::placeholders).toSortedSet().toList()) else en.map(::placeholders)
                assertEquals(theirs, mine, "placeholders of $key in $d")
                if (key.startsWith("string-array:")) assertEquals(en.size, items.size, "items of $key in $d")
            }
        }
    }
}
