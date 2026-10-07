package space.sovereigncast.hopp.feed

/**
 * HTML entities that feeds use although XML does not define them. Unknown
 * ones stay as written ("&foo;") — visible, but not silently dropped.
 */
internal val HTML_ENTITIES: Map<String, String> = mapOf(
    "nbsp" to " ", "shy" to "­", "copy" to "©", "reg" to "®", "trade" to "™",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "bull" to "•", "middot" to "·", "deg" to "°", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
    "times" to "×", "divide" to "÷", "plusmn" to "±", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
    "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "aacute" to "á", "agrave" to "à", "acirc" to "â",
    "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "ccedil" to "ç", "oslash" to "ø",
    "aring" to "å", "aelig" to "æ", "Eacute" to "É", "Ntilde" to "Ñ", "Ccedil" to "Ç",
    "sect" to "§", "para" to "¶", "iexcl" to "¡", "iquest" to "¿", "rarr" to "→", "larr" to "←",
)
