import assert from 'node:assert/strict'
import test from 'node:test'
import type { LibraryItem, WatchState } from '@halo/core'
import {
  buildContinueShelf,
  isHomeCatalogType,
  itemMetaId,
  itemType,
  selectFeatured,
  type NextEpisodeLookup,
} from '../src/home/homeRows.ts'

function row(partial: Partial<WatchState> & Pick<WatchState, 'itemId' | 'videoId'>): WatchState {
  return {
    positionSec: 600,
    durationSec: 3000,
    watched: false,
    updatedAt: 1,
    name: 'Title',
    ...partial,
  } as WatchState
}

const unknown = (): NextEpisodeLookup => ({ state: 'unknown' })

test('item ids split on the first colon, and a bare id is a film', () => {
  assert.equal(itemType('series:tt0903747'), 'series')
  assert.equal(itemMetaId('series:tt0903747'), 'tt0903747')
  assert.equal(itemMetaId('anime:kitsu:1376'), 'kitsu:1376')
  assert.equal(itemType('tt0111161'), 'movie')
  assert.equal(itemMetaId('tt0111161'), 'tt0111161')
})

test('newest activity first, one card per show', () => {
  const shelf = buildContinueShelf(
    [
      row({ itemId: 'series:a', videoId: 'a:1:1', updatedAt: 10 }),
      row({ itemId: 'series:a', videoId: 'a:1:2', updatedAt: 30 }),
      row({ itemId: 'movie:b', videoId: 'b', updatedAt: 20 }),
    ],
    [],
    unknown,
  )
  assert.deepEqual(
    shelf.cards.map((card) => [card.itemId, card.videoId]),
    [
      ['series:a', 'a:1:2'],
      ['movie:b', 'b'],
    ],
  )
})

test('a barely started row neither shows nor speaks for its show', () => {
  const shelf = buildContinueShelf(
    [
      row({ itemId: 'series:a', videoId: 'a:1:2', positionSec: 30, updatedAt: 30 }),
      row({ itemId: 'series:a', videoId: 'a:1:1', positionSec: 900, updatedAt: 10 }),
    ],
    [],
    unknown,
  )
  assert.deepEqual(
    shelf.cards.map((card) => card.videoId),
    ['a:1:1'],
  )
})

test('a finished film leaves the shelf and hides its older partial rows', () => {
  const shelf = buildContinueShelf(
    [
      row({ itemId: 'movie:b', videoId: 'b', positionSec: 2900, updatedAt: 30 }),
      row({ itemId: 'movie:b', videoId: 'b', positionSec: 600, updatedAt: 10 }),
    ],
    [],
    unknown,
  )
  assert.equal(shelf.cards.length, 0)
  assert.equal(shelf.requests.length, 0)
})

test('the watched flag or 95% finishes a row', () => {
  const flagged = buildContinueShelf(
    [row({ itemId: 'movie:b', videoId: 'b', positionSec: 60, watched: true })],
    [],
    unknown,
  )
  const nearEnd = buildContinueShelf(
    [row({ itemId: 'movie:c', videoId: 'c', positionSec: 2850, durationSec: 3000 })],
    [],
    unknown,
  )
  assert.equal(flagged.cards.length, 0)
  assert.equal(nearEnd.cards.length, 0)
})

test('a finished episode asks for its successor, then shows it as up next', () => {
  const rows = [row({ itemId: 'series:a', videoId: 'a:1:3', watched: true, updatedAt: 5 })]
  const asking = buildContinueShelf(rows, [], unknown)
  assert.equal(asking.cards.length, 0)
  assert.deepEqual(asking.requests, [
    { itemId: 'series:a', type: 'series', metaId: 'a', videoId: 'a:1:3' },
  ])

  const resolved = buildContinueShelf(rows, [], () => ({ state: 'resolved', videoId: 'a:1:4' }))
  assert.equal(resolved.requests.length, 0)
  assert.deepEqual(
    resolved.cards.map((card) => [card.kind, card.videoId, card.positionSec, card.durationSec]),
    [['next', 'a:1:4', 0, 0]],
  )

  const over = buildContinueShelf(rows, [], () => ({ state: 'none' }))
  assert.equal(over.cards.length, 0)
  assert.equal(over.requests.length, 0)
})

test('the library supplies a missing name and poster; no name at all is skipped', () => {
  const library = [
    { id: 'series:a', type: 'series', name: 'From Library', poster: 'p.jpg', addedAt: 1, updatedAt: 1 },
  ] as LibraryItem[]
  const shelf = buildContinueShelf(
    [
      row({ itemId: 'series:a', videoId: 'a:1:1', name: undefined, updatedAt: 20 }),
      row({ itemId: 'series:z', videoId: 'z:1:1', name: undefined, updatedAt: 30 }),
    ],
    library,
    unknown,
  )
  assert.deepEqual(
    shelf.cards.map((card) => [card.itemId, card.name, card.poster]),
    [['series:a', 'From Library', 'p.jpg']],
  )
})

test('cards and requests are capped', () => {
  const rows = Array.from({ length: 12 }, (_, index) =>
    row({ itemId: `movie:m${index}`, videoId: `m${index}`, updatedAt: 100 - index }),
  )
  assert.equal(buildContinueShelf(rows, [], unknown, 8, 8).cards.length, 8)

  const finished = Array.from({ length: 12 }, (_, index) =>
    row({ itemId: `series:s${index}`, videoId: `s${index}:1:1`, watched: true, updatedAt: 100 - index }),
  )
  assert.equal(buildContinueShelf(finished, [], unknown, 8, 3).requests.length, 3)
})

test('featured takes backdrops first, in catalog order, without repeats', () => {
  const candidates = [
    { id: '1', type: 'movie' },
    { id: '2', type: 'movie', background: 'b2' },
    { id: '3', type: 'series', background: 'b3' },
    { id: '2', type: 'movie', background: 'b2' },
    { id: '4', type: 'movie' },
  ]
  assert.deepEqual(selectFeatured('all', candidates, 5), [1, 2, 0, 4])
  assert.deepEqual(selectFeatured('all', candidates, 2), [1, 2])
})

test('featured honours the filter, treating anything but a series as a film', () => {
  const candidates = [
    { id: '1', type: 'series', background: 'b' },
    { id: '2', type: 'movie', background: 'b' },
    { id: '3', type: 'tv', background: 'b' },
  ]
  assert.deepEqual(selectFeatured('movie', candidates, 5), [1, 2])
  assert.deepEqual(selectFeatured('series', candidates, 5), [0])
})

test('Home shows only film and series catalogs', () => {
  assert.equal(isHomeCatalogType('movie'), true)
  assert.equal(isHomeCatalogType('series'), true)
  // TorBox publishes the account's torrent list as `other`.
  assert.equal(isHomeCatalogType('other'), false)
  assert.equal(isHomeCatalogType('channel'), false)
  assert.equal(isHomeCatalogType('tv'), false)
})
