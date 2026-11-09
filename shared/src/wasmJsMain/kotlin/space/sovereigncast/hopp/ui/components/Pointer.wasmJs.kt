package space.sovereigncast.hopp.ui.components

// Compose's text fields type through an input element of the page's own, focused while they edit.
private fun typingInPage(): Boolean =
    js("(() => { const a = document.activeElement; return !!a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.isContentEditable); })()")

internal actual fun textFieldFocused(): Boolean? = typingInPage()
