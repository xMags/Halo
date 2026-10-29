package moe.ditto.halo.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import moe.ditto.halo.api.AddonError
import moe.ditto.halo.api.ProxyHeaders
import moe.ditto.halo.api.Stream
import moe.ditto.halo.api.StreamBehaviorHints
import moe.ditto.halo.api.Subtitle

/*
 * Addon responses are untrusted third-party JSON. Everything a resolver hands
 * back passes through here first: only fields Halo uses survive, each bounded
 * in length, and only http(s) URLs. A Kotlin port of
 * `packages/core/src/addon/normalize.ts`, the rules the API server applies for
 * signed-in users; keep the two in step.
 */

private const val MaxResults = 500
private const val MaxUrlLength = 16_384
private const val MaxLabelLength = 4_096
private const val MaxFilenameLength = 1_024
internal const val MaxBingeGroupLength = 512
private const val MaxHashLength = 128
private const val MaxSubtitleIdLength = 512
private const val MaxLanguageLength = 64
private const val MaxHeaders = 64
private const val MaxHeaderNameLength = 128
private const val MaxHeaderValueLength = 4_096
private const val MaxAddonNameLength = 80

/** JavaScript's Number.MAX_SAFE_INTEGER, the largest size the server accepts. */
private const val MaxSafeInteger = 9_007_199_254_740_991.0

private val ControlCharacters = Regex("[\\u0000-\\u001f\\u007f]")
private val Whitespace = Regex("\\s+")
private val UnsafeNamePattern = Regex("https?://|[/\\\\]|[A-Za-z0-9_-]{32,}", RegexOption.IGNORE_CASE)
private val HeaderBreak = Regex("[\\r\\n\\u0000]")

/** An addon's name as it may be shown: no control characters, URLs or key-like runs. */
internal fun safeAddonName(value: String?): String {
    if (value == null) return "Addon"
    val cleaned = value.replace(ControlCharacters, " ").replace(Whitespace, " ").trim()
    if (UnsafeNamePattern.containsMatchIn(cleaned)) return "Addon"
    return cleaned.take(MaxAddonNameLength).ifEmpty { "Addon" }
}

/** The per-addon failure the fan-out endpoints report, never carrying the cause's text. */
internal fun addonError(id: String, name: String?, reason: Throwable): AddonError {
    val safeName = safeAddonName(name)
    return when (reason) {
        is AddonTimeoutException -> AddonError(id, "Addon timed out.", safeName, "timeout")
        is AddonRequestException -> {
            val status = reason.status.takeIf { it in 100..599 }
            AddonError(
                id = id,
                message = if (status == null) "Addon returned an HTTP error." else "Addon returned HTTP $status.",
                name = safeName,
                code = "upstream_http",
                status = status,
            )
        }
        is InvalidAddonResponseException -> AddonError(id, "Addon returned an invalid response.", safeName, "invalid_response")
        else -> AddonError(id, "Addon is unavailable.", safeName, "unavailable")
    }
}

internal fun normalizeStreamsResponse(value: JsonElement): List<Stream> {
    val streams = (value as? JsonObject)?.get("streams") as? JsonArray ?: throw InvalidAddonResponseException()
    return streams.take(MaxResults).mapNotNull(::normalizeStream)
}

internal fun normalizeSubtitlesResponse(value: JsonElement): List<Subtitle> {
    val subtitles = (value as? JsonObject)?.get("subtitles") as? JsonArray ?: throw InvalidAddonResponseException()
    return normalizeSubtitles(subtitles)
}

private fun normalizeStream(value: JsonElement): Stream? {
    if (value !is JsonObject) return null
    val url = validHttpUrl(value["url"]) ?: return null
    // Torrents are never played, even when a URL rides along.
    if (value["infoHash"].string() != null) return null
    return Stream(
        url = url,
        name = boundedString(value["name"], MaxLabelLength),
        title = boundedString(value["title"], MaxLabelLength),
        description = boundedString(value["description"], MaxLabelLength),
        subtitles = (value["subtitles"] as? JsonArray)?.let(::normalizeSubtitles).orEmpty(),
        behaviorHints = normalizeBehaviorHints(value["behaviorHints"]),
    )
}

