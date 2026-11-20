import type { MetaPreview } from '../addon/types'
import {
  foldedText,
  hasNoPoster,
  isImdbId,
  isProductCodeName,
  mergeCopies,
  fillMissingDetails,
  nameRelevance,
  releaseYear,
  titleIdentities,
  withYearOutOfName,
} from './results'

/*
 * How one query's answers become the results a search shows, and how they
 * change afterwards. The catalogs' answers are ranked once, when the results
 * are revealed; from then on a late answer only fills in the details of a
 * title already shown or appends titles after the last one, judged by the
 * thresholds the reveal was judged by. Nothing shown moves or goes until the
 * next query. Shared by every client; the Kotlin app carries the same rules
 * and both are held to the vectors in `test/vectors/`.
 */

/** Short enough to feel live while typing, long enough not to ask every addon per keystroke. */
export const SEARCH_DEBOUNCE_MS = 250
export const SEARCH_MINIMUM_QUERY = 2
/** No title is longer, and the server caps a catalog's search text well above it. */
export const SEARCH_MAXIMUM_QUERY = 120
/** At most this many recent searches, as many as fit on one line. */
export const SEARCH_RECENT_SHOWN = 6
export const SEARCH_SUGGESTION_LIMIT = 5
/** No later than this after asking, once any catalog has answered with titles. */
export const SEARCH_REVEAL_DEADLINE_MS = 1200
/** A person's films are waited for this long after asking, so they rank among the results. */
export const SEARCH_CREDITS_DEADLINE_MS = 2000
/** The first titles' poster links are checked before the reveal: this many, for no longer than the deadline. */
export const SEARCH_POSTER_CHECK_LIMIT = 24
export const SEARCH_POSTER_CHECK_DEADLINE_MS = 2000

/**
 * One catalog entry as the search reads it. `posterMissing` says the poster
 * link was tried and its host has no such image; `imdb_id` is what the TMDB
 * addon sends beside its own id.
 */
export interface SearchTitle extends MetaPreview {
  imdb_id?: string
  posterMissing?: boolean
}

/** One searchable catalog: an addon's film or series catalog that takes a `search` extra and needs nothing else. */
export interface BoardTarget {
  key: string
  addonId: string
  addonName: string
  type: string
  id: string
  title: string
}

/** One catalog's answer, with where its addon stands in the effective order. */
export interface SearchAnswer {
  target: BoardTarget
  titles: readonly SearchTitle[]
  addonRank: number
}

/** What was typed, trimmed, with its name and year read apart: "dune 2021" is "dune" from 2021. */
export interface SearchQueryParts {
  full: string
  name: string
  year: number | null
}

/** The first film was shot in 1888; a year beyond the next two is part of a name. */
const EARLIEST_YEAR = 1888
const YEARS_AHEAD = 2

/**
 * A trailing four-digit number from 1888 to `currentYear` + 2 is the year,
 * and the name is the query without it. It needs a word before it, so a query
 * that is only a year ("1917") keeps it as the name.
 */
export function parseSearchQuery(query: string, currentYear = new Date().getFullYear()): SearchQueryParts {
  const full = query.trim()
  const parts: SearchQueryParts = { full, name: full, year: null }
  const words = full.split(/\s+/).filter(Boolean)
  if (words.length < 2) return parts
  const last = words[words.length - 1]!
  if (!/^[0-9]{4}$/.test(last)) return parts
  const year = Number(last)
  if (year < EARLIEST_YEAR || year > currentYear + YEARS_AHEAD) return parts
  const name = full.slice(0, full.length - last.length).trim()
  if (!name) return parts
  return { full, name, year }
}

/** Trimmed, at most `SEARCH_MAXIMUM_QUERY` UTF-16 units, never splitting a surrogate pair. */
export function cappedQuery(text: string): string {
  if (text.length <= SEARCH_MAXIMUM_QUERY) return text
  let length = SEARCH_MAXIMUM_QUERY
  const last = text.charCodeAt(length - 1)
  if (last >= 0xd800 && last <= 0xdbff) length -= 1
  return text.slice(0, length)
}

