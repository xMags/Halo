package moe.ditto.halo.browse

/*
 * What the search box holds: words to search the catalogs for, or a link (or
 * bare id) that names one thing directly. A hand-written parser on purpose:
 * what counts as a web link here is what playback may open, so the rules are
 * spelled out rather than left to a general URL parser. A port of
 * `@halo/core`'s `search/input.ts`, held to the same vectors
 * (`packages/core/test/vectors/search-input.json`).
 */

enum class TvdbKind(val wire: String) { Series("series"), Movie("movie") }

sealed interface SearchInput {
    data object Text : SearchInput
    /** `tt` and 7 to 10 digits, lower case. */
    data class ImdbTitle(val imdbId: String) : SearchInput
    /** `nm` and 7 to 10 digits, lower case. */
    data class ImdbPerson(val personId: String) : SearchInput
    data class TvdbNumeric(val tvdbId: String, val tvdbKind: TvdbKind) : SearchInput
    /** [displayName] is the decoded slug, for showing only. */
    data class TvdbSlug(val tvdbKind: TvdbKind, val displayName: String) : SearchInput
    /** 40 lower-case hex characters; [displayName] is the decoded `dn`, for showing only. Trackers are never kept. */
    data class Magnet(val infoHash: String, val displayName: String) : SearchInput
    /** The link as given (checked), its host in lower case, and what the player calls it. */
    data class WebLink(val url: String, val host: String, val displayName: String) : SearchInput
    /** A sentence for the viewer. */
    data class Unsupported(val reason: String) : SearchInput
}

/** The longest link that counts as a web link. */
const val SearchWebLinkMaxLength = 2048
/** The most of a magnet's `dn` (or a TVDB slug) kept for showing. */
const val SearchMagnetNameMaxLength = 200
/** The most of a link's file name the player shows as its title. */
const val SearchWebLinkTitleMaxLength = 120

object UnsupportedReasons {
    const val LocalFile = "Files on this device can't be opened from Search."
    const val OtherScheme = "This kind of link isn't supported."
    const val TooLong = "This link is too long."
    const val Credentials = "Links with a user name or password aren't supported."
    const val InvalidLink = "This link isn't valid."
    const val MagnetV2 = "This magnet link uses the newer v2 format, which isn't supported."
    const val MagnetInvalid = "This magnet link has no valid info hash."
    const val ImdbOther = "This IMDb link isn't a title or a person."
    const val TvdbOther = "This TVDB link isn't a film or a series."
    const val VideoSite = "YouTube and live TV links aren't supported."
}

/**
 * Schemes that are links although no `//` follows them. Anything else with a
 * colon in it (`Re:Zero`, `Dune: Part Two`) stays words to search.
 */
private val OpaqueSchemes = setOf(
    "magnet", "file", "data", "javascript", "vbscript", "mailto", "about", "blob", "urn", "ed2k", "acestream", "stremio",
)

private val VideoSiteHosts = listOf("youtube.com", "youtu.be", "twitch.tv")

/** ASCII-only lower case: what these rules compare by, whatever the locale. */
private fun lowerAscii(text: String): String =
    buildString(text.length) { text.forEach { append(if (it in 'A'..'Z') it + 32 else it) } }

private fun equalsIgnoreCase(left: String, right: String): Boolean = left.length == right.length && lowerAscii(left) == lowerAscii(right)

private fun startsWithIgnoreCase(text: String, prefix: String): Boolean =
    text.length >= prefix.length && equalsIgnoreCase(text.substring(0, prefix.length), prefix)

private fun isDigit(c: Char) = c in '0'..'9'
private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
private fun allDigits(text: String) = text.isNotEmpty() && text.all(::isDigit)

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** `prefix` (two letters, any case) and 7 to 10 digits, as IMDb's ids are; lower case. */
private fun imdbStyleId(text: String, prefix: String): String? {
    if (!startsWithIgnoreCase(text, prefix)) return null
    val digits = text.substring(prefix.length)
    if (digits.length < 7 || digits.length > 10 || !allDigits(digits)) return null
    return prefix + digits
}