private fun normalizeBehaviorHints(value: JsonElement?): StreamBehaviorHints? {
    if (value !is JsonObject) return null
    val notWebReady = (value["notWebReady"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    val bingeGroup = boundedString(value["bingeGroup"], MaxBingeGroupLength)
    val proxyHeaders = normalizeProxyHeaders(value["proxyHeaders"])
    val filename = boundedString(value["filename"], MaxFilenameLength)
    val videoSize = positiveSafeInteger(value["videoSize"])
    val videoHash = boundedString(value["videoHash"], MaxHashLength)
    if (notWebReady == null && bingeGroup == null && proxyHeaders == null && filename == null &&
        videoSize == null && videoHash == null
    ) {
        return null
    }
    return StreamBehaviorHints(
        notWebReady = notWebReady ?: false,
        bingeGroup = bingeGroup,
        proxyHeaders = proxyHeaders,
        filename = filename,
        videoSize = videoSize,
        videoHash = videoHash,
    )
}

private fun normalizeProxyHeaders(value: JsonElement?): ProxyHeaders? {
    if (value !is JsonObject) return null
    val request = normalizeHeaderMap(value["request"])
    val response = normalizeHeaderMap(value["response"])
    if (request == null && response == null) return null
    return ProxyHeaders(request = request.orEmpty(), response = response.orEmpty())
}

private fun normalizeHeaderMap(value: JsonElement?): Map<String, String>? {
    if (value !is JsonObject) return null
    val result = LinkedHashMap<String, String>()
    for ((name, element) in value.entries.take(MaxHeaders)) {
        if (name.isEmpty() || name.length > MaxHeaderNameLength || HeaderBreak.containsMatchIn(name)) continue
        val headerValue = element.string() ?: continue
        if (headerValue.length > MaxHeaderValueLength || HeaderBreak.containsMatchIn(headerValue)) continue
        result[name] = headerValue
    }
    return result.ifEmpty { null }
}

private fun normalizeSubtitles(values: JsonArray): List<Subtitle> = values.take(MaxResults).mapNotNull { value ->
    if (value !is JsonObject) return@mapNotNull null
    val id = requiredString(value["id"], MaxSubtitleIdLength)
    val url = validHttpUrl(value["url"])
    val lang = requiredString(value["lang"], MaxLanguageLength)
    if (id != null && url != null && lang != null) Subtitle(id = id, url = url, lang = lang) else null
}

/** A JSON string's content; numbers, booleans and null are not strings. */
internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun boundedString(value: JsonElement?, maxLength: Int): String? =
    value.string()?.takeIf { it.length <= maxLength }

private fun requiredString(value: JsonElement?, maxLength: Int): String? =
    value.string()?.takeIf { it.isNotEmpty() && it.length <= maxLength }

private fun validHttpUrl(value: JsonElement?): String? {
    val text = value.string() ?: return null
    if (text.isEmpty() || text.length > MaxUrlLength) return null
    return text.takeIf(::isHttpUrl)
}

private fun positiveSafeInteger(value: JsonElement?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.doubleOrNull ?: return null
    if (number <= 0 || number > MaxSafeInteger || number != kotlin.math.floor(number)) return null
    return number.toLong()
}

private val HttpUrlStart = Regex("^https?://", RegexOption.IGNORE_CASE)

/**
 * An absolute http(s) URL with a host, the check `new URL()` makes in the
 * shared rules. The authority runs to the first `/`, `?` or `#`; credentials
 * and a port are allowed, but the host must be there and hold no whitespace.
 */
internal fun isHttpUrl(text: String): Boolean {
    val start = HttpUrlStart.find(text) ?: return false
    val rest = text.substring(start.range.last + 1)
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostAndPort = authority.substringAfterLast('@')
    val host = if (hostAndPort.startsWith("[")) {
        hostAndPort.substringBefore(']').removePrefix("[")
    } else {
        hostAndPort.substringBeforeLast(':')
    }
    return host.isNotEmpty() && host.none(Char::isWhitespace)
}
