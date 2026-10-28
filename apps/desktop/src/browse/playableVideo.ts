import type { MetaPreview } from '@halo/core'

/**
 * The video id a film's sources, progress and download are looked up by: the
 * addon's `defaultVideoId` when it names one, else the title's own id. The
 * TMDB addon lists films under its own `tmdb:` ids, which stream addons do not
 * know, and names the IMDb id to play instead, as Stremio honours.
 */
export function movieVideoId(meta: Pick<MetaPreview, 'id' | 'behaviorHints'>): string {
  const hinted = meta.behaviorHints?.defaultVideoId
  return typeof hinted === 'string' && hinted.trim() !== '' ? hinted.trim() : meta.id
}

/**
 * Whether a film can be played from a catalog card as it is: its id is
 * already the one stream addons look up (IMDb), or the card names the video.
 * Otherwise the title page, which loads the full details, is the way in.
 */
export function playableFromPreview(meta: Pick<MetaPreview, 'id' | 'behaviorHints'>): boolean {
  return meta.id.startsWith('tt') || movieVideoId(meta) !== meta.id
}
