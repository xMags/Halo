package moe.ditto.halo.magnets

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import moe.ditto.halo.browse.scriptTrimEnd
import moe.ditto.halo.browse.scriptTrim

/*
 * A pasted magnet's request as a server answers it (`POST /magnets`,
 * `GET /magnets/:id`). Everything is untrusted and checked strictly: a state
 * outside the fixed list, a size that is not a whole non-negative number, an
 * id of the wrong shape, or a play link whose path is not exactly
 * `/play/m1.<token>/<file name>` makes the whole answer an error. A play link
 * is kept only as that path, rebuilt on the signed-in server: whatever host
 * the server wrote is never opened. Names are display only, so they are cut
 * rather than refused. A port of `@halo/core`'s `magnets/readers.ts`, held to
 * `packages/core/test/vectors/magnets.json`.
 */

enum class MagnetState(val wire: String) {
    Ready("ready"), NotCached("not_cached"), Downloading("downloading"), NoVideo("no_video"), TooLarge("too_large"), Failed("failed"),
}

data class MagnetFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val season: Int?,
    val episode: Int?,
    /** On the signed-in server, never the host the answer named. */
    val playUrl: String,
)

data class MagnetIdentity(val type: String, val id: String, val name: String, val year: String?, val poster: String?)

data class MagnetRequest(
    val id: String,
    val state: MagnetState,
    val reason: String?,
    val name: String,
    val sizeBytes: Long,
    val progress: Double,
    /** Only a ready request has files, and it always has some. */
    val files: List<MagnetFile>,
    val identified: MagnetIdentity?,
)

class MagnetAnswerException(message: String) : Exception(message)

