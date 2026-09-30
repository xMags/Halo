import assert from 'node:assert/strict'
import test from 'node:test'
import type { WatchState } from '@halo/core'
import { hasWatchProgress, unwatchedRow, watchedRow } from '../src/browse/watchMarks.ts'
import { buildContinueShelf, type NextEpisodeLookup } from '../src/home/homeRows.ts'

const episode = { videoId: 'tt1:1:3', itemId: 'series:tt1', name: 'Show', poster: 'https://img/show.jpg' }

function row(partial: Partial<WatchState> & Pick<WatchState, 'videoId'>): WatchState {
  return {
    itemId: 'series:tt1',
    positionSec: 600,
    durationSec: 3000,
    watched: false,
    name: 'Show',
    updatedAt: 1,
    ...partial,
  }
}

test('marking watched keeps a measured duration and fills the position', () => {
  const existing = row({ videoId: 'tt1:1:3', positionSec: 1200, durationSec: 2710, updatedAt: 5 })
  assert.deepEqual(watchedRow(episode, existing, 2880, 100), {
    videoId: 'tt1:1:3',
    itemId: 'series:tt1',
    name: 'Show',
    poster: 'https://img/show.jpg',
    positionSec: 2710,
    durationSec: 2710,
    watched: true,
    updatedAt: 100,
  })
})

test('marking watched falls back to the runtime, then to an unknown length, never a made-up one', () => {
  assert.equal(watchedRow(episode, undefined, 2880, 100).durationSec, 2880)
  const unknown = watchedRow(episode, undefined, null, 100)
  assert.equal(unknown.durationSec, 0)
  assert.equal(unknown.positionSec, 0)
  assert.equal(unknown.watched, true)
})

test('a mark is always newer than the row it replaces', () => {
  // Another device's clock ran ahead: the mark must still win last-write-wins.
  const ahead = row({ videoId: 'tt1:1:3', updatedAt: 500 })
  assert.equal(watchedRow(episode, ahead, null, 100).updatedAt, 501)
  assert.equal(unwatchedRow(episode, ahead, 100).updatedAt, 501)
})

test('display fields are never blanked', () => {
  const existing = row({ videoId: 'tt1:1:3', name: 'Old name', poster: 'https://img/old.jpg' })
  const bare = { videoId: 'tt1:1:3', itemId: 'series:tt1', name: '' }
  const marked = unwatchedRow(bare, existing, 100)
  assert.equal(marked.name, 'Old name')
  assert.equal(marked.poster, 'https://img/old.jpg')
  assert.equal('name' in unwatchedRow(bare, undefined, 100), false)
})

test('marking unwatched records no viewing at all', () => {
  const marked = unwatchedRow(episode, row({ videoId: 'tt1:1:3', watched: true }), 100)
  assert.equal(marked.positionSec, 0)
  assert.equal(marked.durationSec, 0)
  assert.equal(marked.watched, false)
  assert.equal(hasWatchProgress(marked), false)
  assert.equal(hasWatchProgress(row({ videoId: 'x' })), true)
  assert.equal(hasWatchProgress(row({ videoId: 'x', positionSec: 0, watched: true })), true)
})

const nextOf = (videoId: string): NextEpisodeLookup =>
  videoId === 'tt1:1:3' ? { state: 'resolved', videoId: 'tt1:1:4' } : { state: 'resolved', videoId: 'tt1:1:3' }

test('the continue shelf moves on when the episode is marked watched', () => {
  const inProgress = row({ videoId: 'tt1:1:3', updatedAt: 10 })
  const before = buildContinueShelf([inProgress], [], (request) => nextOf(request.videoId))
  assert.equal(before.cards[0]?.kind, 'resume')

  const after = buildContinueShelf([watchedRow(episode, inProgress, null, 20)], [], (request) =>
    nextOf(request.videoId),
  )
  assert.equal(after.cards[0]?.kind, 'next')
  assert.equal(after.cards[0]?.videoId, 'tt1:1:4')
})

test('an unwatched mark reads as a missing row: the show falls back to what came before', () => {
  const previous = row({ videoId: 'tt1:1:2', watched: true, positionSec: 3000, updatedAt: 10 })
  const latest = row({ videoId: 'tt1:1:3', watched: true, positionSec: 3000, updatedAt: 20 })
  const shelf = buildContinueShelf([previous, unwatchedRow(episode, latest, 30)], [], (request) =>
    nextOf(request.videoId),
  )
  assert.equal(shelf.cards.length, 1)
  assert.equal(shelf.cards[0]?.kind, 'next')
  assert.equal(shelf.cards[0]?.videoId, 'tt1:1:3')
})
