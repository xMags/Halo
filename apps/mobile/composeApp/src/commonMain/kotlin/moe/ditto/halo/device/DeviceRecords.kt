package moe.ditto.halo.device

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import moe.ditto.halo.api.LibraryItem
import moe.ditto.halo.api.UserSettings
import moe.ditto.halo.api.WatchState

/*
 * Validation and last-write-wins merging for the synced records, mirroring the
 * API server's request schemas and upserts. A Kotlin port of
 * `packages/core/src/device/records.ts`; keep the two in step. What the phone
 * stores without an account must always be something the server would have
 * accepted: on sign-in the records move into the account, and a row the
 * server refuses would block that move.
 */

private const val MaxAddons = 50
private const val MaxWatchStateNameLength = 512
private val UrlScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** Why an addon URL list is refused, or null. The server's PUT /addons rules, http(s) only. */
internal fun addonUrlsProblem(urls: List<String>): String? {
    if (urls.size > MaxAddons) return "at most $MaxAddons addons can be installed"
    val seen = HashSet<String>()
    for (url in urls) {
        if (!isHttpUrl(url)) return "not an addon URL: $url"
        if (!seen.add(url)) return "duplicate transportUrl: $url"
    }
    return null
}

internal fun libraryItemProblem(item: LibraryItem): String? {
    if (item.id.isEmpty() || item.type.isEmpty() || item.name.isEmpty()) return "library item needs an id, type and name"
    if (item.poster != null && !isUrl(item.poster)) return "library item poster must be a URL"
    if (item.addedAt <= 0 || item.updatedAt <= 0) return "library item timestamps must be positive integers"
    if (item.removedAt != null && item.removedAt <= 0) return "library item removedAt must be a positive integer"
    return null
}

internal fun watchStateProblem(state: WatchState): String? {
    if (state.videoId.isEmpty() || state.itemId.isEmpty()) return "watch state needs a videoId and itemId"
    if (!nonNegativeFinite(state.positionSec) || !nonNegativeFinite(state.durationSec)) {
        return "watch state position and duration must be non-negative numbers"
    }
    if (state.name != null && (state.name.isEmpty() || state.name.length > MaxWatchStateNameLength)) {
        return "watch state name must be 1 to 512 characters"
    }
    if (state.poster != null && !isUrl(state.poster)) return "watch state poster must be a URL"
    if (state.removedAt != null && state.removedAt <= 0) return "watch state removedAt must be a positive integer"
    if (state.updatedAt <= 0) return "watch state updatedAt must be a positive integer"
    return null
}

/** Known settings are checked; unknown ones pass through, as on the server, so newer clients keep theirs. */
internal fun settingsProblem(value: UserSettings, updatedAt: Long): String? {
    if (updatedAt <= 0) return "settings updatedAt must be a positive integer"
    val raw = value.raw
    val checks: List<Pair<String, (JsonElement) -> Boolean>> = listOf(
        "preferredAudioLang" to { v -> v.string()?.let { it.length <= 8 } == true },
        "preferredSubtitleLang" to { v -> v.string()?.let { it.length <= 8 } == true },
        "videoFitMode" to { v -> v.string() in setOf("cover", "contain") },
        "subtitleScalePercent" to { v -> v.number()?.let { it == kotlin.math.floor(it) && it in 50.0..200.0 } == true },
        "subtitleFontFamily" to { v -> v.string()?.let { it.length in 1..64 } == true },
        "subtitleOutline" to { v -> v.string() in setOf("none", "thin", "normal", "thick") },
        "subtitleShadow" to { v -> v.boolean() != null },
        "playbackRate" to { v -> v.number()?.let { it.isFinite() && it in 0.25..4.0 } == true },
        "autoplayNextEpisode" to { v -> v.boolean() != null },
        "catalogLanguage" to { v -> v.string()?.let { it.length in 1..64 } == true },
    )
    val invalid = checks.firstOrNull { (key, valid) -> raw[key]?.let { !valid(it) } == true }
    return invalid?.let { "invalid setting: ${it.first}" }
}

/**
 * Applies incoming library rows over stored ones, keyed by id: a strictly
 * newer `updatedAt` wins and a tie keeps the stored row. A row's type is fixed
 * by its first write, as on the server. Removals are tombstones, never
 * deletions, so they survive stale re-adds.
 */
internal fun mergeLibrary(stored: List<LibraryItem>, incoming: List<LibraryItem>): List<LibraryItem> {
    val byId = LinkedHashMap<String, LibraryItem>()
    stored.forEach { byId[it.id] = it }
    for (item in incoming) {
        val existing = byId[item.id]
        if (existing == null) {
            byId[item.id] = item
        } else if (item.updatedAt > existing.updatedAt) {
            byId[item.id] = item.copy(type = existing.type)
        }
    }
    return byId.values.toList()
}

/** The rows a watch history read returns: everything but removal tombstones. */
internal fun liveWatchStates(states: List<WatchState>): List<WatchState> = states.filter { it.removedAt == null }

/** Same rule as [mergeLibrary], keyed by videoId; a winning row replaces the stored one whole. */
internal fun mergeWatchStates(stored: List<WatchState>, incoming: List<WatchState>): List<WatchState> {
    val byVideo = LinkedHashMap<String, WatchState>()
    stored.forEach { byVideo[it.videoId] = it }
    for (state in incoming) {
        val existing = byVideo[state.videoId]
        if (existing == null || state.updatedAt > existing.updatedAt) byVideo[state.videoId] = state
    }
    return byVideo.values.toList()
}

/** Finite as well: JSON cannot carry Infinity, so the server would refuse it on the way into an account. */
private fun nonNegativeFinite(value: Double): Boolean = value.isFinite() && value >= 0

/** Any absolute URL, as `new URL()` accepts one: a scheme and something after it. */
private fun isUrl(value: String): Boolean {
    val scheme = UrlScheme.find(value) ?: return false
    return value.length > scheme.range.last + 1
}

private fun JsonElement.number(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

private fun JsonElement.boolean(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
