import assert from 'node:assert/strict'
import test from 'node:test'
import type { MetaVideo, WatchState } from '@halo/core'
import {
  earlierEpisodes,
  hasWatchProgress,
  unwatchedRow,
  watchedRow,
  watchedRowsInOrder,
} from '../src/browse/watchMarks.ts'
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

const ep = (season: number | undefined, episode: number | undefined): MetaVideo => ({
  id: `tt1:${season ?? 'x'}:${episode ?? 'x'}`,
  ...(season != null ? { season } : {}),
  ...(episode != null ? { episode } : {}),
})

test('earlier episodes run through every earlier season, first to last, and skip specials', () => {
  const videos = [ep(2, 2), ep(0, 1), ep(1, 2), ep(2, 1), ep(1, 1), ep(2, 3), ep(3, 1)]
  const ids = earlierEpisodes(videos, ep(2, 3)).map((video) => video.id)
  assert.deepEqual(ids, ['tt1:1:1', 'tt1:1:2', 'tt1:2:1', 'tt1:2:2'])
  assert.deepEqual(earlierEpisodes(videos, ep(1, 1)), [])
})

test('before a special only earlier specials count; unnumbered videos never do', () => {
  const videos = [ep(0, 1), ep(0, 2), ep(1, 1), ep(undefined, 3), ep(1, undefined)]
  assert.deepEqual(earlierEpisodes(videos, ep(0, 3)).map((video) => video.id), ['tt1:0:1', 'tt1:0:2'])
  // An addon without seasons (kitsu) reads as all season 0: the lower numbers count.
  assert.deepEqual(earlierEpisodes(videos, ep(undefined, 3)).map((video) => video.id), ['tt1:0:1', 'tt1:0:2'])
  assert.deepEqual(earlierEpisodes(videos, ep(1, undefined)), [])
})

test('a batch of marks is stamped in order, so the shelf lands after the last of them', () => {
  const targets = ['tt1:1:1', 'tt1:1:2', 'tt1:1:3'].map((videoId) => ({ ...episode, videoId }))
  // The middle episode's row carries a clock that ran ahead of this device.
  const existing = new Map([['tt1:1:2', row({ videoId: 'tt1:1:2', updatedAt: 500 })]])
  const rows = watchedRowsInOrder(targets, existing, 2880, 100)
  assert.deepEqual(
    rows.map((mark) => mark.updatedAt),
    [100, 501, 502],
  )
  assert.equal(rows[1]?.durationSec, 3000)
  assert.equal(rows[0]?.durationSec, 2880)

  const shelf = buildContinueShelf(rows, [], (request) =>
    request.videoId === 'tt1:1:3' ? { state: 'resolved', videoId: 'tt1:1:4' } : { state: 'unknown' },
  )
  assert.equal(shelf.cards[0]?.videoId, 'tt1:1:4')
})
