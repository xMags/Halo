import assert from 'node:assert/strict'
import test from 'node:test'
import { downloadEtaSeconds, downloadFailureMessage, downloadProgress, downloadStatusLabel, formatDownloadBytes, formatDownloadEta, nextDownloadedEpisode, opaqueDownloadOwner } from '../src/downloadsLogic.ts'

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
