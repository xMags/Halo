import { foldedText, type LibraryItem, type MetaPreview } from '@halo/core'

/*
 * What only this app has beside the shared search rules (`@halo/core`'s
 * `search/board.ts`): its library, matched against the query.
 */

/** The query's words; a title matches when its name holds every one of them. */
function queryWords(query: string): string[] {
  return foldedText(query).split(/\s+/).filter(Boolean)
}

/**
 * The library entries the query names, newest first, as posters. They are
 * what the viewer most likely means, and no addon has to answer for them.
 */
export function libraryMatches(library: readonly LibraryItem[] | undefined, query: string): MetaPreview[] {
  const words = queryWords(query)
  if (words.length === 0) return []
  return (library ?? [])
    .filter((item) => !item.removedAt)
    .filter((item) => {
      const name = foldedText(item.name)
      return words.every((word) => name.includes(word))
    })
    .sort((a, b) => b.addedAt - a.addedAt)
    .map((item) => ({
      id: item.id.slice(item.type.length + 1),
      type: item.type,
      name: item.name,
      ...(item.poster ? { poster: item.poster } : {}),
    }))
}
