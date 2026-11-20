import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  computeCutoffs,
  decideReveal,
  isEmptyDiff,
  parseSearchQuery,
  passesCutoffs,
  rankForReveal,
  revealPosterLinks,
  SearchBoard,
  searchFailureNote,
  searchStillAskingNote,
  searchSuggestions,
  searchTargets,
  cappedQuery,
  isSearchableQuery,
  type SearchAnswer,
  type SearchQueryMeaning,
  type SearchTitle,
} from '../src/search/board'
import { byRelevance, hasNoPoster, mergeCopies, metaKey, nameRelevance } from '../src/search/results'

/*
 * The shared vectors (test/vectors/search-board.json) hold the rules both
 * this package and the Kotlin app must follow; see their `about` line.
 */

interface VectorAnswer {
  target: string
  type: string
  addonRank: number
  titles: SearchTitle[]
}

const vectors = JSON.parse(readFileSync(new URL('./vectors/search-board.json', import.meta.url), 'utf8')) as {
  currentYear: number
  nameRelevance: Array<{ name: string; query: string; tier: number }>
  parseQuery: Array<{ query: string; full: string; name: string; year: number | null }>
  hasNoPoster: Array<{ poster?: string; id?: string; none: boolean }>
  rank: Array<{
    case: string
    query: string
    sorted?: boolean
    reversedToo?: boolean
    answers: VectorAnswer[]
    keys: string[]
    first?: Partial<SearchTitle>
    firstTier?: number
    firstRank?: { copies: number; bestPosition: number; addonRank: number }
  }>
  byRelevance: Array<{ query: string; titles: SearchTitle[]; ids: string[] }>
  refine: Array<{ case: string; query: string; titles: SearchTitle[]; ids?: string[]; count?: number; absent?: string[]; lastId?: string }>
  board: Array<{
    case: string
    query: string
    meaning?: SearchQueryMeaning
    answers: VectorAnswer[]
    top?: string | null
    titles?: string[]
    cutoffs?: Record<string, boolean>
    steps?: Array<
      | { admitPersonWork: boolean }
      | {
          absorb: VectorAnswer
          appends?: string[]
          updates?: Array<{ index: number; id: string } & Partial<SearchTitle>>
          topMatchUpdate?: ({ id: string } & Partial<SearchTitle>) | null
        }
    >
    finalTitles?: string[]
    finalTop?: string
  }>
  posterLinks: Array<{ query: string; meaning: SearchQueryMeaning; answers: VectorAnswer[]; limit: number; links: string[] }>
  mergeCopies: Array<{ case: string; kept: SearchTitle; other: SearchTitle; posterMissing: boolean; poster?: string }>
  reveal: Array<{ pending: number; total: number; answeredWithItems: number; sinceMs: number; decision: string }>
  suggestions: Array<{ history: string[]; typed: string; found: string[]; expect: string[] }>
  failureNote: Array<{ failed: string[]; total: number; note: string }>
  stillAsking: Array<{ pending: number; note: string }>
}

const parts = (query: string) => parseSearchQuery(query, vectors.currentYear)
const toAnswer = (answer: VectorAnswer): SearchAnswer => ({
  target: { key: answer.target, addonId: answer.target, addonName: answer.target, type: answer.type, id: 'search', title: 'Search' },
  titles: answer.titles,
  addonRank: answer.addonRank,
})
const ids = (titles: readonly SearchTitle[]) => titles.map((title) => title.id)

test('vectors: name relevance', () => {
  for (const row of vectors.nameRelevance) assert.equal(nameRelevance(row.name, row.query), row.tier, `${row.name} / ${row.query}`)
})

test('vectors: a trailing year is read apart', () => {
  for (const row of vectors.parseQuery) assert.deepEqual(parts(row.query), { full: row.full, name: row.name, year: row.year }, row.query)
})

test('vectors: what counts as no poster', () => {
  for (const row of vectors.hasNoPoster) {
    assert.equal(hasNoPoster({ ...(row.poster ? { poster: row.poster } : {}), ...(row.id ? { id: row.id } : {}) }), row.none, row.poster)
  }
})

test('vectors: ranking for a reveal', () => {
  for (const row of vectors.rank) {
    const answers = row.answers.map(toAnswer)
    const ranked = rankForReveal(answers, parts(row.query))
    const keys = ranked.map((entry) => metaKey(entry.title))
    assert.deepEqual(row.sorted ? [...keys].sort() : keys, row.keys, row.case)
    if (row.first) for (const [field, value] of Object.entries(row.first)) assert.equal(ranked[0]!.title[field as keyof SearchTitle], value, `${row.case}: ${field}`)
    if (row.firstTier !== undefined) assert.equal(ranked[0]!.tier, row.firstTier, row.case)
    if (row.firstRank) {
      const { copies, bestPosition, addonRank } = ranked[0]!
      assert.deepEqual({ copies, bestPosition, addonRank }, row.firstRank, row.case)
    }
    if (row.reversedToo) {
      const reversed = rankForReveal([...answers].reverse(), parts(row.query)).map((entry) => metaKey(entry.title))
      assert.deepEqual(reversed, keys, `${row.case}: reversed`)
    }
  }
})

