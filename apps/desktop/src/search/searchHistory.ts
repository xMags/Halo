const KEY = 'halo.searchHistory.v1'
const LIMIT = 20

export interface SearchHistoryEntry {
  term: string
  /** When the term was last searched — the "4 h" column beside it. */
  at: number
}

/**
 * Device-local search history, most recent first. Deliberately not synced
 * (mobile parity): whole-blob LWW (the settings mechanism) would clobber
 * merges from two devices, and per-term sync isn't worth a table.
 *
 * The v1 key originally held a bare `string[]`. Those rows are still read and
 * carried forward with an unknown (zero) timestamp rather than dropped — the
 * key stays v1 because the terms themselves never changed meaning.
 */
export function getSearchHistory(): SearchHistoryEntry[] {
  try {
    const raw = localStorage.getItem(KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    if (!Array.isArray(parsed)) return []
    return parsed.flatMap((row): SearchHistoryEntry[] => {
      if (typeof row === 'string') return [{ term: row, at: 0 }]
      if (row && typeof row === 'object' && typeof (row as SearchHistoryEntry).term === 'string') {
        const entry = row as SearchHistoryEntry
        return [{ term: entry.term, at: typeof entry.at === 'number' ? entry.at : 0 }]
      }
      return []
    })
  } catch {
    return []
  }
}

function write(entries: SearchHistoryEntry[]): SearchHistoryEntry[] {
  try {
    localStorage.setItem(KEY, JSON.stringify(entries))
  } catch {
    // Quota/serialization failure — history is best-effort.
  }
  return entries
}

/** Moves (or inserts) the term at the front; case-insensitive dedupe keeps the newest casing. */
export function addSearchTerm(term: string): SearchHistoryEntry[] {
  const trimmed = term.trim()
  if (trimmed.length < 2) return getSearchHistory()
  const rest = getSearchHistory().filter((e) => e.term.toLowerCase() !== trimmed.toLowerCase())
  return write([{ term: trimmed, at: Date.now() }, ...rest].slice(0, LIMIT))
}

export function removeSearchTerm(term: string): SearchHistoryEntry[] {
  return write(getSearchHistory().filter((e) => e.term !== term))
}

export function clearSearchHistory(): SearchHistoryEntry[] {
  return write([])
}
