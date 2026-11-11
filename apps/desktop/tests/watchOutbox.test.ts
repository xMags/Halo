import assert from 'node:assert/strict'
import test from 'node:test'
import type { WatchState } from '@halo/core'
import { mergePending, withoutSettled } from '../src/player/watchOutbox'

const row = (videoId: string, positionSec: number, updatedAt: number): WatchState => ({
  videoId,
  itemId: 'tt1',
  positionSec,
  durationSec: 3000,
  watched: false,
  updatedAt,
})

test('staging keeps one row per video, the newest', () => {
  const pending = mergePending([row('a', 10, 100), row('b', 20, 100)], [row('a', 30, 200), row('b', 5, 50)])
  assert.deepEqual(
    pending.map((state) => [state.videoId, state.positionSec]),
    [
      ['b', 20],
      ['a', 30],
    ],
  )
})

test('a sent row is removed, and one staged again since is kept', () => {
  const sent = [row('a', 10, 100), row('b', 20, 100)]
  const pending = [row('a', 12, 150), row('b', 20, 100), row('c', 1, 90)]
  assert.deepEqual(
    withoutSettled(pending, sent).map((state) => state.videoId),
    ['a', 'c'],
  )
})

test('the outbox is bounded, dropping the oldest rows first', () => {
  const many = Array.from({ length: 250 }, (_, index) => row(`v${index}`, 10, index + 1))
  const pending = mergePending([], many)
  assert.equal(pending.length, 200)
  assert.equal(pending[0]!.videoId, 'v50')
})
