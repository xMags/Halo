import {
  bestTitleTier,
  choosePeopleRow,
  creditTitles,
  decideReveal,
  exactNamePerson,
  foldedText,
  HaloApiError,
  isEmptyDiff,
  metaKey,
  parseSearchQuery,
  revealPosterLinks,
  SearchBoard,
  searchFailureNote,
  searchStillAskingNote,
  searchTargets,
  SEARCH_CREDITS_DEADLINE_MS,
  SEARCH_POSTER_CHECK_DEADLINE_MS,
  SEARCH_POSTER_CHECK_LIMIT,
  SEARCH_REVEAL_DEADLINE_MS,
  SEARCH_SUGGESTION_LIMIT,
  titleIdentities,
  uniqueMetas,
  type AddonEntry,
  type BoardTarget,
  type MetaPreview,
  type PersonSummary,
  type SearchAnswer,
  type SearchQueryMeaning,
  type SearchTitle,
} from '@halo/core'
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useReducer, useRef } from 'react'
import { getClient } from '../api'
import { useCapabilities } from '../capabilities'
import { useEffectiveAddons, useLibrary } from '../queries'
import { checkPosterLinks, posterLinkState, usePosterChecksVersion } from './posterChecks'
import { currentSearchVisit } from './searchVisit'
import { libraryMatches } from './searchResults'

/*
 * One query's search, from the catalogs' answers to what the page shows:
 * every film and series catalog that can search is asked at once (one
 * immediate second try for a catalog that fails, none after a 429), the
 * people named so are asked beside them, and the results are revealed once,
 * when every catalog has answered or 1.2 s after the query with something to
 * show. From then on late answers only fill in cards or add them after the
 * last (`SearchBoard`), so nothing a viewer is looking at moves.
 *
 * A person's exact name keeps the films the addons found for them, and the
 * person's own credits are folded in as one more answer; both are waited for
 * a little before the reveal so they rank among the results.
 */

export type SearchFilter = 'movie' | 'series' | null

/** The named person's films, kept apart from every catalog's key. */
const CREDITS_KEY = 'people/credits'
/** How often an unrevealed query checks whether its reveal is due. */
const REVEAL_CHECK_MS = 100
const SEARCHING_NOTE = 'Searching…'

interface RankedTarget extends BoardTarget {
  addonRank: number
}

/** One query's board and what was decided at its reveal. */
interface Session {
  query: string
  /** Bumped by Try again: the same query asked anew is a new board. */
  attempt: number
  board: SearchBoard
  askedAt: number
  revealed: boolean
  /** Answers already given to the board, by catalog key. */
  absorbed: Set<string>
  library: MetaPreview[]
  libraryIdentities: Set<string>
  bestTier: number | null
  /** A person's exact name was let in after the reveal (`admitPersonWork`). */
  personAdmitted: boolean
}

export interface SearchStatus {
  text: string
  spinner: boolean
  failed: boolean
}

export interface SearchView {
  /** No query to search: the page shows its idle state. */
  idle: boolean
  /** Waiting for this query's reveal, showing the previous one's results dimmed. */
  stale: boolean
  /** Waiting for a first reveal with nothing to show: placeholders. */
  skeleton: boolean
  people: PersonSummary[]
  topMatch: SearchTitle | null
  library: MetaPreview[]
  results: SearchTitle[]
  status: SearchStatus | null
  noAddons: boolean
  addonsUnreachable: boolean
  nothingFound: boolean
  filteredEmpty: boolean
  /** The query the shown results answer. */
  revealedQuery: string
  /** Names found for what is being typed, for the suggestions: people, then titles. */
  foundNames: (typed: string) => string[]
  retry: () => void
}

function rankedTargets(addons: readonly AddonEntry[]): RankedTarget[] {
  const rank = new Map(addons.map((addon, index) => [addon.id, index]))
  return searchTargets(addons).map((target) => ({ ...target, addonRank: rank.get(target.addonId) ?? addons.length }))
}

/** One catalog asked, and asked once more at once when it fails, unless it said to slow down. */
async function askCatalog(target: BoardTarget, query: string, signal: AbortSignal): Promise<MetaPreview[]> {
  const ask = async () =>
    uniqueMetas((await getClient().getCatalog(target.addonId, target.type, target.id, { search: query }, { signal })).metas ?? [])
  try {
    return await ask()
  } catch (error) {
    if (signal.aborted || (error instanceof HaloApiError && error.status === 429)) throw error
    return ask()
  }
}