/** Whether a query, trimmed, is long enough to search. */
export function isSearchableQuery(query: string): boolean {
  return query.trim().length >= SEARCH_MINIMUM_QUERY
}

/** Whether a catalog of this kind is searched: films and series, never TV channels or debrid file lists. */
export function isSearchedCatalogType(type: string): boolean {
  return type === 'movie' || type === 'series'
}

/** A catalog, as far as searching it goes. */
export interface SearchableCatalog {
  type: string
  id: string
  name?: string
  extra?: ReadonlyArray<{ name: string; isRequired?: boolean }>
  extraSupported?: readonly string[]
  extraRequired?: readonly string[]
}

/**
 * The catalogs a search asks, in addon order: films and series that take a
 * `search` extra. Only the query is sent, so a catalog that also insists on
 * another extra (a genre, say) cannot answer and is left out rather than
 * reported as an addon that failed.
 */
export function searchTargets(
  addons: ReadonlyArray<{ id: string; manifest: { name: string; catalogs: readonly SearchableCatalog[] } }>,
): BoardTarget[] {
  return addons.flatMap((addon) =>
    addon.manifest.catalogs
      .filter((c) => isSearchedCatalogType(c.type))
      .filter((c) => (c.extra ?? []).some((e) => e.name === 'search') || (c.extraSupported ?? []).includes('search'))
      .filter(
        (c) =>
          !(c.extra ?? []).some((e) => e.isRequired && e.name !== 'search') &&
          !(c.extraRequired ?? []).some((name) => name !== 'search'),
      )
      .map((c) => ({
        key: `${addon.id}/${c.type}/${c.id}`,
        addonId: addon.id,
        addonName: addon.manifest.name,
        type: c.type,
        id: c.id,
        title: c.name ?? addon.manifest.name,
      })),
  )
}

/** One title of the merged answers and what it is ranked by. */
export interface RankedTitle {
  title: SearchTitle
  /** The better of its name's relevance to the full query and to the name alone; lower is better. */
  tier: number
  /** The query has a year and the title's release line has the same one. */
  yearMatches: boolean
  /** A release year and a poster that can be drawn. */
  complete: boolean
  /** How many catalogs listed it. */
  copies: number
  /** The best index it had in any catalog's own list. */
  bestPosition: number
  /** The best rank of any addon that listed it. */
  addonRank: number
  /** Every identity any of its copies is known by. */
  identities: string[]
}

/** The thresholds the revealed list was judged by, kept so later answers are judged by the same. */
export interface RevealCutoffs {
  /** Some title shares a word with the query, so those sharing none go. */
  dropUnrelated: boolean
  /** Enough titles hold every word, so those holding only some go. */
  dropSomeWords: boolean
  /** Enough complete titles, so those without a poster go. */
  dropArtless: boolean
  /** The query is a person's name: their complete titles stay although their names share no word with it. */
  keepCredited: boolean
}

/** What the words typed stand for: a title's name (or nothing known), or exactly some person's name. */
export type SearchQueryMeaning = 'title' | 'person'

const EXACT_NAME_TIER = 0
const SOME_WORDS_TIER = 5
const UNRELATED_TIER = 6
/** Titles holding every word that make the ones holding only some not worth showing. */
const ENOUGH_NAMED = 8
/** Titles with a year and artwork that make the ones without artwork not worth showing. */
const ENOUGH_COMPLETE = 8

function yearOf(releaseInfo: string | undefined): number | null {
  const year = releaseYear(releaseInfo)
  return year === null ? null : Number(year)
}

/** Whether a title has no poster worth drawing: none or a stand-in unless it has an IMDb id (metahub draws those), or a link known to lead nowhere. */
export function isArtless(title: SearchTitle): boolean {
  return title.posterMissing === true || (hasNoPoster(title) && !isImdbId(title.id))
}

