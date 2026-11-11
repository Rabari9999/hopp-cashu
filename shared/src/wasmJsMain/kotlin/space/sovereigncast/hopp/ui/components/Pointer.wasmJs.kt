package space.sovereigncast.hopp.ui.components

// Compose's text fields type through an input element of the page's own, focused while they edit.
private fun typingInPage(): Boolean =
    js("(() => { const a = document.activeElement; return !!a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.isContentEditable); })()")

internal actual fun textFieldFocused(): Boolean? = typingInPage()

// The page's wheel event in pixels, as Compose scrolls its own lists by them (measured 2026-11-10).
internal actual fun scrollUnitPx(density: androidx.compose.ui.unit.Density): Float = 1f
