import assert from 'node:assert/strict'
import test from 'node:test'
import {
  downloadEtaSeconds,
  downloadFailureMessage,
  downloadProgress,
  downloadStatusLabel,
  formatDownloadBytes,
  formatDownloadEta,
  isNewLinePeak,
  isPausedAll,
  nextDownloadedEpisode,
  opaqueDownloadOwner,
  planPauseAll,
  planResumeAll,
  pushThroughputSample,
  requiresNewSource,
  selectLandscapeArtwork,
  THROUGHPUT_SLOTS,
} from '../src/downloads/downloadsLogic.ts'

test('download status labels distinguish active, ready, and source expiry', () => {
  assert.equal(downloadStatusLabel({ status: 'downloading' }), 'Downloading')
  assert.equal(downloadStatusLabel({ status: 'done' }), 'Ready offline')
  assert.equal(downloadStatusLabel({ status: 'failed', failure: 'source_expired' }), 'Choose source again')
})

test('download progress is bounded and unknown totals stay indeterminate', () => {
  assert.equal(downloadProgress({ status: 'downloading', total_bytes: 0, downloaded_bytes: 12 }), null)
  assert.equal(downloadProgress({ status: 'downloading', total_bytes: 100, downloaded_bytes: 150 }), 1)
  assert.equal(downloadProgress({ status: 'downloading', total_bytes: 100, downloaded_bytes: 25 }), 0.25)
})

test('download byte labels use stable binary units', () => {
  assert.equal(formatDownloadBytes(0), '0 B')
  assert.equal(formatDownloadBytes(1024), '1.0 KB')
  assert.equal(formatDownloadBytes(1024 * 1024), '1.0 MB')
})

test('download failures and ETA remain sanitized and deterministic', () => {
  assert.equal(downloadFailureMessage('source_expired'), 'This source expired. Choose a source again to continue.')
  assert.equal(downloadEtaSeconds({ total_bytes: 1000, downloaded_bytes: 400, bytes_per_second: 100 }), 6)
  assert.equal(downloadEtaSeconds({ total_bytes: 0, downloaded_bytes: 0, bytes_per_second: 100 }), null)
  assert.equal(formatDownloadEta(3660), '1h 1m left')
})

test('offline continuation selects only the next completed episode in the same title', () => {
  const entries = [
    { videoId: 'e3', itemId: 'series:1', episodeLabel: 'S01E03', status: 'done' as const },
    { videoId: 'e1', itemId: 'series:1', episodeLabel: 'S01E01', status: 'done' as const },
    { videoId: 'other', itemId: 'series:2', episodeLabel: 'S01E02', status: 'done' as const },
    { videoId: 'e2', itemId: 'series:1', episodeLabel: 'S01E02', status: 'done' as const },
  ]
  assert.equal(nextDownloadedEpisode('e1', entries)?.videoId, 'e2')
  assert.equal(nextDownloadedEpisode('e3', entries), null)
})

test('download ownership is stable without persisting server or user identifiers', async () => {
  const owner = await opaqueDownloadOwner('https://halo.example/', 'internal-user-id')
  assert.equal(owner, await opaqueDownloadOwner('https://halo.example', 'internal-user-id'))
  assert.equal(owner.length, 64)
  assert.equal(owner.includes('halo.example'), false)
  assert.equal(owner.includes('internal-user-id'), false)
})

test('offline continuation reads separated and three-digit episode labels', () => {
  const entries = [
    { videoId: 'e99', itemId: 'show', episodeLabel: 'S01E99', status: 'done' as const },
    { videoId: 'e100', itemId: 'show', episodeLabel: 's01.e100', status: 'done' as const },
    { videoId: 'e101', itemId: 'show', episodeLabel: 'S01 E101', status: 'failed' as const },
  ]
  assert.equal(nextDownloadedEpisode('e99', entries)?.videoId, 'e100')
  assert.equal(nextDownloadedEpisode('e100', entries), null)
})