function score(ranked: RankedTitle, query: SearchQueryParts): void {
  let tier = nameRelevance(ranked.title.name, query.full)
  if (query.name !== query.full) tier = Math.min(tier, nameRelevance(ranked.title.name, query.name))
  ranked.tier = tier
  ranked.yearMatches = query.year !== null && yearOf(ranked.title.releaseInfo) === query.year
  ranked.complete = releaseYear(ranked.title.releaseInfo) !== null && !isArtless(ranked.title)
}

/** A single title ranked against the query, as one copy at `position` of an addon at `addonRank`. */
export function rankOne(title: SearchTitle, query: SearchQueryParts, position: number, addonRank: number): RankedTitle {
  const ranked: RankedTitle = {
    title,
    tier: UNRELATED_TIER,
    yearMatches: false,
    complete: false,
    copies: 1,
    bestPosition: position,
    addonRank,
    identities: titleIdentities(title),
  }
  score(ranked, query)
  return ranked
}

/** Whether the title's name is exactly the query. */
export function namesQueryExactly(title: RankedTitle): boolean {
  return title.tier === EXACT_NAME_TIER
}

/**
 * The thresholds for a reveal. A person's name counts as one only while no
 * title is named exactly that; then the person's work stays and titles holding
 * only some of the name's words go, however few hold all of them.
 */
export function computeCutoffs(ranked: readonly RankedTitle[], meaning: SearchQueryMeaning = 'title'): RevealCutoffs {
  const person = meaning === 'person' && !ranked.some(namesQueryExactly)
  const dropUnrelated = ranked.some((title) => title.tier < UNRELATED_TIER)
  const dropSomeWords = ranked.filter((title) => title.tier < SOME_WORDS_TIER).length >= ENOUGH_NAMED || person
  // Counted among what the name rules keep, as the screen would list it.
  const byName: RevealCutoffs = { dropUnrelated, dropSomeWords, dropArtless: false, keepCredited: person }
  const dropArtless = ranked.filter((title) => title.complete && passesCutoffs(title, byName)).length >= ENOUGH_COMPLETE
  return { dropUnrelated, dropSomeWords, dropArtless, keepCredited: person }
}

/** Whether a title is shown under these thresholds. One with neither a poster nor a year never is. */
export function passesCutoffs(title: RankedTitle, cutoffs: RevealCutoffs): boolean {
  if (cutoffs.dropUnrelated && title.tier >= UNRELATED_TIER && !(cutoffs.keepCredited && title.complete)) return false
  if (cutoffs.dropSomeWords && title.tier === SOME_WORDS_TIER) return false
  if (!isArtless(title.title)) return true
  return releaseYear(title.title.releaseInfo) !== null && !cutoffs.dropArtless
}

/**
 * Every answer as one list: product codes dropped, years read out of names,
 * copies of one title merged, then ordered by tier, a matching year (when the
 * query has one), completeness, more copies, a better position in a catalog's
 * own list, and a better addon rank. Copies are merged in turns by position
 * and then addon rank, so the result does not depend on the order the answers
 * are passed in.
 */