private fun appendUtf8(bytes: MutableList<Int>, point: Int) {
    when {
        point < 0x80 -> bytes += point
        point < 0x800 -> { bytes += 0xC0 or (point shr 6); bytes += 0x80 or (point and 0x3F) }
        point < 0x10000 -> {
            bytes += 0xE0 or (point shr 12); bytes += 0x80 or ((point shr 6) and 0x3F); bytes += 0x80 or (point and 0x3F)
        }
        else -> {
            bytes += 0xF0 or (point shr 18); bytes += 0x80 or ((point shr 12) and 0x3F)
            bytes += 0x80 or ((point shr 6) and 0x3F); bytes += 0x80 or (point and 0x3F)
        }
    }
}

private fun StringBuilder.appendCodePoint(point: Int) {
    if (point < 0x10000) {
        append(point.toChar())
        return
    }
    val offset = point - 0x10000
    append((0xD800 + (offset shr 10)).toChar())
    append((0xDC00 + (offset and 0x3FF)).toChar())
}

/**
 * Strict UTF-8: anything malformed (overlong, surrogate, truncated) reads as
 * U+FFFD one byte at a time, so a hostile `%`-sequence cannot smuggle
 * anything through.
 */
private fun decodeUtf8(bytes: List<Int>): String = buildString {
    var index = 0
    while (index < bytes.size) {
        val lead = bytes[index]
        if (lead < 0x80) {
            append(lead.toChar())
            index += 1
            continue
        }
        val (length, start, minimum) = when {
            lead and 0xE0 == 0xC0 -> Triple(2, lead and 0x1F, 0x80)
            lead and 0xF0 == 0xE0 -> Triple(3, lead and 0x0F, 0x800)
            lead and 0xF8 == 0xF0 -> Triple(4, lead and 0x07, 0x10000)
            else -> {
                append('�')
                index += 1
                continue
            }
        }
        var point = start
        var valid = index + length <= bytes.size
        var offset = 1
        while (valid && offset < length) {
            val next = bytes[index + offset]
            if (next and 0xC0 != 0x80) valid = false else point = (point shl 6) or (next and 0x3F)
            offset += 1
        }
        if (!valid || point < minimum || point > 0x10FFFF || point in 0xD800..0xDFFF) {
            append('�')
            index += 1
            continue
        }
        appendCodePoint(point)
        index += length
    }
}

/** `%XX` as bytes of UTF-8 (and `+` as a space in a query value); the characters already in the text are kept as they are. */
private fun percentDecoded(text: String, plusIsSpace: Boolean): String {
    val bytes = mutableListOf<Int>()
    var index = 0
    while (index < text.length) {
        val unit = text[index]
        if (unit == '%' && index + 2 < text.length) {
            val high = hexValue(text[index + 1])
            val low = hexValue(text[index + 2])
            if (high >= 0 && low >= 0) {
                bytes += (high shl 4) or low
                index += 3
                continue
            }
        }
        if (unit == '+' && plusIsSpace) {
            bytes += 0x20
            index += 1
            continue
        }
        val code = unit.code
        val next = text.getOrNull(index + 1)?.code ?: 0
        val point = when {
            code in 0xD800..0xDBFF && next in 0xDC00..0xDFFF -> {
                index += 1
                0x10000 + ((code - 0xD800) shl 10) + (next - 0xDC00)
            }
            code in 0xD800..0xDFFF -> 0xFFFD
            else -> code
        }
        appendUtf8(bytes, point)
        index += 1
    }
    return decodeUtf8(bytes)
}

/** Control characters, and the direction overrides that could make a name read as something it is not. */
private fun isHiddenCharacter(code: Int): Boolean =
    code < 0x20 || code in 0x7F..0x9F || code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0x200E || code == 0x200F

