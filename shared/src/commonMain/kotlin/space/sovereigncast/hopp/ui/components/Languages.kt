package space.sovereigncast.hopp.ui.components

/**
 * One language Hopp speaks (ADR 0016): its code (the `values-<code>` folder), its own name, and how
 * it writes numbers and dates — so a new language is a line here and a folder of strings, not a
 * search for `== "de"` (maintainer 2026-11-02, preparing Spanish). [complete]: every string
 * translated; a language that is not may be shipped, its missing strings in English.
 */
data class Language(
    val code: String,
    val name: String,
    val decimal: Char,
    val grouping: Char,
    /** Day, month and year: "07.10.2026" with '.', "07/10/2026" with '/'. */
    val dateSeparator: Char,
    /** What a second browser tab shows (ADR 0022) — before the app and its strings are there: a title, a line. */
    val otherTab: Pair<String, String>,
    val complete: Boolean = true,
)

/**
 * The languages Hopp speaks, in one place. Android's `locales_config.xml`, iOS's
 * `CFBundleLocalizations` and the folders of strings are checked against it (LanguagesTest); the
 * browser's language choice and the "open in another tab" page read it.
 */
object Languages {
    val all = listOf(
        // English as Hopp has written it so far: a decimal point, dates with dots (as before 0.4.0).
        Language("en", "English", decimal = '.', grouping = ',', dateSeparator = '.',
            otherTab = "Hopp is open in another tab." to "Close it, or switch to it."),
        Language("de", "Deutsch", decimal = ',', grouping = '.', dateSeparator = '.',
            otherTab = "Hopp ist schon in einem anderen Tab offen." to "Schließ ihn oder wechsle dorthin."),
        // A draft, to be read by a native speaker (maintainer 2026-11-02).
        Language("es", "Español", decimal = ',', grouping = '.', dateSeparator = '/',
            otherTab = "Hopp ya está abierto en otra pestaña." to "Ciérrala o cambia a ella."),
        // A draft, to be read by a native speaker (maintainer 2026-11-06).
        Language("fr", "Français", decimal = ',', grouping = '\u202F', dateSeparator = '/',
            otherTab = "Hopp est déjà ouvert dans un autre onglet." to "Ferme-le, ou bascule dessus."),
    )

    /** The language for a code ("de", "de-AT"); English for any other. */
    fun of(code: String?): Language {
        val base = code.orEmpty().lowercase().substringBefore('-').substringBefore('_')
        return all.firstOrNull { it.code == base } ?: all.first()
    }

    /** The code of the language the UI is shown in now. */
    fun current(): String = androidx.compose.ui.text.intl.Locale.current.language
}
