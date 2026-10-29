import assert from 'node:assert/strict'
import test from 'node:test'
import type { LibraryItem, WatchState } from '@halo/core'
import { buildHistory, groupHistory, searchHistory } from '../src/history/historyRows.ts'

function row(partial: Partial<WatchState> & Pick<WatchState, 'itemId' | 'videoId' | 'updatedAt'>): WatchState {
  return { positionSec: 600, durationSec: 3000, watched: false, name: 'Title', ...partial } as WatchState
}

test('every played video is its own row, newest first, finished or not', () => {
  const history = buildHistory(
    [
      row({ itemId: 'series:a', videoId: 'a:1:1', updatedAt: 10, name: 'Show', watched: true }),
      row({ itemId: 'series:a', videoId: 'a:1:2', updatedAt: 30, name: 'Show' }),
      row({ itemId: 'movie:b', videoId: 'b', updatedAt: 20, name: 'Film', positionSec: 2990 }),
    ],
    [],
    'all',
  )
  assert.deepEqual(
    history.map((entry) => [entry.videoId, entry.metaId, entry.finished]),
    [
      ['a:1:2', 'a', false],
      ['b', 'b', true],
      ['a:1:1', 'a', true],
    ],
  )
})

test('the filter applies, and anything that is not a series counts as a movie', () => {
  const states = [
    row({ itemId: 'series:a', videoId: 'a:1:1', updatedAt: 3 }),
    row({ itemId: 'movie:b', videoId: 'b', updatedAt: 2 }),
    row({ itemId: 'anime:kitsu:1', videoId: 'kitsu:1:1', updatedAt: 1 }),
  ]
  assert.deepEqual(buildHistory(states, [], 'movie').map((e) => e.itemId), ['movie:b', 'anime:kitsu:1'])
  assert.deepEqual(buildHistory(states, [], 'series').map((e) => e.itemId), ['series:a'])
})

test('the library only lends a name and poster; unnamed rows are left out', () => {
  const library: LibraryItem[] = [
    { id: 'movie:b', type: 'movie', name: 'Saved name', poster: 'https://p.test/b.jpg', addedAt: 1, updatedAt: 1 },
  ]
  const history = buildHistory(
    [
      row({ itemId: 'movie:b', videoId: 'b', updatedAt: 2, name: undefined }),
      row({ itemId: 'movie:c', videoId: 'c', updatedAt: 1, name: undefined }),
    ],
    library,
    'all',
  )
  assert.deepEqual(history.map((e) => [e.name, e.poster]), [['Saved name', 'https://p.test/b.jpg']])
})

test('rows group into today, yesterday, earlier this week and months by local calendar', () => {
  const now = new Date(2026, 9, 29, 15, 0).getTime()
  const at = (day: number, month = 9) => new Date(2026, month, day, 12, 0).getTime()
  const entries = [at(29), at(29), at(28), at(24), at(20), at(2), at(30, 8)].map((updatedAt, index) => ({
    itemId: `movie:${index}`,
    type: 'movie',
    metaId: String(index),
    videoId: String(index),
    name: 'x',
    poster: undefined,
    positionSec: 0,
    durationSec: 0,
    finished: false,
    updatedAt,
  }))
  const october = new Date(2026, 9, 2).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
  const september = new Date(2026, 8, 30).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
  assert.deepEqual(
    groupHistory(entries, now).map((group) => [group.label, group.entries.length]),
    [
      ['Today', 2],
      ['Yesterday', 1],
      ['Earlier this week', 1],
      [october, 2],
      [september, 1],
    ],
  )
})

test('search keeps the titles holding every word, ignoring case and accents', () => {
  const history = buildHistory(
    [
      row({ itemId: 'movie:a', videoId: 'a', updatedAt: 3, name: 'Spider-Man: Across the Spider-Verse' }),
      row({ itemId: 'movie:b', videoId: 'b', updatedAt: 2, name: 'Amélie' }),
      row({ itemId: 'movie:c', videoId: 'c', updatedAt: 1, name: 'Spider Baby' }),
    ],
    [],
    'all',
  )
  assert.deepEqual(searchHistory(history, 'spider verse').map((entry) => entry.videoId), ['a'])
  assert.deepEqual(searchHistory(history, 'AMELIE').map((entry) => entry.videoId), ['b'])
  assert.equal(searchHistory(history, '   ').length, 3)
})
