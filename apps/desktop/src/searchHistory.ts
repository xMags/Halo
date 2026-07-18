const KEY = 'halo.searchHistory.v1'
const LIMIT = 20

/**
 * Device-local search history, most recent first. Deliberately not synced
 * (mobile parity): whole-blob LWW (the settings mechanism) would clobber
 * merges from two devices, and per-term sync isn't worth a table.
 */
export function getSearchHistory(): string[] {
  try {
    const raw = localStorage.getItem(KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : []
  } catch {
    return []
  }
}

function write(terms: string[]): string[] {
  try {
    localStorage.setItem(KEY, JSON.stringify(terms))
  } catch {
    // Quota/serialization failure — history is best-effort.
  }
  return terms
}

/** Moves (or inserts) the term to the front; case-insensitive dedupe keeps the newest casing. */
export function addSearchTerm(term: string): string[] {
  const trimmed = term.trim()
  if (trimmed.length < 2) return getSearchHistory()
  const rest = getSearchHistory().filter((t) => t.toLowerCase() !== trimmed.toLowerCase())
  return write([trimmed, ...rest].slice(0, LIMIT))
}

export function removeSearchTerm(term: string): string[] {
  return write(getSearchHistory().filter((t) => t !== term))
}

export function clearSearchHistory(): string[] {
  return write([])
}
