import type { MetaPreview, Stream } from './types'

/**
 * Addon entries that are actions, not titles or videos, and never reach a row.
 *
 * Some addons put destructive actions into their catalogs as fake items.
 * MediaFusion's watchlist rows open with "🗑️💩 Delete all files", id
 * `dl<service>` (`dltorbox`), whose one stream points at
 * `/delete_all_watchlist` and deletes every file in the debrid account when
 * played. Halo is a player: no addon, now or later, gets to offer that as
 * something to watch. So both halves are dropped wherever addon data enters
 * Halo (here, for addons asked directly, and in the API client, for whatever a
 * server returns): the catalog entry, and any stream that would act, should a
 * future addon disguise the entry well enough to pass the first check.
 */

/** MediaFusion's action id shape: `dl` and letters, no colon. Real ids are IMDb `tt…` or `prefix:…`. */
const ACTION_ID = /^dl[a-z]+$/

/**
 * A name that reads as a bulk or destructive action rather than a title: the
 * wastebasket glyph, or a destroying verb aimed at what an account holds.
 * Whole words, so "Deleted Scenes" or "The Purge" stay.
 */
const ACTION_TEXT =
  /\u{1F5D1}|\b(delete|remove|clear|wipe|erase|purge)\b.{0,40}?\b(all|files?|everything|watchlist|library|downloads?|torrents?|cache)\b/iu

/** A link that performs an action instead of serving a file. */
const ACTION_URL = /(delete|remove|clear|wipe|erase|purge)[_-]?(all|watchlist|files|everything)/i

export function isActionEntry(meta: { id: string; name?: string }): boolean {
  return ACTION_ID.test(meta.id) || ACTION_TEXT.test(meta.name ?? '')
}

export function isActionStream(stream: Pick<Stream, 'url' | 'name' | 'title' | 'description'>): boolean {
  if (stream.url && ACTION_URL.test(stream.url)) return true
  return [stream.name, stream.title, stream.description].some((text) => text !== undefined && ACTION_TEXT.test(text))
}

export function withoutActionEntries<T extends Pick<MetaPreview, 'id' | 'name'>>(metas: readonly T[]): T[] {
  return metas.filter((meta) => !isActionEntry(meta))
}

export function withoutActionStreams<T extends Pick<Stream, 'url' | 'name' | 'title' | 'description'>>(
  streams: readonly T[],
): T[] {
  return streams.filter((stream) => !isActionStream(stream))
}
