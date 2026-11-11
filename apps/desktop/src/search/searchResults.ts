import { foldedText, type LibraryItem, type MetaPreview } from '@halo/core'
import type { SearchHistoryEntry } from './searchHistory'

/*
 * The rules that turn several catalogs' answers into one list live in
 * `@halo/core` (`search/results.ts`), shared with every other client. What is
 * here is what only this app has: its library and its search history.
 */
export {
  byRelevance,
  hasNoPoster,
  interleave,
  metaKey,
  nameRelevance,
  refineResults,
  searchProgress,
  titleIdentities,
  uniqueMetas,
  type SearchProgress,
  type SearchResultGroup,
  type SearchTarget,
  type TargetState,
} from '@halo/core'

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

/**
 * Past searches that contain what is being typed, most recent first, for
 * picking one instead of typing it out. The term itself is not suggested back.
 */
export function suggestedSearches(history: readonly SearchHistoryEntry[], typed: string, limit = 5): string[] {
  const needle = foldedText(typed.trim())
  if (!needle) return []
  return history
    .map((entry) => entry.term)
    .filter((term) => {
      const candidate = foldedText(term)
      return candidate !== needle && candidate.includes(needle)
    })
    .slice(0, limit)
}
