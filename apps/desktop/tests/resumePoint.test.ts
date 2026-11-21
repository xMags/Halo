import assert from 'node:assert/strict'
import test from 'node:test'
import type { WatchState } from '@halo/core'
import { newestWatchState, withinTimeout } from '../src/player/resumePoint.ts'

function row(videoId: string, positionSec: number, updatedAt: number, removedAt?: number): WatchState {
  return {
    videoId,
    itemId: `movie:${videoId}`,
    positionSec,
    durationSec: 100,
    watched: false,
    updatedAt,
    ...(removedAt === undefined ? {} : { removedAt }),
  }
}

test('a video resumes from the newest row any source has', () => {
  const fresh = [row('tt1', 80, 30)]
  const cached = [row('tt1', 20, 10)]
  const pending = [row('tt1', 50, 20)]
  assert.equal(newestWatchState('tt1', [fresh, cached, pending])?.positionSec, 80)
  // Progress made here and not sent yet beats an older server row.
  assert.equal(newestWatchState('tt1', [cached, [row('tt1', 60, 40)]])?.positionSec, 60)
})

test('a missing source, or one without the video, is skipped', () => {
  assert.equal(newestWatchState('tt1', [null, undefined, [row('tt2', 5, 1)]]), null)
  assert.equal(newestWatchState('tt1', [null, [row('tt1', 5, 1)]])?.positionSec, 5)
})

test('a newest row that removes the video starts it from the beginning', () => {
  assert.equal(newestWatchState('tt1', [[row('tt1', 80, 10)], [row('tt1', 80, 20, 20)]]), null)
})

test('a read that answers in time is used, and one that fails or stalls is not', async () => {
  assert.equal(await withinTimeout(Promise.resolve(7), 50), 7)
  assert.equal(await withinTimeout(Promise.reject(new Error('offline')), 50), null)
  assert.equal(await withinTimeout(new Promise<number>(() => undefined), 20), null)
})