private const val MaxId = 64
private const val MaxFileId = 32
private const val MaxReason = 40
private const val MaxName = 300
private const val MaxTitleName = 200
private const val MaxPlayUrl = 4096
/** The server lists at most 200 video files. */
private const val MaxFiles = 200
private const val MaxSeason = 1000
private const val MaxEpisode = 10000
private const val MaxToken = 2048
private const val MaxSafeInteger = 9_007_199_254_740_991.0
private const val TmdbImagePrefix = "https://image.tmdb.org/t/p/"
private val PlainId = Regex("""^[A-Za-z0-9_-]+$""")
private val ReasonShape = Regex("""^[a-z_]+$""")
private val ImdbTitleId = Regex("""^tt[0-9]{1,10}$""")
private val FourDigits = Regex("""^[0-9]{4}$""")
private val MagnetToken = Regex("""^m1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")
private val ImagePath = Regex("""^[A-Za-z0-9/_.-]+$""")

private fun refuse(why: String): Nothing = throw MagnetAnswerException(why)

private enum class Kind { Text, Number, Object, List }

/** A field that is present must have the expected type; absent or null is fine. */
private fun JsonObject.field(key: String, kind: Kind, why: String): JsonElement? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val ok = when (kind) {
        Kind.Text -> value is JsonPrimitive && value.isString
        Kind.Number -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        Kind.Object -> value is JsonObject
        Kind.List -> value is JsonArray
    }
    if (!ok) refuse(why)
    return value
}

private fun JsonObject.required(key: String, kind: Kind, why: String): JsonElement = field(key, kind, why) ?: refuse(why)

private fun JsonElement.text(): String = (this as JsonPrimitive).content
private fun JsonElement.number(): Double = (this as JsonPrimitive).doubleOrNull ?: Double.NaN

/** Trimmed, control characters as spaces, cut at [maximum] (never on half of a surrogate pair). */
private fun displayText(raw: String, maximum: Int): String {
    var text = raw.scriptTrim().take(maximum)
    if (text.isNotEmpty() && text.last().isHighSurrogate()) text = text.dropLast(1)
    return text.map { if (it.code < 0x20 || it.code in 0x7F..0x9F) ' ' else it }.joinToString("").scriptTrimEnd()
}

private fun JsonObject.wholeSize(key: String, why: String): Long {
    val number = required(key, Kind.Number, why).number()
    if (!number.isFinite() || number < 0 || number > MaxSafeInteger || number != kotlin.math.floor(number)) refuse(why)
    return number.toLong()
}

private fun JsonObject.smallCount(key: String, maximum: Int, why: String): Int? {
    val number = field(key, Kind.Number, why)?.number() ?: return null
    if (!number.isFinite() || number < 0 || number > maximum || number != kotlin.math.floor(number)) refuse(why)
    return number.toInt()
}

private fun isPlainId(text: String, maximum: Int) = text.isNotEmpty() && text.length <= maximum && PlainId.matches(text)

private class Origin(val scheme: String, val rest: String)

/**
 * scheme://host[:port] of an absolute http(s) link, or null. No user name or
 * password, no white space or control characters, a host of letters, digits,
 * dots and hyphens (or a bracketed IPv6 literal).
 */
private fun originOf(url: String): Origin? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = url.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val afterScheme = url.substring(schemeEnd + 3)
    val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
    val rest = if (authorityEnd < 0) "" else afterScheme.substring(authorityEnd)
    if (authority.isEmpty()) return null
    var host = authority
    var port = ""
    var portGiven = false
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        host = authority.substring(0, close + 1)
        val after = authority.substring(close + 1)
        if (after.isNotEmpty()) {
            if (after[0] != ':') return null
            port = after.substring(1)
            portGiven = true
        }
        val inside = host.substring(1, host.length - 1)
        if (inside.isEmpty() || inside.any { !it.isDigit() && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' && it != '.' }) return null
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            host = authority.substring(0, colon)
            port = authority.substring(colon + 1)
            portGiven = true
        }
        if (host.isEmpty() || host.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '.' && it != '-' }) return null
    }
    if (portGiven && port.isEmpty()) return null
    if (port.isNotEmpty() && (port.length > 5 || port.any { it !in '0'..'9' })) return null
    return Origin(scheme, rest)
}

/** A path of printable ASCII only, as the server writes its links (the file name is percent-encoded). No fragment, no backslash. */
private fun isPlainPath(rest: String): Boolean =
    rest.startsWith('/') && rest.all { it.code in 0x21..0x7E && it !in "\\#\"<>`{}|^" }

/** The percent-encoded file name: never a dot segment, and never an encoded dot, slash or backslash a client might decode into one. */
private fun isPlayFileName(name: String): Boolean {
    if (name.isEmpty() || name == "." || name == "..") return false
    val lower = name.lowercase()
    return "%2e" !in lower && "%2f" !in lower && "%5c" !in lower
}

/**
 * The play link's `/play/<token>/<file name>` on [serverUrl] (the app's own
 * server base, joined as every other call joins its path), or null when the
 * link is not exactly that shape. The link's own scheme, host and port are
 * checked but never used.
 */
fun magnetPlayLinkOn(url: String, serverUrl: String): String? {
    if (url.isEmpty() || url.length > MaxPlayUrl) return null
    val link = originOf(url) ?: return null
    if (!isPlainPath(link.rest) || '?' in link.rest) return null
    val at = link.rest.lastIndexOf("/play/")
    if (at < 0) return null
    val tail = link.rest.substring(at)
    val segments = tail.substring("/play/".length)
    val slash = segments.indexOf('/')
    if (slash < 0) return null
    val token = segments.substring(0, slash)
    val name = segments.substring(slash + 1)
    if (token.length > MaxToken || !MagnetToken.matches(token) || '/' in name || !isPlayFileName(name)) return null
    val server = originOf(serverUrl) ?: return null
    if ('?' in server.rest || '#' in server.rest) return null
    return serverUrl.trimEnd('/') + tail
}

private fun readFile(item: JsonElement, serverUrl: String): MagnetFile {
    val why = "The magnet answer lists a file of the wrong shape."
    val obj = item as? JsonObject ?: refuse(why)
    val id = obj.required("id", Kind.Text, why).text()
    if (!isPlainId(id, MaxFileId)) refuse(why)
    val name = displayText(obj.required("name", Kind.Text, why).text(), MaxName)
    if (name.isEmpty()) refuse(why)
    val playUrl = magnetPlayLinkOn(obj.required("playUrl", Kind.Text, why).text(), serverUrl)
        ?: refuse("The magnet answer has a play link of the wrong shape.")
    return MagnetFile(
        id = id,
        name = name,
        sizeBytes = obj.wholeSize("sizeBytes", why),
        season = obj.smallCount("season", MaxSeason, why),
        episode = obj.smallCount("episode", MaxEpisode, why),
        playUrl = playUrl,
    )
}

private fun readIdentity(root: JsonObject): MagnetIdentity? {
    val why = "The magnet answer names a title of the wrong shape."
    val obj = root.field("identified", Kind.Object, why) as? JsonObject ?: return null
    val type = obj.required("type", Kind.Text, why).text()
    if (type != "movie" && type != "series") refuse(why)
    val id = obj.required("id", Kind.Text, why).text()
    if (!ImdbTitleId.matches(id)) refuse(why)
    val name = displayText(obj.required("name", Kind.Text, why).text(), MaxTitleName)
    if (name.isEmpty()) refuse(why)
    val year = obj.field("year", Kind.Text, why)?.text()
    if (!year.isNullOrEmpty() && !FourDigits.matches(year)) refuse(why)
    // Artwork only from TMDB's own image host; another poster is left out rather than refused.
    val poster = obj.field("poster", Kind.Text, why)?.text()?.takeIf {
        it.length <= 300 && it.startsWith(TmdbImagePrefix) && ".." !in it && ImagePath.matches(it.substring(TmdbImagePrefix.length))
    }
    return MagnetIdentity(type, id, name, year?.ifEmpty { null }, poster)
}

/** One magnet answer, its play links rebuilt on [serverUrl]. Throws [MagnetAnswerException] when anything is out of shape. */
fun readMagnet(value: JsonElement, serverUrl: String): MagnetRequest {
    val root = value as? JsonObject ?: refuse("The magnet answer must be an object.")
    val why = "The magnet answer has a field of the wrong shape."
    val id = root.required("id", Kind.Text, why).text()
    if (!isMagnetId(id)) refuse(why)
    val stateText = root.required("state", Kind.Text, why).text()
    val state = MagnetState.entries.firstOrNull { it.wire == stateText } ?: refuse("The magnet answer has an unknown state.")
    val reasonWhy = "The magnet answer has a reason of the wrong shape."
    val reason = root.field("reason", Kind.Text, reasonWhy)?.text()
    if (reason != null && !(reason.isNotEmpty() && reason.length <= MaxReason && ReasonShape.matches(reason))) refuse(reasonWhy)
    val name = root.field("name", Kind.Text, why)?.text()
    val progress = root.field("progress", Kind.Number, why)?.number()
    if (progress != null && !(progress.isFinite() && progress >= 0 && progress <= 1)) refuse(why)
    val base = MagnetRequest(
        id = id,
        state = state,
        reason = reason,
        name = name?.let { displayText(it, MaxName) }.orEmpty(),
        sizeBytes = root.wholeSize("sizeBytes", why),
        progress = progress ?: 0.0,
        files = emptyList(),
        identified = readIdentity(root),
    )
    if (state != MagnetState.Ready) return base
    val files = root.required("files", Kind.List, "A ready magnet answer has no files.") as JsonArray
    if (files.isEmpty() || files.size > MaxFiles) refuse("A ready magnet answer has no files, or too many.")
    val read = mutableListOf<MagnetFile>()
    for (item in files) {
        val file = readFile(item, serverUrl)
        if (read.any { it.id == file.id }) refuse("The magnet answer lists one file twice.")
        read += file
    }
    return base.copy(files = read)
}

/** A magnet request id as the server mints them, checked before it goes into a path. */
fun isMagnetId(id: String): Boolean = id.startsWith("mg_") && isPlainId(id.substring(3), MaxId)