test('vectors: by relevance keeps each level in order', () => {
  for (const row of vectors.byRelevance) assert.deepEqual(ids(byRelevance(row.titles, row.query)), row.ids)
})

test('vectors: one catalog judged by the cutoffs', () => {
  for (const row of vectors.refine) {
    const ranked = rankForReveal([toAnswer({ target: 't/movie', type: 'movie', addonRank: 0, titles: row.titles })], parts(row.query))
    const cutoffs = computeCutoffs(ranked)
    const kept = ranked.filter((entry) => passesCutoffs(entry, cutoffs)).map((entry) => entry.title.id)
    if (row.ids) assert.deepEqual(kept, row.ids, row.case)
    if (row.count !== undefined) assert.equal(kept.length, row.count, row.case)
    for (const id of row.absent ?? []) assert.ok(!kept.includes(id), `${row.case}: ${id}`)
    if (row.lastId) assert.equal(kept.at(-1), row.lastId, row.case)
  }
})

test('vectors: the board reveals once and late answers only fill in or append', () => {
  for (const row of vectors.board) {
    const board = new SearchBoard()
    const revealed = board.reveal(row.answers.map(toAnswer), parts(row.query), row.meaning ?? 'title')
    if (row.top !== undefined) assert.equal(revealed.topMatch?.id ?? null, row.top, `${row.case}: top`)
    if (row.titles) assert.deepEqual(ids(revealed.titles), row.titles, `${row.case}: titles`)
    for (const [field, value] of Object.entries(row.cutoffs ?? {})) {
      assert.equal(revealed.cutoffs[field as keyof typeof revealed.cutoffs], value, `${row.case}: ${field}`)
    }
    for (const [index, step] of (row.steps ?? []).entries()) {
      const label = `${row.case}: step ${index}`
      if ('admitPersonWork' in step) {
        assert.equal(board.admitPersonWork(), step.admitPersonWork, label)
        continue
      }
      const diff = board.absorb(toAnswer(step.absorb))
      if (step.appends) assert.deepEqual(ids(diff.appends), step.appends, `${label}: appends`)
      if (step.updates) {
        assert.equal(diff.updates.length, step.updates.length, `${label}: updates`)
        step.updates.forEach((expected, at) => {
          const actual = diff.updates[at]!
          assert.equal(actual.index, expected.index, label)
          for (const [field, value] of Object.entries(expected)) {
            if (field !== 'index') assert.equal(actual.title[field as keyof SearchTitle], value, `${label}: ${field}`)
          }
          assert.deepEqual(board.titles[actual.index], actual.title, `${label}: kept on the board`)
        })
      }
      if (step.topMatchUpdate === null) assert.equal(diff.topMatchUpdate, null, `${label}: top match`)
      else if (step.topMatchUpdate) {
        for (const [field, value] of Object.entries(step.topMatchUpdate)) {
          assert.equal(diff.topMatchUpdate?.[field as keyof SearchTitle], value, `${label}: top ${field}`)
        }
      }
    }
    if (row.finalTitles) assert.deepEqual(ids(board.titles), row.finalTitles, `${row.case}: final`)
    if (row.finalTop) assert.equal(board.topMatch?.id, row.finalTop, `${row.case}: final top`)
  }
})

test('vectors: poster links worth checking before the reveal', () => {
  for (const row of vectors.posterLinks) {
    const answers = row.answers.map(toAnswer)
    assert.deepEqual(revealPosterLinks(answers, parts(row.query), row.meaning, row.limit), row.links)
    assert.ok(revealPosterLinks(answers, parts(row.query), row.meaning, 2).length <= 2)
  }
})

test('vectors: merging copies and the missing-poster mark', () => {
  for (const row of vectors.mergeCopies) {
    const merged = mergeCopies(row.kept, row.other)
    assert.equal(merged.posterMissing === true, row.posterMissing, row.case)
    if (row.poster) assert.equal(merged.poster, row.poster, row.case)
  }
})

test('vectors: when results are revealed', () => {
  for (const row of vectors.reveal) {
    assert.equal(decideReveal(row.pending, row.total, row.answeredWithItems, row.sinceMs), row.decision, JSON.stringify(row))
  }
})

test('vectors: suggestions, notes', () => {
  for (const row of vectors.suggestions) assert.deepEqual(searchSuggestions(row.history, row.typed, row.found), row.expect, row.typed)
  for (const row of vectors.failureNote) {
    assert.equal(searchFailureNote(row.failed.map((addonName) => ({ addonName })), row.total), row.note)
  }
  for (const row of vectors.stillAsking) assert.equal(searchStillAskingNote(row.pending), row.note)
})

