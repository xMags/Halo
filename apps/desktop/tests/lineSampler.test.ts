import assert from 'node:assert/strict'
import test from 'node:test'
import { LINE_SAMPLE_WINDOW, PlaybackLineSampler, SEEK_STALL_GRACE_MS } from '../src/player/lineSampler.ts'

/** 1 250 000 bytes a second is exactly 10 Mbps. */
const TEN_MBPS = 1_250_000

function filling(sampler: PlaybackLineSampler, bytesPerSecond: number, seconds: number): void {
  sampler.cacheSpeed(bytesPerSecond)
  for (let second = 0; second < seconds; second += 1) sampler.tick()
}

test('nothing is measured until the cache says it is filling', () => {
  const sampler = new PlaybackLineSampler()
  filling(sampler, TEN_MBPS, 10)
  assert.deepEqual(sampler.session(), { peakMbps: 0, stalled: false })
})

test('the reading is the best average of five filling seconds, not a single burst', () => {
  const sampler = new PlaybackLineSampler()
  sampler.cacheIdleChanged(false)
  // One burst second, then a steady 10 Mbps: the burst is averaged away.
  filling(sampler, 10 * TEN_MBPS, 1)
  filling(sampler, TEN_MBPS, LINE_SAMPLE_WINDOW - 2)
  assert.equal(sampler.session().peakMbps, 0, 'four samples are not yet a reading')
  filling(sampler, TEN_MBPS, 1)
  assert.equal(sampler.session().peakMbps, (100 + 4 * 10) / 5)
  filling(sampler, TEN_MBPS, LINE_SAMPLE_WINDOW)
  // The peak is kept even after the window has moved on to slower seconds.
  assert.equal(sampler.session().peakMbps, 28)
})

test('an idle cache breaks the run, so its stale speed never counts', () => {
  const sampler = new PlaybackLineSampler()
  sampler.cacheIdleChanged(false)
  filling(sampler, TEN_MBPS, LINE_SAMPLE_WINDOW - 1)
  sampler.cacheIdleChanged(true)
  filling(sampler, 50 * TEN_MBPS, 10)
  sampler.cacheIdleChanged(false)
  filling(sampler, TEN_MBPS, LINE_SAMPLE_WINDOW - 1)
  assert.equal(sampler.session().peakMbps, 0)
  filling(sampler, TEN_MBPS, 1)
  assert.equal(sampler.session().peakMbps, 10)
})

test('a broken speed reads as nothing arriving', () => {
  const sampler = new PlaybackLineSampler()
  sampler.cacheIdleChanged(false)
  filling(sampler, Number.NaN, LINE_SAMPLE_WINDOW)
  sampler.cacheSpeed(null)
  sampler.tick()
  assert.equal(sampler.session().peakMbps, 0)
})

test('a stall counts only after playback started and away from a seek', () => {
  const opening = new PlaybackLineSampler()
  // Buffering before the first frame is the stream opening.
  opening.pausedForCache(true, 1_000)
  assert.equal(opening.session().stalled, false)

  const seeking = new PlaybackLineSampler()
  seeking.playbackStarted()
  seeking.seeked(10_000)
  seeking.pausedForCache(true, 10_000 + SEEK_STALL_GRACE_MS - 1)
  assert.equal(seeking.session().stalled, false)
  seeking.pausedForCache(false, 10_000 + SEEK_STALL_GRACE_MS)
  assert.equal(seeking.session().stalled, false)
  seeking.pausedForCache(true, 10_000 + SEEK_STALL_GRACE_MS)
  assert.equal(seeking.session().stalled, true)

  const playing = new PlaybackLineSampler()
  playing.playbackStarted()
  playing.pausedForCache(true, 50_000)
  assert.equal(playing.session().stalled, true)
})
