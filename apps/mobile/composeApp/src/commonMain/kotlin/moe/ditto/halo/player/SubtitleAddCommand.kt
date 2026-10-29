package moe.ditto.halo.player

/**
 * The argv of mpv's `sub-add <url> <flags> [<title> [<lang>]]`.
 *
 * The arguments are positional, so a language cannot be given without a title
 * in front of it. When only a language is known the title slot is filled with
 * an empty string, which mpv treats as no title. Blank values count as absent.
 *
 * `cached` is always the flag: it re-selects a URL already added instead of
 * adding a second, identical track.
 *
 * The iOS core builds the same argv in Swift (`MPVCore.addSubtitle`), so a
 * change here has to be mirrored there.
 */
internal fun subAddArguments(url: String, title: String?, lang: String?): List<String> {
    val arguments = mutableListOf("sub-add", url, "cached")
    val namedTitle = title?.takeIf { it.isNotBlank() }
    val namedLang = lang?.takeIf { it.isNotBlank() }
    if (namedTitle == null && namedLang == null) return arguments
    arguments += namedTitle.orEmpty()
    if (namedLang != null) arguments += namedLang
    return arguments
}