test('an unrevealed board absorbs nothing', () => {
  const board = new SearchBoard()
  const answer = toAnswer({ target: 't', type: 'movie', addonRank: 0, titles: [{ id: 'tt1', type: 'movie', name: 'Dune' }] })
  assert.ok(isEmptyDiff(board.absorb(answer)))
  assert.deepEqual(board.titles, [])
  assert.equal(board.admitPersonWork(), false)
})

test('query limits', () => {
  assert.equal(cappedQuery('a'.repeat(130)).length, 120)
  assert.equal(cappedQuery(`${'a'.repeat(119)}\u{1F600}`).length, 119)
  assert.equal(isSearchableQuery(' a '), false)
  assert.equal(isSearchableQuery(' ab '), true)
})

test('search targets are film and series catalogs that need only the query', () => {
  const targets = searchTargets([
    {
      id: 'a',
      manifest: {
        name: 'A',
        catalogs: [
          { type: 'movie', id: 'top', extra: [{ name: 'search' }] },
          { type: 'series', id: 'legacy', extraSupported: ['search'] },
          { type: 'movie', id: 'genre', extra: [{ name: 'search' }, { name: 'genre', isRequired: true }] },
          { type: 'tv', id: 'channels', extra: [{ name: 'search' }] },
          { type: 'other', id: 'torrents', extra: [{ name: 'search' }] },
          { type: 'movie', id: 'browse' },
        ],
      },
    },
  ])
  assert.deepEqual(targets.map((target) => target.key), ['a/movie/top', 'a/series/legacy'])
})

/** A small seeded generator, so a failure names a seed that reproduces it. */
function seeded(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0
    return state / 2 ** 32
  }
}

test('whatever the arrival order, a late answer never moves, removes or replaces a shown card', () => {
  const full = (id: string, name: string, extra: Partial<SearchTitle> = {}): SearchTitle => ({
    id,
    type: 'movie',
    name,
    releaseInfo: '2016',
    poster: `https://image.tmdb.org/t/p/w500/${id}.jpg`,
    ...extra,
  })
  const answers: SearchAnswer[] = [
    toAnswer({ target: 'c/movie', type: 'movie', addonRank: 0, titles: [full('tt1160419', 'Dune', { releaseInfo: '2021' }), full('tt15239678', 'Dune: Part Two'), full('tt0087182', 'Dune', { releaseInfo: '1984' })] }),
    toAnswer({ target: 'c/series', type: 'series', addonRank: 0, titles: [{ id: 'tt10466872', type: 'series', name: 'Dune: Prophecy', releaseInfo: '2024' }, { id: 'tt0142032', type: 'series', name: 'Dune', releaseInfo: '2000' }] }),
    toAnswer({ target: 't/movie', type: 'movie', addonRank: 1, titles: [full('tmdb:438631', 'Dune', { releaseInfo: '2021', imdb_id: 'tt1160419', imdbRating: '7.8' }), full('tmdb:693134', 'Dune: Part Two', { imdb_id: 'tt15239678' }), full('tmdb:1', 'Dune Drifter')] }),
    toAnswer({ target: 'm/movie', type: 'movie', addonRank: 2, titles: [{ id: 'mf1706190', type: 'movie', name: 'Dune (2021)' }, { id: 'mf17', type: 'movie', name: 'Dune World' }, full('tmdb:2', 'Faust and the Devil')] }),
    toAnswer({ target: 'o/movie', type: 'movie', addonRank: 3, titles: [{ id: 'tt0087182', type: 'movie', name: 'Dune', releaseInfo: '1984', imdbRating: '6.3' }, full('o:3', 'Dune Messiah'), full('o:4', 'Children of Dune')] }),
  ]
  for (let seed = 1; seed <= 40; seed += 1) {
    const random = seeded(seed)
    const order = [...answers].sort(() => random() - 0.5)
    const revealedCount = (seed % order.length) + 1
    for (const query of ['dune', 'dune 2021']) {
      const board = new SearchBoard()
      const revealed = board.reveal(order.slice(0, revealedCount), parts(query))
      let shown = revealed.titles.map(metaKey)
      const top = revealed.topMatch ? metaKey(revealed.topMatch) : ''
      for (const late of order.slice(revealedCount)) {
        const before = board.titles.length
        const diff = board.absorb(late)
        const now = board.titles.map(metaKey)
        assert.equal(now.length, before + diff.appends.length, `seed ${seed}`)
        assert.deepEqual(now.slice(0, shown.length), shown, `seed ${seed}`)
        for (const update of diff.updates) {
          assert.ok(update.index < before, `seed ${seed}`)
          assert.equal(metaKey(update.title), now[update.index], `seed ${seed}`)
        }
        assert.equal(board.topMatch ? metaKey(board.topMatch) : '', top, `seed ${seed}`)
        shown = now
      }
    }
  }
})