/** A poster link known to lead nowhere marks its title as one without artwork. */
function marked(titles: readonly MetaPreview[]): SearchTitle[] {
  return titles.map((title) => {
    const missing = title.poster ? posterLinkState(title.poster) === 'missing' : false
    return missing ? { ...title, posterMissing: true } : title
  })
}

/**
 * The last search's board, kept beyond the page. The screen stack draws only
 * its top screen, so Back from a title builds Search anew; a new board would
 * rank answers that came in meanwhile among the others, and the results seen
 * before would move. Kept for one visit to Search (`searchVisit`), so opening
 * Search afresh searches anew, and only for the backend it was asked through,
 * so a sign-in or sign-out never shows the other one's results.
 */
interface RetainedSearch {
  backend: object
  visit: number
  session: Session | null
  /** Bumped by Try again. */
  attempt: number
}

let retained: RetainedSearch | null = null

function retainedFor(backend: object, visit: number): RetainedSearch {
  if (retained?.backend !== backend || retained.visit !== visit) retained = { backend, visit, session: null, attempt: 0 }
  return retained
}

export function useSearchSession(query: string, enabled: boolean, filter: SearchFilter): SearchView {
  const queryClient = useQueryClient()
  const { capabilities } = useCapabilities()
  const addonsQuery = useEffectiveAddons()
  const { data: library } = useLibrary()
  // A poster check that answers renders again, so a reveal waiting on it is looked at.
  usePosterChecksVersion()
  const [, rerender] = useReducer((count: number) => count + 1, 0)
  const memory = retainedFor(getClient(), currentSearchVisit())
  /** The last revealed session, shown dimmed while the next query is awaited. */
  const shownRef = useRef<Session | null>(null)

  const targets = addonsQuery.data ? rankedTargets(addonsQuery.data) : []
  const searching = enabled && !!addonsQuery.data
  const catalogs = useQueries({
    queries: targets.map((target) => ({
      queryKey: ['search', query, target.addonId, target.type, target.id] as const,
      enabled: searching,
      staleTime: 60_000,
      retry: false,
      queryFn: ({ signal }: { signal: AbortSignal }) => askCatalog(target, query, signal),
    })),
  })

  const peopleOn = enabled && capabilities.people
  const peopleQuery = useQuery({
    queryKey: ['people', 'search', query],
    enabled: peopleOn,
    staleTime: 10 * 60_000,
    retry: false,
    queryFn: ({ signal }) => getClient().searchPeople(query, { signal }),
  })
  const peopleAnswer = peopleOn && peopleQuery.isSuccess ? peopleQuery.data : null
  // A misspelled name is read, from here on, by the name the server corrected it to.
  const peopleName = peopleAnswer?.corrected ?? query
  const named = peopleAnswer ? exactNamePerson(peopleAnswer.people, peopleName) : null
  const creditsQuery = useQuery({
    queryKey: ['person', named?.tmdbId ?? 0],
    enabled: peopleOn && named !== null,
    staleTime: 10 * 60_000,
    retry: false,
    queryFn: ({ signal }) => getClient().getPerson(named!.tmdbId, { signal }),
  })
  // No answer reads as no films: the addons' results stand as they are.
  const credits = named && creditsQuery.isSuccess ? creditTitles(creditsQuery.data.credits) : named && creditsQuery.isError ? [] : null

  /* ── The board, kept outside React state: it only grows, and is read on render. ── */

  if (!enabled) {
    memory.session = null
    // Back to the empty page: the next query starts without dimmed leftovers.
    shownRef.current = null
  } else if (!memory.session || memory.session.query !== query || memory.session.attempt !== memory.attempt) {
    if (memory.session?.revealed) shownRef.current = memory.session
    memory.session = {
      query,
      attempt: memory.attempt,
      board: new SearchBoard(),
      askedAt: Date.now(),
      revealed: false,
      absorbed: new Set(),
      library: [],
      libraryIdentities: new Set(),
      bestTier: null,
      personAdmitted: false,
    }
  }
  const session = memory.session

  const pending = searching ? catalogs.filter((result) => result.isPending).length : 0
  const failedTargets = searching ? targets.filter((_, index) => catalogs[index]?.isError) : []
  const total = searching ? targets.length : 0
  const answeredWithItems = catalogs.filter((result) => result.isSuccess && result.data.length > 0).length

  /** Every settled answer (or only those the board has not been given), marking each as given. */
  const collect = (onlyNew: boolean): SearchAnswer[] => {
    if (!session) return []
    const answers: SearchAnswer[] = []
    targets.forEach((target, index) => {
      const result = catalogs[index]
      if (!result || result.isPending) return
      const fresh = !session.absorbed.has(target.key)
      session.absorbed.add(target.key)
      if ((onlyNew && !fresh) || !result.isSuccess || result.data.length === 0) return
      answers.push({ target, titles: marked(result.data), addonRank: target.addonRank })
    })
    if (credits && credits.length > 0) {
      const fresh = !session.absorbed.has(CREDITS_KEY)
      session.absorbed.add(CREDITS_KEY)
      if (!onlyNew || fresh) {
        answers.push({
          target: { key: CREDITS_KEY, addonId: '', addonName: '', type: '', id: '', title: '' },
          titles: marked(credits),
          addonRank: targets.length,
        })
      }
    }
    return answers
  }

  const meaning: SearchQueryMeaning = named ? 'person' : 'title'

  /** Runs after every render and on the reveal timer: reveals when due, then takes late answers in. */
  const evaluate = (): void => {
    if (!session) return
    if (!session.revealed) {
      if (addonsQuery.isPending) return
      const sinceAsked = Date.now() - session.askedAt
      const parts = parseSearchQuery(query)
      const links = revealPosterLinks(collect(false), parts, meaning, SEARCH_POSTER_CHECK_LIMIT)
      checkPosterLinks(links)
      const postersSettled =
        sinceAsked >= SEARCH_POSTER_CHECK_DEADLINE_MS || links.every((link) => posterLinkState(link) !== 'unknown')
      // Whether the words are a person's name decides which titles stay, so
      // the people answer is waited for like a catalog, up to the deadline;
      // their films a little longer.
      if (peopleOn && peopleQuery.isPending && sinceAsked < SEARCH_REVEAL_DEADLINE_MS) return
      if (named && credits === null && sinceAsked < SEARCH_CREDITS_DEADLINE_MS) return
      const addonsFailed = addonsQuery.isError
      if (!addonsFailed && (decideReveal(pending, total, answeredWithItems, sinceAsked) !== 'reveal' || !postersSettled)) return
      session.board.reveal(collect(false), parts, meaning)
      session.revealed = true
      session.personAdmitted = meaning === 'person'
      const saved = libraryMatches(library, query)
      // Every saved match keeps its copies out of the results, the top match included.
      session.libraryIdentities = new Set(saved.flatMap(titleIdentities))
      const top = session.board.topMatch
      // The top match is shown once: its card says "In library", so the row leaves it out.
      const topKey = top ? metaKey(top) : null
      session.library = saved.filter((title) => metaKey(title) !== topKey)
      session.bestTier = bestTitleTier(top ? [...session.board.titles, top] : session.board.titles, query)
      shownRef.current = null
      rerender()
      return
    }
    let changed = false
    // Known to be a person only after the reveal: their work the reveal left
    // out is offered again, and lands after the last card.
    if (named && !session.personAdmitted) {
      session.personAdmitted = true
      if (session.board.admitPersonWork()) {
        for (const answer of collect(false)) changed = !isEmptyDiff(session.board.absorb(answer)) || changed
      }
    }
    for (const answer of collect(true)) changed = !isEmptyDiff(session.board.absorb(answer)) || changed
    if (changed) rerender()
  }

  // The timer runs the latest render's check, so it reads the latest answers.
  const evaluateRef = useRef(evaluate)
  evaluateRef.current = evaluate
  useEffect(() => evaluateRef.current())
  const waiting = !!session && !session.revealed
  useEffect(() => {
    if (!waiting) return
    const timer = window.setInterval(() => evaluateRef.current(), REVEAL_CHECK_MS)
    return () => window.clearInterval(timer)
  }, [waiting, session])

  /* ── What the page shows ── */

  const retry = (): void => {
    memory.attempt += 1
    // A failed addon list is loaded again too. Reset rather than refetched: a
    // refetch keeps the query in error while it runs, and the new attempt would
    // reveal that error at once instead of waiting for the list.
    if (addonsQuery.isError) void queryClient.resetQueries({ queryKey: ['addons'], exact: true })
    void queryClient.resetQueries({ queryKey: ['search', query] })
    if (peopleQuery.isError) void queryClient.resetQueries({ queryKey: ['people', 'search', query] })
    rerender()
  }

  if (!enabled || !session) {
    return {
      idle: true,
      stale: false,
      skeleton: false,
      people: [],
      topMatch: null,
      library: [],
      results: [],
      status: null,
      noAddons: false,
      addonsUnreachable: false,
      nothingFound: false,
      filteredEmpty: false,
      revealedQuery: '',
      foundNames: () => [],
      retry,
    }
  }

  const shown = session.revealed ? session : shownRef.current
  const ofKind = <T extends MetaPreview>(titles: readonly T[]): T[] => (filter ? titles.filter((title) => title.type === filter) : [...titles])
  const libraryShown = shown ? ofKind(shown.library) : []
  const results = shown
    ? ofKind(shown.board.titles).filter((title) => !titleIdentities(title).some((identity) => shown.libraryIdentities.has(identity)))
    : []
  // People are not films or series, so a kind chip leaves them out. The row
  // belongs to the reveal it answers, and goes first whenever it lands.
  const peopleForShown =
    shown && shown.query === query && peopleAnswer ? choosePeopleRow(peopleAnswer.people, peopleName, shown.bestTier) : []
  const people = filter === null ? peopleForShown : []
  const topMatch = shown?.board.topMatch ?? null

  let status: SearchStatus
  let addonsUnreachable = false
  let nothingFound = false
  let filteredEmpty = false
  const noAddons = !!addonsQuery.data && targets.length === 0
  if (!session.revealed) {
    // Until the reveal the line says only that the search is under way.
    status = { text: SEARCHING_NOTE, spinner: true, failed: false }
  } else {
    const settledEmpty = pending === 0 && libraryShown.length === 0 && results.length === 0
    filteredEmpty = settledEmpty && !topMatch && filter !== null && answeredWithItems > 0
    nothingFound =
      settledEmpty && !topMatch && filter === null && total > 0 && failedTargets.length < total && peopleForShown.length === 0
    const everyOneFailed = pending === 0 && total > 0 && failedTargets.length >= total
    addonsUnreachable = addonsQuery.isError || (everyOneFailed && settledEmpty && !topMatch)
    if (addonsUnreachable) status = { text: '', spinner: false, failed: false }
    else if (pending > 0) status = { text: searchStillAskingNote(pending), spinner: true, failed: false }
    else {
      const note = searchFailureNote(failedTargets, total)
      status = note
        ? { text: note, spinner: false, failed: true }
        : peopleAnswer?.corrected && peopleForShown.length > 0
          ? { text: `Showing results for ${peopleAnswer.corrected}`, spinner: false, failed: false }
          : { text: '', spinner: false, failed: false }
    }
  }

  const foundNames = (typed: string): string[] => {
    // Only what was found for the text in the box, or for its beginning while more of it is typed.
    const typedKey = foldedText(typed.trim())
    const revealedKey = foldedText(session.query)
    if (!session.revealed || !revealedKey || !typedKey.startsWith(revealedKey)) return []
    const names = peopleForShown.map((person) => person.name)
    if (session.board.topMatch) names.push(session.board.topMatch.name)
    for (const title of session.board.titles) {
      if (names.length >= SEARCH_SUGGESTION_LIMIT * 2) break
      names.push(title.name)
    }
    return names
  }

  return {
    idle: false,
    stale: !session.revealed && shown !== null,
    skeleton: !session.revealed && shown === null,
    people,
    topMatch,
    library: libraryShown,
    results,
    status,
    noAddons,
    addonsUnreachable,
    nothingFound,
    filteredEmpty,
    revealedQuery: shown?.query ?? '',
    foundNames,
    retry,
  }
}