private fun isSpace(code: Int): Boolean = code == 0x20 || code == 0xA0 || code == 0x3000 || code in 0x2000..0x200B

/**
 * What may be shown of a name taken from a link: no hidden characters, no
 * surrounding space, and at most [limit] UTF-16 units without cutting a
 * surrogate pair in half.
 */
private fun displayable(text: String, limit: Int): String {
    var shown = text.filterNot { isHiddenCharacter(it.code) }
    var first = 0
    while (first < shown.length && isSpace(shown[first].code)) first += 1
    var last = shown.length
    while (last > first && isSpace(shown[last - 1].code)) last -= 1
    shown = shown.substring(first, last)
    if (shown.length > limit) {
        var length = limit
        if (length > 0 && shown[length - 1].isHighSurrogate()) length -= 1
        shown = shown.substring(0, length).trimEnd { isSpace(it.code) }
    }
    return shown
}

private fun isDrivePath(text: String): Boolean =
    text.length >= 3 && isAsciiLetter(text[0]) && text[1] == ':' && (text[2] == '\\' || text[2] == '/')

private fun isUncPath(text: String): Boolean = text.startsWith("\\\\") || text.startsWith("//")

/** The scheme, lower case, when [text] is a link: a valid scheme of two or more characters followed by `//`, or an opaque scheme. */
private fun linkScheme(text: String): String? {
    val colon = text.indexOf(':')
    if (colon < 2 || !isAsciiLetter(text[0])) return null
    for (index in 1 until colon) {
        val unit = text[index]
        if (!isAsciiLetter(unit) && !isDigit(unit) && unit != '+' && unit != '-' && unit != '.') return null
    }
    val scheme = lowerAscii(text.substring(0, colon))
    if (text.substring(colon + 1).startsWith("//")) return scheme
    return scheme.takeIf { it in OpaqueSchemes }
}

private fun pathSegments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

/** `key=value` pairs of a query, keys in lower case, values left encoded. */
private fun queryPairs(query: String): List<Pair<String, String>> = query.split('&').mapNotNull { part ->
    val equals = part.indexOf('=')
    if (equals < 0) null else lowerAscii(part.substring(0, equals)) to part.substring(equals + 1)
}

private fun hostIsOrUnder(host: String, domain: String): Boolean =
    host == domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.')

/** RFC 4648 base32 (A to Z, 2 to 7), any case, 32 characters: 20 bytes, as hex. */
private fun base32ToHex(text: String): String? {
    if (text.length != 32) return null
    val hex = StringBuilder(40)
    var buffer = 0
    var bits = 0
    for (unit in text) {
        val lower = if (unit in 'A'..'Z') unit + 32 else unit
        val digit = when (lower) {
            in 'a'..'z' -> lower - 'a'
            in '2'..'7' -> lower - '2' + 26
            else -> return null
        }
        buffer = ((buffer shl 5) or digit) and 0xFFFFF
        bits += 5
        while (bits >= 4) {
            bits -= 4
            hex.append("0123456789abcdef"[(buffer shr bits) and 0xF])
        }
    }
    return hex.toString()
}

/** 40 hex characters or 32 base32 ones, as lower-case hex. */
private fun normalizedInfoHash(text: String): String? {
    if (text.length == 40) return if (text.all { hexValue(it) >= 0 }) lowerAscii(text) else null
    return base32ToHex(text)
}

private fun isMagnetTopicKey(key: String): Boolean = key == "xt" || (key.startsWith("xt.") && allDigits(key.substring(3)))

