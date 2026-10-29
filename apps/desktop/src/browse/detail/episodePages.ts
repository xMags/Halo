import type { MetaVideo } from '@halo/core'

/** Above this many episodes a season is shown a page at a time. */
export const EPISODE_PAGE_SIZE = 25

export interface EpisodePage {
  /** "Episodes 1–50", by the episodes' own numbers where they have them. */
  label: string
  episodes: MetaVideo[]
}

/**
 * A season's episodes in pages of [size], in order. A season of up to [size]
 * episodes is one page, which the panel shows without a picker: only very
 * long seasons (daily soaps, long-running anime with every episode in one
 * season) need paging, and without it everything under the list (cast,
 * details, similar titles) sits below thousands of rows.
 */
export function episodePages(episodes: readonly MetaVideo[], size = EPISODE_PAGE_SIZE): EpisodePage[] {
  const pages: EpisodePage[] = []
  for (let start = 0; start < episodes.length; start += size) {
    const chunk = episodes.slice(start, start + size)
    const first = chunk[0]?.episode ?? start + 1
    const last = chunk[chunk.length - 1]?.episode ?? start + chunk.length
    pages.push({ label: first === last ? `Episode ${first}` : `Episodes ${first}–${last}`, episodes: chunk })
  }
  return pages
}

/** The page holding [videoId], or the first page when no page does. */
export function pageContaining(pages: readonly EpisodePage[], videoId: string | null): number {
  if (!videoId) return 0
  const index = pages.findIndex((page) => page.episodes.some((video) => video.id === videoId))
  return index < 0 ? 0 : index
}
