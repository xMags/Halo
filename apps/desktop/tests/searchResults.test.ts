import assert from 'node:assert/strict'
import test from 'node:test'
import type { MetaPreview } from '@halo/core'
import { searchProgress, uniqueMetas, type SearchTarget } from '../src/search/searchResults.ts'

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
