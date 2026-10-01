package moe.ditto.halo.cache

import kotlin.time.Duration.Companion.minutes

/**
 * Every cached resource, with its staleness window attached.
 *
 * Keeping the policy on the key rather than at each call site means the
 * caching behaviour of the whole app is one readable list, and two screens
 * reading the same resource cannot disagree about how long it stays fresh.
 */
sealed class HaloKey(override val id: String, val staleMs: Long) : QueryKey {

    /** Identity changes only by signing out, which discards the cache anyway. */
    data object Me : HaloKey("me", Forever)

    /** Both the raw split and the effective order derive from this one entry. */
    data object Addons : HaloKey("addons", 5.minutes.inWholeMilliseconds)

    data class Catalog(
        val addonId: String,
        val type: String,
        val catalogId: String,
    ) : HaloKey("catalog/$addonId/$type/$catalogId", 10.minutes.inWholeMilliseconds)

    data class Meta(val type: String, val metaId: String) :
        HaloKey("meta/$type/$metaId", 10.minutes.inWholeMilliseconds)

    /**
     * Never reused across sessions: debrid results embed short-lived links, so
     * a cached list is worse than no list once its URLs expire.
     */
    data class Streams(val type: String, val videoId: String) :
        HaloKey("streams/$type/$videoId", Always)

    /**
     * Keyed by the source as well as the video, because results depend on the
     * file being matched — a downloaded copy hashes differently from the
     * stream it came from and deserves its own entry.
     */
    data class Subtitles(val type: String, val videoId: String, val source: String?) :
        HaloKey("subtitles/$type/$videoId/${source ?: "none"}", Forever)

    data class Search(val term: String) : HaloKey("search/$term", 1.minutes.inWholeMilliseconds)

    /** Synced collections are read fresh: another device may have changed them. */
    data object Library : HaloKey("library", Always)

    data object WatchStates : HaloKey("watch-state", Always)

    data object Settings : HaloKey("settings", 1.minutes.inWholeMilliseconds)

    companion object {
        /** Refetch whenever a screen asks, while still serving the cached value first. */
        const val Always = 0L

        /** Fetch once per session; nothing invalidates it but an explicit write. */
        const val Forever = Long.MAX_VALUE
    }
}