export function rankForReveal(answers: readonly SearchAnswer[], query: SearchQueryParts): RankedTitle[] {
  // By addon rank, the order passed in breaking a tie (two catalogs of one addon).
  const order = answers.map((_, index) => index).sort((a, b) => answers[a]!.addonRank - answers[b]!.addonRank)
  const longest = Math.max(0, ...answers.map((answer) => answer.titles.length))
  const merged: RankedTitle[] = []
  /** Which answers listed each merged title, to count copies once per catalog. */
  const listedBy: Array<Set<number>> = []
  const placed = new Map<string, number>()
  const claim = (index: number, identities: readonly string[]): void => {
    for (const identity of identities) {
      if (placed.has(identity)) continue
      placed.set(identity, index)
      merged[index]!.identities.push(identity)
    }
  }
  for (let position = 0; position < longest; position += 1) {
    for (const answerIndex of order) {
      const answer = answers[answerIndex]!
      const listed = answer.titles[position]
      if (!listed || isProductCodeName(listed.name)) continue
      const title = withYearOutOfName(listed)
      const identities = titleIdentities(title)
      const kept = identities.map((identity) => placed.get(identity)).find((index) => index !== undefined)
      if (kept === undefined) {
        const index = merged.length
        merged.push({ ...rankOne(title, query, position, answer.addonRank), identities: [] })
        listedBy.push(new Set([answerIndex]))
        claim(index, identities)
        continue
      }
      const entry = merged[kept]!
      entry.title = mergeCopies(entry.title, title)
      const listers = listedBy[kept]!
      if (!listers.has(answerIndex)) {
        listers.add(answerIndex)
        entry.copies += 1
      }
      entry.bestPosition = Math.min(entry.bestPosition, position)
      entry.addonRank = Math.min(entry.addonRank, answer.addonRank)
      claim(kept, identities)
      // A copy that brought a year makes the merged title known by its name and year too.
      claim(kept, titleIdentities(entry.title))
    }
  }
  for (const entry of merged) score(entry, query)
  const hasYear = query.year !== null
  // Array.prototype.sort is stable.
  return merged.sort((a, b) => {
    if (a.tier !== b.tier) return a.tier - b.tier
    if (hasYear && a.yearMatches !== b.yearMatches) return a.yearMatches ? -1 : 1
    if (a.complete !== b.complete) return a.complete ? -1 : 1
    if (a.copies !== b.copies) return b.copies - a.copies
    if (a.bestPosition !== b.bestPosition) return a.bestPosition - b.bestPosition
    return a.addonRank - b.addonRank
  })
}

/** The first title when it is named by the query (tier 0 or 1) and, when the query has a year, from that year. */
export function chooseRevealTopMatch(ranked: readonly RankedTitle[], query: SearchQueryParts): number | null {
  const first = ranked[0]
  if (!first || first.tier > 1) return null
  if (query.year !== null && !first.yearMatches) return null
  return 0
}

/**
 * The poster links of the first `limit` titles a reveal of these answers
 * would show, in screen order: the ones worth knowing to be missing before
 * the results are laid out.
 */
export function revealPosterLinks(
  answers: readonly SearchAnswer[],
  query: SearchQueryParts,
  meaning: SearchQueryMeaning,
  limit: number,
): string[] {
  const ranked = rankForReveal(answers, query)
  const cutoffs = computeCutoffs(ranked, meaning)
  const links: string[] = []
  let shown = 0
  for (const entry of ranked) {
    if (shown === limit) break
    if (!passesCutoffs(entry, cutoffs)) continue
    shown += 1
    if (!hasNoPoster(entry.title)) links.push(entry.title.poster!)
  }
  return links
}

export type RevealDecision = 'wait' | 'reveal'

/**
 * Whether a query's results are shown yet. Every catalog settled (or none to
 * ask) reveals at once; otherwise the deadline reveals once some answer has
 * titles, and with none the page waits for the first one or for every
 * catalog to settle.
 */
export function decideReveal(pending: number, total: number, answeredWithItems: number, sinceAskedMs: number): RevealDecision {
  if (total === 0 || pending === 0) return 'reveal'
  if (sinceAskedMs >= SEARCH_REVEAL_DEADLINE_MS && answeredWithItems > 0) return 'reveal'
  return 'wait'
}

/** What one query's results showed when they were revealed. */
export interface RevealedSet {
  /** In screen order, without the top match. */
  titles: SearchTitle[]
  topMatch: SearchTitle | null
  cutoffs: RevealCutoffs
}

