import assert from 'node:assert/strict'
import test from 'node:test'
import type { LibraryItem, MetaPreview } from '@halo/core'
import {
  interleave,
  libraryMatches,
  searchProgress,
  suggestedSearches,
  uniqueMetas,
  type SearchTarget,
} from '../src/search/searchResults.ts'

const target = (key: string, addonName: string, type = 'movie'): SearchTarget => ({
  key,
  addonId: key,
  addonName,
  type,
  id: 'search',
  title: 'Search',
})
const meta = (id: string, type = 'movie'): MetaPreview => ({ id, type, name: id })

const cinemetaMovies = target('c/movie', 'Cinemeta')
const cinemetaSeries = target('c/series', 'Cinemeta', 'series')
const tmdbMovies = target('t/movie', 'TMDB')

test('rows appear as their catalogs answer, in addon order', () => {
  const progress = searchProgress(
    [cinemetaMovies, cinemetaSeries, tmdbMovies],
    [{ status: 'pending' }, { status: 'done', metas: [meta('s1', 'series')] }, { status: 'done', metas: [meta('m9')] }],
  )
  assert.deepEqual(progress.groups.map((g) => g.key), ['c/series', 't/movie'])
  assert.equal(progress.pending, 1)
})

test('the top match waits for earlier catalogs, so it never swaps once shown', () => {
  const targets = [cinemetaMovies, tmdbMovies]
  assert.equal(searchProgress(targets, [{ status: 'pending' }, { status: 'done', metas: [meta('m9')] }]).topMatch, null)
  assert.equal(searchProgress(targets, [{ status: 'done', metas: [meta('m1')] }, { status: 'pending' }]).topMatch?.id, 'm1')
  // An earlier catalog that failed or found nothing passes the choice on.
  assert.equal(searchProgress(targets, [{ status: 'failed' }, { status: 'done', metas: [meta('m9')] }]).topMatch?.id, 'm9')
  assert.equal(searchProgress(targets, [{ status: 'done', metas: [] }, { status: 'done', metas: [meta('m9')] }]).topMatch?.id, 'm9')
})

test('failed catalogs are reported, not dropped silently', () => {
  const progress = searchProgress([cinemetaMovies, tmdbMovies], [{ status: 'failed' }, { status: 'done', metas: [] }])
  assert.deepEqual(progress.failed.map((t) => t.addonName), ['Cinemeta'])
  assert.deepEqual(progress.groups, [])
  assert.equal(progress.total, 2)
  assert.equal(progress.pending, 0)
})

test('a catalog that lists a title twice shows it once', () => {
  assert.deepEqual(uniqueMetas([meta('a'), meta('a'), meta('a', 'series')]).map((m) => `${m.type}:${m.id}`), [
    'movie:a',
    'series:a',
  ])
})

const title = (id: string, type = 'movie'): MetaPreview => ({ id, type, name: id })
const ids = (metas: MetaPreview[]) => metas.map((meta) => `${meta.type}:${meta.id}`)

test('interleave takes turns between the lists and keeps each title once', () => {
  const cinemeta = [title('tt1'), title('tt2'), title('tt3'), title('tt4')]
  const tmdb = [title('tt2'), title('tt9')]
  const series = [title('tt5', 'series')]

  assert.deepEqual(ids(interleave([cinemeta, tmdb, series])), [
    'movie:tt1',
    'movie:tt2',
    'series:tt5',
    'movie:tt9',
    'movie:tt3',
    'movie:tt4',
  ])
})

test('interleave leaves out what is shown elsewhere', () => {
  assert.deepEqual(ids(interleave([[title('tt1'), title('tt2')]], new Set(['movie:tt1']))), ['movie:tt2'])
})

const item = (id: string, name: string, extra: Partial<LibraryItem> = {}): LibraryItem => ({
  id,
  type: id.split(':')[0]!,
  name,
  addedAt: 1,
  updatedAt: 1,
  ...extra,
})

test('libraryMatches finds every word, ignoring case and accents, newest first', () => {
  const library = [
    item('movie:tt1', 'Dune', { addedAt: 1 }),
    item('movie:tt2', 'Dune: Part Two', { addedAt: 5, poster: 'p.jpg' }),
    item('movie:tt3', 'Amélie', { addedAt: 3 }),
    item('series:tt4', 'Dune: Prophecy', { addedAt: 4, removedAt: 9 }),
  ]

  assert.deepEqual(libraryMatches(library, 'dune'), [
    { id: 'tt2', type: 'movie', name: 'Dune: Part Two', poster: 'p.jpg' },
    { id: 'tt1', type: 'movie', name: 'Dune' },
  ])
  assert.deepEqual(ids(libraryMatches(library, 'TWO dune')), ['movie:tt2'])
  assert.deepEqual(ids(libraryMatches(library, 'amelie')), ['movie:tt3'])
  assert.deepEqual(libraryMatches(library, '   '), [])
})

test('suggestedSearches offers past searches containing what is typed', () => {
  const history = [
    { term: 'dune part two', at: 3 },
    { term: 'Dune', at: 2 },
    { term: 'the office', at: 1 },
  ]
  assert.deepEqual(suggestedSearches(history, 'dun'), ['dune part two', 'Dune'])
  // What is already typed is not offered back.
  assert.deepEqual(suggestedSearches(history, 'dune'), ['dune part two'])
  assert.deepEqual(suggestedSearches(history, ''), [])
})