private fun classifyMagnet(text: String): SearchInput {
    val prefix = "magnet:?"
    if (!startsWithIgnoreCase(text, prefix)) return SearchInput.Unsupported(UnsupportedReasons.MagnetInvalid)
    var query = text.substring(prefix.length)
    val fragment = query.indexOf('#')
    if (fragment >= 0) query = query.substring(0, fragment)
    val hashes = linkedSetOf<String>()
    var invalidHash = false
    var sawV2 = false
    var name: String? = null
    for ((key, value) in queryPairs(query)) {
        if (isMagnetTopicKey(key)) {
            val topic = percentDecoded(value, plusIsSpace = false)
            if (startsWithIgnoreCase(topic, "urn:btih:")) {
                val hash = normalizedInfoHash(topic.substring(9))
                if (hash != null) hashes += hash else invalidHash = true
            } else if (startsWithIgnoreCase(topic, "urn:btmh:")) {
                sawV2 = true
            }
        } else if (key == "dn" && name == null) {
            name = displayable(percentDecoded(value, plusIsSpace = true), SearchMagnetNameMaxLength)
        }
        // `tr` and every other key are dropped: no tracker is ever kept.
    }
    if (!invalidHash && hashes.size == 1) return SearchInput.Magnet(hashes.first(), name ?: "")
    if (!invalidHash && hashes.isEmpty() && sawV2) return SearchInput.Unsupported(UnsupportedReasons.MagnetV2)
    return SearchInput.Unsupported(UnsupportedReasons.MagnetInvalid)
}

private fun classifyImdb(path: List<String>): SearchInput {
    var segments = path
    // IMDb's localised pages put the language first: `/fr/title/tt…`.
    if (segments.size > 2 && (segments[0].length == 2 || (segments[0].length == 5 && segments[0][2] == '-'))) {
        segments = segments.drop(1)
    }
    if (segments.size >= 2 && equalsIgnoreCase(segments[0], "title")) {
        imdbStyleId(segments[1], "tt")?.let { return SearchInput.ImdbTitle(it) }
    }
    if (segments.size >= 2 && equalsIgnoreCase(segments[0], "name")) {
        imdbStyleId(segments[1], "nm")?.let { return SearchInput.ImdbPerson(it) }
    }
    return SearchInput.Unsupported(UnsupportedReasons.ImdbOther)
}

private fun classifyTvdb(segments: List<String>, query: String): SearchInput {
    if (
        segments.size >= 3 && equalsIgnoreCase(segments[0], "dereferrer") &&
        (equalsIgnoreCase(segments[1], "series") || equalsIgnoreCase(segments[1], "movie")) &&
        allDigits(segments[2]) && segments[2].length <= 10
    ) {
        val kind = if (equalsIgnoreCase(segments[1], "series")) TvdbKind.Series else TvdbKind.Movie
        return SearchInput.TvdbNumeric(segments[2], kind)
    }
    if (segments.size >= 2 && (equalsIgnoreCase(segments[0], "series") || equalsIgnoreCase(segments[0], "movies"))) {
        return SearchInput.TvdbSlug(
            tvdbKind = if (equalsIgnoreCase(segments[0], "series")) TvdbKind.Series else TvdbKind.Movie,
            displayName = displayable(percentDecoded(segments[1], plusIsSpace = false), SearchMagnetNameMaxLength),
        )
    }
    if (segments.isEmpty() || (segments.size == 1 && equalsIgnoreCase(segments[0], "index.php"))) {
        var seriesTab = false
        var id: String? = null
        for ((key, value) in queryPairs(query)) {
            if (key == "tab" && equalsIgnoreCase(value, "series")) seriesTab = true
            if (key == "id" && allDigits(value) && value.length <= 10) id = value
        }
        if (seriesTab && id != null) return SearchInput.TvdbNumeric(id, TvdbKind.Series)
    }
    return SearchInput.Unsupported(UnsupportedReasons.TvdbOther)
}

/** What the player calls a pasted link: its file name, else its host. */
private fun webLinkTitle(segments: List<String>, host: String): String {
    val last = segments.lastOrNull() ?: return host
    return displayable(percentDecoded(last, plusIsSpace = false), SearchWebLinkTitleMaxLength).ifEmpty { host }
}