/** What a later answer changed: titles already shown that gained details, in place, and new titles after the last. */
export interface BoardDiff {
  /** Board index and the title as it now reads (same id, kind and name). */
  updates: Array<{ index: number; title: SearchTitle }>
  /** In order; the first lands at the index after the board's last title before this answer. */
  appends: SearchTitle[]
  /** The top match as it now reads, when a later copy filled it in. */
  topMatchUpdate: SearchTitle | null
}

export function isEmptyDiff(diff: BoardDiff): boolean {
  return diff.updates.length === 0 && diff.appends.length === 0 && diff.topMatchUpdate === null
}

/** Where an identity's title sits: a board index, or the top match. */
const TOP_MATCH_SLOT = -1

/** The fields `fillMissingDetails` can change, so an answer that changes none of them is no update. */
function sameDetails(a: SearchTitle, b: SearchTitle): boolean {
  return (
    a.poster === b.poster &&
    (a.posterMissing === true) === (b.posterMissing === true) &&
    a.imdbRating === b.imdbRating &&
    a.releaseInfo === b.releaseInfo &&
    a.description === b.description
  )
}

/**
 * One query's revealed results, which only ever grow at the end once shown.
 * A later answer fills in the details of a title already shown (never its id,
 * kind, name or place) and appends titles that pass the reveal's thresholds.
 * Only a new board (a new query) lays the list out again.
 */
export class SearchBoard {
  private revealedFlag = false
  private query: SearchQueryParts = { full: '', name: '', year: null }
  private cutoffsValue: RevealCutoffs = { dropUnrelated: false, dropSomeWords: false, dropArtless: false, keepCredited: false }
  private titlesValue: SearchTitle[] = []
  private topMatchValue: SearchTitle | null = null
  private readonly slots = new Map<string, number>()

  /** Ranks every answer so far and keeps the result, replacing any earlier reveal on this board. */
  reveal(answers: readonly SearchAnswer[], query: SearchQueryParts, meaning: SearchQueryMeaning = 'title'): RevealedSet {
    this.query = query
    const ranked = rankForReveal(answers, query)
    this.cutoffsValue = computeCutoffs(ranked, meaning)
    const kept = ranked.filter((title) => passesCutoffs(title, this.cutoffsValue))
    const top = chooseRevealTopMatch(kept, query)
    this.titlesValue = []
    this.slots.clear()
    this.topMatchValue = null
    kept.forEach((entry, index) => {
      if (index === top) {
        this.topMatchValue = entry.title
        this.claim(TOP_MATCH_SLOT, entry.identities)
        return
      }
      this.claim(this.titlesValue.length, entry.identities)
      this.titlesValue.push(entry.title)
    })
    this.revealedFlag = true
    return this.snapshot()
  }

  /** A catalog that answered after the reveal. Ignored before one. */
  absorb(late: SearchAnswer): BoardDiff {
    const diff: BoardDiff = { updates: [], appends: [], topMatchUpdate: null }
    if (!this.revealedFlag) return diff
    const firstAppend = this.titlesValue.length
    late.titles.forEach((listed, position) => {
      if (isProductCodeName(listed.name)) return
      const title = withYearOutOfName(listed)
      const identities = titleIdentities(title)
      const slot = this.find(identities)
      if (slot !== null) {
        this.claim(slot, identities)
        const shown = slot === TOP_MATCH_SLOT ? this.topMatchValue! : this.titlesValue[slot]!
        const next = fillMissingDetails(shown, title)
        if (sameDetails(next, shown)) return
        if (slot === TOP_MATCH_SLOT) this.topMatchValue = next
        else this.titlesValue[slot] = next
        this.claim(slot, titleIdentities(next))
        if (slot === TOP_MATCH_SLOT) {
          diff.topMatchUpdate = next
        } else if (slot >= firstAppend) {
          // Appended by this same answer: still news, as it now reads.
          diff.appends[slot - firstAppend] = next
        } else {
          const updated = diff.updates.find((entry) => entry.index === slot)
          if (updated) updated.title = next
          else diff.updates.push({ index: slot, title: next })
        }
        return
      }
      const ranked = rankOne(title, this.query, position, late.addonRank)
      if (!passesCutoffs(ranked, this.cutoffsValue)) return
      this.claim(this.titlesValue.length, ranked.identities)
      diff.appends.push(ranked.title)
      this.titlesValue.push(ranked.title)
    })
    return diff
  }

