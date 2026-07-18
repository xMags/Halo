import AsyncStorage from '@react-native-async-storage/async-storage'

const KEY = 'halo.searchHistory.v1'
const LIMIT = 20

/**
 * Device-local search history, most recent first. Deliberately not synced:
 * whole-blob LWW (the settings mechanism) would clobber merges from two
 * devices, and per-term sync isn't worth a table.
 */
export async function getSearchHistory(): Promise<string[]> {
  try {
    const raw = await AsyncStorage.getItem(KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : []
  } catch {
    return []
  }
}

async function write(terms: string[]): Promise<string[]> {
  await AsyncStorage.setItem(KEY, JSON.stringify(terms)).catch(() => undefined)
  return terms
}

/** Moves (or inserts) the term to the front; case-insensitive dedupe keeps the newest casing. */
export async function addSearchTerm(term: string): Promise<string[]> {
  const trimmed = term.trim()
  if (trimmed.length < 2) return getSearchHistory()
  const rest = (await getSearchHistory()).filter((t) => t.toLowerCase() !== trimmed.toLowerCase())
  return write([trimmed, ...rest].slice(0, LIMIT))
}

export async function removeSearchTerm(term: string): Promise<string[]> {
  return write((await getSearchHistory()).filter((t) => t !== term))
}

export async function clearSearchHistory(): Promise<string[]> {
  return write([])
}