test('a missing file needs a new source like an expired one, a network failure does not', () => {
  assert.equal(requiresNewSource('missing_file'), true)
  assert.equal(requiresNewSource('source_expired'), true)
  assert.equal(requiresNewSource('network'), false)
  assert.equal(requiresNewSource(undefined), false)
  assert.equal(downloadStatusLabel({ status: 'failed', failure: 'missing_file' }), 'Choose source again')
})

test('the throughput window keeps the newest samples and never stores a negative', () => {
  let samples: number[] = []
  for (let second = 1; second <= THROUGHPUT_SLOTS + 5; second += 1) {
    samples = pushThroughputSample(samples, second)
  }
  assert.equal(samples.length, THROUGHPUT_SLOTS)
  assert.equal(samples[0], 6)
  assert.equal(samples.at(-1), THROUGHPUT_SLOTS + 5)
  assert.equal(pushThroughputSample([], -3)[0], 0)
  assert.equal(pushThroughputSample([], Number.NaN)[0], 0)
})

test('resume all restarts only what pause all stopped', () => {
  const records = [
    { job_id: 'running', status: 'downloading' as const },
    { job_id: 'waiting', status: 'queued' as const },
    { job_id: 'mine', status: 'paused' as const },
    { job_id: 'done', status: 'done' as const },
  ]
  const plan = planPauseAll(records, new Set())
  assert.deepEqual(plan.pause, ['running', 'waiting'])
  assert.deepEqual([...plan.remembered], ['running', 'waiting'])

  const afterPause = [
    { job_id: 'running', status: 'paused' as const },
    { job_id: 'waiting', status: 'paused' as const },
    { job_id: 'mine', status: 'paused' as const },
    { job_id: 'done', status: 'done' as const },
  ]
  assert.equal(isPausedAll(afterPause, plan.remembered), true)
  assert.deepEqual(planResumeAll(afterPause, plan.remembered), ['running', 'waiting'])
})

test('the page is not paused while anything moves or nothing was bulk paused', () => {
  const paused = [{ job_id: 'a', status: 'paused' as const }]
  assert.equal(isPausedAll(paused, new Set()), false)
  assert.equal(isPausedAll([...paused, { job_id: 'b', status: 'queued' as const }], new Set(['a'])), false)
  // The remembered transfer was resumed or removed by hand since.
  assert.equal(isPausedAll([{ job_id: 'a', status: 'downloading' as const }], new Set(['a'])), false)
  assert.equal(isPausedAll([], new Set(['gone'])), false)
})

test('a second pause all keeps remembering what the first one stopped', () => {
  const records = [
    { job_id: 'earlier', status: 'paused' as const },
    { job_id: 'new', status: 'downloading' as const },
  ]
  const plan = planPauseAll(records, new Set(['earlier']))
  assert.deepEqual(plan.pause, ['new'])
  assert.deepEqual([...plan.remembered].sort(), ['earlier', 'new'])
})

test('row artwork prefers the episode still, then the backdrop, then nothing', () => {
  const meta = {
    background: 'https://img.test/backdrop.jpg',
    videos: [{ id: 'tt1:1:2', thumbnail: 'https://img.test/still.jpg' }, { id: 'tt1:1:3' }],
  }
  assert.equal(selectLandscapeArtwork('tt1:1:2', meta), 'https://img.test/still.jpg')
  assert.equal(selectLandscapeArtwork('tt1:1:3', meta), 'https://img.test/backdrop.jpg')
  assert.equal(selectLandscapeArtwork('tt1', {}), null)
})

test('only a clearly faster line reading is recorded', () => {
  assert.equal(isNewLinePeak(100, 0), true)
  assert.equal(isNewLinePeak(105, 100), false)
  assert.equal(isNewLinePeak(111, 100), true)
  assert.equal(isNewLinePeak(0, 0), false)
  assert.equal(isNewLinePeak(Number.POSITIVE_INFINITY, 0), false)
})
