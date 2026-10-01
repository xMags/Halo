package moe.ditto.halo.screens.player

/**
 * The top bar's stream badges: what this source is, read out of what the addon
 * called it.
 *
 * Addons name sources for a list, not for a player. A real Torrentio result is
 * `"[TB+] Torrentio\n4k HDR"` over four lines of release name, seeders, tracker
 * and flags. The first line of it is the debrid service and the account
 * tier it was fetched with. None of that belongs over someone's video, so
 * nothing here echoes the text it was handed: every badge is a constant from the
 * tables below, selected by matching a pattern against the source's naming. A
 * provider, tracker or account name therefore cannot reach the screen through
 * this path, whatever an addon puts in the field. That is a property of the
 * parser rather than of a filter someone has to remember to apply.
 *
 * Resolution and codec only, per the design. Everything else a release name
 * carries, such as HDR, the source medium, the audio layout and the release
 * group, is either already visible in the picture or not worth the width.
 */

/** How the badge for one fact is spelled, and everything that means it. */
private class BadgeRule(val badge: String, alternatives: List<String>) {
    // Bounded by non-alphanumerics rather than \b so that the dotted spellings
    // (h.264) bound the same way the bare ones do. Sources are lowercased
    // before matching, which keeps the negated class free of case rules that
    // differ between Kotlin's regex backends.
    private val pattern = Regex("(?:^|[^a-z0-9])(?:" + alternatives.joinToString("|") + ")(?:$|[^a-z0-9])")

    fun matches(source: String): Boolean = pattern.containsMatchIn(source)
}

/**
 * Highest first: a pack's naming can mention several, and the one it is named
 * for is the one it leads with.
 */
private val ResolutionRules = listOf(
    BadgeRule("2160p", listOf("2160p?", "4k", "uhd")),
    BadgeRule("1440p", listOf("1440p?", "qhd")),
    BadgeRule("1080p", listOf("1080p?", "fhd")),
    BadgeRule("720p", listOf("720p?")),
    BadgeRule("576p", listOf("576p?")),
    BadgeRule("480p", listOf("480p?")),
)

private val CodecRules = listOf(
    BadgeRule("AV1", listOf("av1")),
    BadgeRule("HEVC", listOf("hevc", "hev1", "h\\.?265", "x\\.?265")),
    // Hi10P names a High 10 profile, which only H.264 has; it says the codec
    // and the bit depth in one token, and the ten-bit rule below reads it too.
    BadgeRule("H.264", listOf("avc", "avc1", "h\\.?264", "x\\.?264", "hi10p?")),
    BadgeRule("VP9", listOf("vp9")),
)

/**
 * Bit depth is a suffix on the codec badge rather than a badge of its own: on
 * its own it says nothing, and the pair is what the design shows.
 */
private val TenBitRule = BadgeRule("10-bit", listOf("10[\\s._-]?bits?", "hi10p?"))

/**
 * Badges for one source, in the order the design lays them out.
 *
 * The parameters are in precedence order, most specific first. The filename
 * describes the file that will actually play; a title can describe a whole
 * season pack, and a name is often just the addon saying who it is. Each fact is
 * taken from the first source that states it, so a 1080p file inside a pack
 * named for its 2160p sibling is badged for itself.
 *
 * Returns an empty list when nothing is recognised, which is the honest answer
 * for a source named only after its provider.
 */
internal fun streamBadges(filename: String?, title: String?, name: String?): List<String> {
    val sources = listOfNotNull(filename, title, name)
        .filter { it.isNotBlank() }
        .map { it.lowercase() }

    val resolution = sources.firstNotNullOfOrNull { source ->
        ResolutionRules.firstOrNull { it.matches(source) }?.badge
    }
    val codec = sources.firstNotNullOfOrNull { source ->
        val rule = CodecRules.firstOrNull { it.matches(source) } ?: return@firstNotNullOfOrNull null
        // Read from the source that named the codec, so the two halves of the
        // badge always describe the same thing.
        if (TenBitRule.matches(source)) "${rule.badge} 10-bit" else rule.badge
    }

    return listOfNotNull(resolution, codec)
}
