package moe.ditto.halo.player

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/*
 * Request headers an addon says its stream needs (`behaviorHints.proxyHeaders
 * .request`: a Referer, an auth header), in the shapes playback uses them.
 *
 * They come from an addon, possibly through a server this app does not
 * control, so they are checked here again before they reach the engine or a
 * request: a name must be a plain token, a value must not break out of its
 * line, and headers that belong to the connection or to the player (which
 * sends its own Range for every seek) are never taken from an addon. The
 * desktop applies the same rules in `player/requestHeaders.ts`.
 */

private const val MaxHeaders = 64
private const val MaxNameLength = 128
private const val MaxValueLength = 4096
private val HeaderName = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")
private val UnsafeInValue = Regex("[\\r\\n\\u0000]")

/** Lowercase names the player or the connection owns. */
private val NotFromAddons = setOf("host", "range", "content-length", "connection", "transfer-encoding", "accept-encoding")

private val HeaderMap = MapSerializer(String.serializer(), String.serializer())

/** The usable headers; empty when there are none. */
internal fun playbackHeaders(raw: Map<String, String>?): Map<String, String> = raw.orEmpty().entries
    .take(MaxHeaders)
    .filter { (name, value) ->
        name.length <= MaxNameLength && HeaderName.matches(name) && name.lowercase() !in NotFromAddons &&
            value.length <= MaxValueLength && !UnsafeInValue.containsMatchIn(value)
    }
    .associate { it.key to it.value }

/** One `Name: value` line per header, as mpv's `http-header-fields` takes them. */
internal fun headerLines(headers: Map<String, String>): List<String> =
    playbackHeaders(headers).map { (name, value) -> "$name: $value" }

/** The headers as one string, for a navigation route, which carries only primitives. Null when there are none. */
internal fun encodeRequestHeaders(raw: Map<String, String>?): String? =
    playbackHeaders(raw).takeIf { it.isNotEmpty() }?.let { Json.encodeToString(HeaderMap, it) }

/** The reverse of [encodeRequestHeaders]. Anything unreadable is no headers, never a crash on the way into playback. */
internal fun decodeRequestHeaders(encoded: String?): Map<String, String> {
    if (encoded.isNullOrEmpty()) return emptyMap()
    return try {
        playbackHeaders(Json.decodeFromString(HeaderMap, encoded))
    } catch (_: SerializationException) {
        emptyMap()
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }
}
