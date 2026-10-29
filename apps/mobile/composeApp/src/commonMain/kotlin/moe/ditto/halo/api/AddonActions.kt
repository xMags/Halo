package moe.ditto.halo.api

/*
 * Addon entries that are actions, not titles or videos, and never reach a row.
 *
 * Some addons put destructive actions into their catalogs as fake items.
 * MediaFusion's watchlist rows open with "🗑️💩 Delete all files", id
 * `dl<service>` (`dltorbox`), whose one stream points at `/delete_all_watchlist`
 * and deletes every file in the debrid account when played. Halo is a player:
 * no addon, now or later, gets to offer that as something to watch. So both
 * halves are dropped wherever addon data enters the app (the on-device
 * resolver, for addons asked directly, and HaloClient, for whatever a server
 * returns): the catalog entry, and any stream that would act, should a future
 * addon disguise the entry well enough to pass the first check. The desktop
 * applies the same rules in @halo/core's `addon/actionEntries.ts`.
 */

/** MediaFusion's action id shape: `dl` and letters, no colon. Real ids are IMDb `tt…` or `prefix:…`. */
private val ActionId = Regex("^dl[a-z]+$")

/**
 * A name that reads as a bulk or destructive action rather than a title: the
 * wastebasket glyph, or a destroying verb aimed at what an account holds.
 * Whole words, so "Deleted Scenes" or "The Purge" stay.
 */
private val ActionText = Regex(
    "🗑|\\b(delete|remove|clear|wipe|erase|purge)\\b.{0,40}?\\b(all|files?|everything|watchlist|library|downloads?|torrents?|cache)\\b",
    RegexOption.IGNORE_CASE,
)

/** A link that performs an action instead of serving a file. */
private val ActionUrl = Regex("(delete|remove|clear|wipe|erase|purge)[_-]?(all|watchlist|files|everything)", RegexOption.IGNORE_CASE)

internal fun isActionEntry(id: String, name: String): Boolean =
    ActionId.matches(id) || ActionText.containsMatchIn(name)

internal fun isActionStream(stream: Stream): Boolean =
    stream.url?.let { ActionUrl.containsMatchIn(it) } == true ||
        listOfNotNull(stream.name, stream.title, stream.description).any { ActionText.containsMatchIn(it) }

internal fun List<MetaPreview>.withoutActionEntries(): List<MetaPreview> =
    filterNot { isActionEntry(it.id, it.name) }

internal fun List<Stream>.withoutActionStreams(): List<Stream> = filterNot(::isActionStream)

/** Keeps the count of what arrived, because an addon's `skip` counts the entries dropped here too. */
internal fun CatalogResponse.withoutActions(): CatalogResponse =
    copy(metas = metas.withoutActionEntries(), received = receivedCount)

internal fun StreamsResult.withoutActions(): StreamsResult = copy(
    results = results
        .map { group -> group.copy(streams = group.streams.withoutActionStreams()) }
        .filter { it.streams.isNotEmpty() },
)

internal fun NextEpisodeResult.withoutActions(): NextEpisodeResult =
    if (stream != null && isActionStream(stream)) copy(stream = null) else this