private fun hostOf(authority: String): String? {
    var host = authority
    var port = ""
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        host = authority.substring(0, close + 1)
        val rest = authority.substring(close + 1)
        if (rest.isNotEmpty()) {
            if (rest[0] != ':') return null
            port = rest.substring(1)
        }
        if (host.substring(1, host.length - 1).any { hexValue(it) < 0 && it != ':' && it != '.' }) return null
        if (host.length <= 2) return null
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            host = authority.substring(0, colon)
            port = authority.substring(colon + 1)
        }
        if (host.any { !isAsciiLetter(it) && !isDigit(it) && it != '-' && it != '.' && it != '_' && it.code < 0x80 }) return null
    }
    if (port.isNotEmpty()) {
        if (port.length > 5 || !allDigits(port)) return null
        val number = port.toInt()
        if (number == 0 || number > 65535) return null
    }
    var lower = lowerAscii(host)
    if (lower.endsWith('.')) lower = lower.dropLast(1)
    if (lower.isEmpty() || lower.startsWith('.')) return null
    return lower
}

private fun classifyWeb(text: String, schemeLength: Int): SearchInput {
    if (text.length > SearchWebLinkMaxLength) return SearchInput.Unsupported(UnsupportedReasons.TooLong)
    // White space, control characters and backslashes have no place in a link, and different parsers read them differently.
    if (text.any { it.code <= 0x20 || isHiddenCharacter(it.code) || isSpace(it.code) || it == '\\' }) {
        return SearchInput.Unsupported(UnsupportedReasons.InvalidLink)
    }
    var rest = text.substring(schemeLength + 1)
    if (!rest.startsWith("//")) return SearchInput.Unsupported(UnsupportedReasons.InvalidLink)
    rest = rest.substring(2)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    if ('@' in authority) return SearchInput.Unsupported(UnsupportedReasons.Credentials)
    val host = hostOf(authority) ?: return SearchInput.Unsupported(UnsupportedReasons.InvalidLink)
    var tail = rest.substring(authorityEnd)
    val fragment = tail.indexOf('#')
    if (fragment >= 0) tail = tail.substring(0, fragment)
    var query = ""
    val question = tail.indexOf('?')
    if (question >= 0) {
        query = tail.substring(question + 1)
        tail = tail.substring(0, question)
    }
    val segments = pathSegments(tail)
    if (VideoSiteHosts.any { hostIsOrUnder(host, it) }) return SearchInput.Unsupported(UnsupportedReasons.VideoSite)
    if (host == "imdb.com" || host == "www.imdb.com" || host == "m.imdb.com") return classifyImdb(segments)
    if (hostIsOrUnder(host, "imdb.com")) return SearchInput.Unsupported(UnsupportedReasons.ImdbOther)
    if (host == "thetvdb.com" || host == "www.thetvdb.com") return classifyTvdb(segments, query)
    return SearchInput.WebLink(text, host, webLinkTitle(segments, host))
}

/** Reads the box's text, trimmed of surrounding white space. */
fun classifySearchInput(trimmed: String): SearchInput {
    if (trimmed.isEmpty()) return SearchInput.Text
    imdbStyleId(trimmed, "tt")?.let { return SearchInput.ImdbTitle(it) }
    if (isDrivePath(trimmed) || isUncPath(trimmed)) return SearchInput.Unsupported(UnsupportedReasons.LocalFile)
    val scheme = linkScheme(trimmed) ?: return SearchInput.Text
    return when (scheme) {
        "magnet" -> classifyMagnet(trimmed)
        "http", "https" -> classifyWeb(trimmed, scheme.length)
        "file" -> SearchInput.Unsupported(UnsupportedReasons.LocalFile)
        else -> SearchInput.Unsupported(UnsupportedReasons.OtherScheme)
    }
}