  /**
   * The query turned out to be a person's name only after the reveal: from
   * now on their complete titles pass although their names share no word with
   * it, as a reveal judged as a person's name would have kept them. Only ever
   * lets more in. False, changing nothing, before a reveal, when the rule is
   * already on, or when a title shown is named exactly the query.
   */
  admitPersonWork(): boolean {
    if (!this.revealedFlag || this.cutoffsValue.keepCredited) return false
    const namedExactly = (title: SearchTitle): boolean => namesQueryExactly(rankOne(title, this.query, 0, 0))
    if ((this.topMatchValue && namedExactly(this.topMatchValue)) || this.titlesValue.some(namedExactly)) return false
    this.cutoffsValue = { ...this.cutoffsValue, keepCredited: true }
    return true
  }

  get revealed(): boolean {
    return this.revealedFlag
  }

  get titles(): readonly SearchTitle[] {
    return this.titlesValue
  }

  get topMatch(): SearchTitle | null {
    return this.topMatchValue
  }

  get cutoffs(): RevealCutoffs {
    return this.cutoffsValue
  }

  private snapshot(): RevealedSet {
    return { titles: [...this.titlesValue], topMatch: this.topMatchValue, cutoffs: { ...this.cutoffsValue } }
  }

  /** The first title an identity named keeps it, so a later copy never moves a match to another card. */
  private claim(slot: number, identities: readonly string[]): void {
    for (const identity of identities) if (!this.slots.has(identity)) this.slots.set(identity, slot)
  }

  private find(identities: readonly string[]): number | null {
    for (const identity of identities) {
      const found = this.slots.get(identity)
      if (found !== undefined) return found
    }
    return null
  }
}

/**
 * Past searches holding what is typed, most recent first, then the names
 * found for it (people and titles, best first), each once and never the typed
 * text itself. `history` is newest first.
 */
export function searchSuggestions(
  history: readonly string[],
  typed: string,
  found: readonly string[] = [],
  limit = SEARCH_SUGGESTION_LIMIT,
): string[] {
  const needle = foldedText(typed.trim())
  if (!needle) return []
  const suggestions: string[] = []
  for (const term of history) {
    if (suggestions.length === limit) break
    const candidate = foldedText(term)
    if (candidate !== needle && candidate.includes(needle)) suggestions.push(term)
  }
  const offered = suggestions.map((suggestion) => foldedText(suggestion))
  for (const name of found) {
    if (suggestions.length >= limit) break
    const trimmed = name.trim()
    const key = foldedText(trimmed)
    if (!key || key === needle || offered.includes(key)) continue
    offered.push(key)
    suggestions.push(trimmed)
  }
  return suggestions
}

/** The note over a search some catalogs did not answer: every one failed, or the addons that did not, each named once. */
export function searchFailureNote(failed: ReadonlyArray<Pick<BoardTarget, 'addonName'>>, total: number): string {
  if (failed.length === 0) return ''
  if (failed.length === total) return 'Search could not reach your addons. Check your connection and try again.'
  const names = [...new Set(failed.map((target) => target.addonName))]
  return `${names.join(', ')} did not answer, so ${names.length === 1 ? 'its' : 'their'} results are missing.`
}

/** "Still asking N more catalog(s)…". */
export function searchStillAskingNote(pending: number): string {
  return `Still asking ${pending} more ${pending === 1 ? 'catalog' : 'catalogs'}…`
}
