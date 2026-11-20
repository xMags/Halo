import type { TmdbClient, TmdbPersonSummary } from './tmdb'

/**
 * A misspelled name: TMDB's people search forgives no typo, so a name it
 * finds nobody for is searched again one word at a time, and only the people
 * whose whole name is nearly what was typed are kept. "crystophar nolan"
 * finds Christopher Nolan through "nolan"; Jonathan Nolan is too far off. At
 * least one word has to be spelled right (or begin the real word) for the
 * person to turn up at all.
 */

/** A word shorter than this finds too many people to be worth a search. */
const MIN_WORD_LENGTH = 3
/** At most this many words are searched, longest first. */
const MAX_WORDS_SEARCHED = 3
const MAX_CLOSEST_PEOPLE = 3

export interface ClosestPeople {
  /** Nearest first, then most popular. Never empty. */
  people: TmdbPersonSummary[]
  /** The nearest person's name, as TMDB writes it. */
  corrected: string
}

/** A name as compared: lower case, accents off, anything but letters and digits a space. */
export function nameKey(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim()
}

/** How many letters may differ for a name of this length to still be the one meant. */
export function allowedDistance(length: number): number {
  return Math.max(1, Math.floor(length / 4))
}

/**
 * The edit distance between two texts (a letter added, dropped or changed
 * counts one), or `limit + 1` as soon as it is known to be over `limit`.
 */
export function editDistance(a: string, b: string, limit: number): number {
  if (Math.abs(a.length - b.length) > limit) return limit + 1
  let previous = Array.from({ length: b.length + 1 }, (_, index) => index)
  for (let i = 1; i <= a.length; i++) {
    const current = [i]
    let best = i
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      const value = Math.min(previous[j]! + 1, current[j - 1]! + 1, previous[j - 1]! + cost)
      current.push(value)
      if (value < best) best = value
    }
    if (best > limit) return limit + 1
    previous = current
  }
  return previous[b.length]!
}

/** The words a misspelled name is searched by: none for a single word, else those of three letters or more, longest first, at most three. */
export function closestPeopleWords(query: string): string[] {
  const words = [...new Set(nameKey(query).split(' '))]
  if (words.length < 2) return []
  return words
    .filter((word) => word.length >= MIN_WORD_LENGTH)
    .sort((a, b) => b.length - a.length)
    .slice(0, MAX_WORDS_SEARCHED)
}

/**
 * The people a misspelled `query` most likely means, or null when nobody is
 * near enough (or the query is one word, which leaves nothing to search by).
 * Throws only when every word's search failed upstream.
 */
export async function closestPeople(client: Pick<TmdbClient, 'searchPeople'>, query: string): Promise<ClosestPeople | null> {
  const typed = nameKey(query)
  const searched = closestPeopleWords(query)
  if (searched.length === 0) return null

  const answers = await Promise.allSettled(searched.map((word) => client.searchPeople(word)))
  const failed = answers.find((answer) => answer.status === 'rejected')
  if (failed && answers.every((answer) => answer.status === 'rejected')) throw failed.reason

  const limit = allowedDistance(typed.length)
  const near = new Map<number, { person: TmdbPersonSummary; distance: number }>()
  for (const answer of answers) {
    if (answer.status !== 'fulfilled') continue
    for (const person of answer.value) {
      if (near.has(person.tmdbId)) continue
      const distance = editDistance(typed, nameKey(person.name), limit)
      if (distance <= limit) near.set(person.tmdbId, { person, distance })
    }
  }
  const ranked = [...near.values()].sort((a, b) => a.distance - b.distance || b.person.popularity - a.person.popularity)
  const best = ranked[0]
  if (!best) return null
  return { people: ranked.slice(0, MAX_CLOSEST_PEOPLE).map((entry) => entry.person), corrected: best.person.name }
}
