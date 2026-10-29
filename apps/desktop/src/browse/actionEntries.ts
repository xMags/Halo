/**
 * Catalog entries that are buttons, not titles.
 *
 * Some addons put actions into their catalogs as fake items. MediaFusion's
 * watchlist rows open with "🗑️💩 Delete all files", id `dl<service>`
 * (`dltorbox`), whose one "stream" deletes every file in the debrid account.
 * A title row is no place for that, so such entries are dropped as catalogs
 * arrive, before any row, grid or search result can draw one.
 *
 * Two signs, either enough: MediaFusion's action id shape (`dl` and letters,
 * with no colon; real ids are IMDb `tt…` or `prefix:…`), and a name that
 * reads as a bulk action rather than a title.
 */
const ACTION_ID = /^dl[a-z]+$/
const ACTION_NAME = /\b(delete|remove|clear)\s+all\b/i

export function isActionEntry(meta: { id: string; name?: string }): boolean {
  return ACTION_ID.test(meta.id) || ACTION_NAME.test(meta.name ?? '')
}

export function withoutActionEntries<T extends { id: string; name?: string }>(metas: readonly T[]): T[] {
  return metas.filter((meta) => !isActionEntry(meta))
}
