import assert from 'node:assert/strict'
import test from 'node:test'
import type { DownloadView } from '../src/downloads/downloadsStore.ts'
import {
  addedFact,
  addedLabel,
  bytesOfTotal,
  CHART_FLOOR,
  chartBars,
  CHART_CEILING,
  connectionsLabel,
  filterCounts,
  freeLine,
  matchesFilter,
  peakText,
  queueLine,
  rateFigure,
  rateNote,
  rateText,
  rateTone,
  readyHeadline,
  resolveSelection,
  rowOf,
  splitBytes,
  storageFigures,
  summaryLine,
  totalConnections,
} from '../src/downloads/downloadsPresentation.ts'

function view(patch: Partial<DownloadView> & { media?: Partial<DownloadView['media']> } = {}): DownloadView {
  const { media, ...rest } = patch
  return {
    job_id: 'job',
    media: {
      video_id: 'tt1:1:2',
      item_id: 'tt1',
      media_type: 'series',
      meta_id: 'tt1',
      title: 'Cat’s in the Bag',
      show_name: 'Breaking Bad',
      episode_label: 'S01E02',
      poster: 'https://img.test/poster.jpg',
      stream_name: 'Torrentio 4K',
      stream_title: 'Breaking.Bad.S01E02.2160p.HDR.HEVC',
      ...media,
    },
    file_name: 'Breaking.Bad.S01E02-0123456789ab-fedcba98.mkv',
    status: 'downloading',
    total_bytes: 4 * 1024 ** 3,
    downloaded_bytes: 1024 ** 3,
    bytes_per_second: 12 * 1024 ** 2,
    active_connections: 0,
    source_fingerprint: 'f',
    explicit_pause: false,
    created_at: new Date(2026, 8, 5, 12).getTime(),
    updated_at: 0,
    ...rest,
  }
}

test('a downloading episode row reads like the native row', () => {
  const row = rowOf(view())
  assert.equal(row.tag, 'S01E02')
  assert.equal(row.name, 'Breaking Bad')
  assert.equal(row.sub, 'Cat’s in the Bag')
  assert.equal(row.lead, '12.0 MB/s')
  assert.equal(row.tone, 'normal')
  assert.equal(row.downloadedLine, '1.0 GB / 4.0 GB')
  assert.equal(row.progress, 0.25)
  assert.deepEqual(row.medal, { plate: '4K', tag: 'HDR', finish: 'gold' })
  assert.equal(row.subsChip, 'NO SUBS')
  assert.equal(row.subsMuted, true)
  assert.equal(row.subs, 'None')
  assert.deepEqual(row.transferMeta, ['1.0 of 4.0 GB', '25%', '5m left'])
  assert.equal(row.addedLabel, 'ADDED 05 SEP')
  assert.equal(addedFact(row.addedLabel), '05 SEP')
  assert.equal(row.canPause, true)
  assert.equal(row.canResume, false)
})

test('a movie row tags its type and drops a sub-line that repeats the name', () => {
  const row = rowOf(
    view({
      file_name: 'Heat',
      media: { media_type: 'movie', show_name: undefined, episode_label: undefined, title: 'Heat' },
    }),
  )
  assert.equal(row.tag, 'MOVIE')
  assert.equal(row.name, 'Heat')
  assert.equal(row.sub, '')
  assert.equal(row.episodeLabel, undefined)
  assert.equal(row.cardTitle, 'Heat')
  assert.equal(row.cardSub, '')
  assert.equal(row.paneMeta, 'MOVIE')
})

test('a ready episode card names the show and episode, and its quality, size and subtitle', () => {
  const row = rowOf(view({ status: 'done', downloaded_bytes: 4 * 1024 ** 3, subtitle_lang: 'eng' }))
  assert.equal(row.cardTitle, 'Breaking Bad')
  assert.equal(row.cardSub, 'Cat’s in the Bag')
  assert.equal(row.episodeLabel, 'S01E02')
  assert.equal(row.paneMeta, 'S01E02 · Cat’s in the Bag')
  assert.equal(row.cardMeta, 'HEVC · 4.0 GB · ENG')
  assert.deepEqual(row.qualityBadge, { label: '4K HDR', gold: true })
  assert.equal(row.subs, 'English')
})

test('a card leaves out what the release name did not state', () => {
  const row = rowOf(
    view({
      status: 'done',
      file_name: 'video.mkv',
      media: { stream_name: 'Addon', stream_title: 'Some upload' },
    }),
  )
  assert.equal(row.qualityBadge, null)
  assert.equal(row.cardMeta, '4.0 GB · NO SUBS')
})

test('the queue line says why a transfer is not moving', () => {
  assert.deepEqual(rowOf(view({ status: 'paused' })).transferMeta, ['1.0 of 4.0 GB', '25%', 'Paused by you'])
  assert.deepEqual(rowOf(view({ bytes_per_second: 0 })).transferMeta, ['1.0 of 4.0 GB', '25%', 'Waiting for the source'])
  assert.deepEqual(rowOf(view({ status: 'failed', failure: 'network' })).transferMeta, [
    'The download could not continue after repeated network failures.',
  ])
  assert.deepEqual(rowOf(view({ total_bytes: 0, media: { video_size: undefined } })).transferMeta, [
    '1.0 GB so far',
  ])
  assert.equal(bytesOfTotal(512 * 1024 ** 2, 4 * 1024 ** 3), '512.0 MB of 4.0 GB')
})

test('states choose the lead line and its tone', () => {
  assert.equal(rowOf(view({ bytes_per_second: 0 })).lead, 'WAITING')
  assert.equal(rowOf(view({ status: 'queued' })).lead, 'WAITING FOR THE CURRENT TRANSFER')
  const paused = rowOf(view({ status: 'paused' }))
  assert.equal(paused.lead, 'PAUSED BY YOU')
  assert.equal(paused.tone, 'caution')
  const failed = rowOf(view({ status: 'failed', failure: 'network' }))
  assert.equal(failed.lead, 'The download could not continue after repeated network failures.')
  assert.equal(failed.tone, 'critical')
  assert.equal(rowOf(view({ status: 'done' })).lead, 'READY FOR OFFLINE PLAYBACK')
})

test('a retryable failure offers resume and retry; an expired one only a new source', () => {
  const retryable = rowOf(view({ status: 'failed', failure: 'server_unavailable' }))
  assert.equal(retryable.canResume, true)
  assert.equal(retryable.canRetry, true)
  assert.equal(retryable.requiresNewSource, false)
  const expired = rowOf(view({ status: 'failed', failure: 'missing_file' }))
  assert.equal(expired.canResume, false)
  assert.equal(expired.canRetry, false)
  assert.equal(expired.requiresNewSource, true)
  assert.equal(expired.canPause, false)
})

test('a finished download shows one size, full progress and its subtitle', () => {
  const row = rowOf(view({ status: 'done', downloaded_bytes: 4 * 1024 ** 3, subtitle_lang: 'en' }))
  assert.equal(row.state, 'ondisk')
  assert.equal(row.downloadedLine, '4.0 GB')
  assert.equal(row.progress, 1)
  assert.equal(row.subsChip, 'SUB EN')
  assert.equal(row.subs, 'en')
})

test('an unknown size says so, with what has arrived so far', () => {
  const row = rowOf(view({ total_bytes: 0, media: { video_size: undefined } }))
  assert.equal(row.downloadedLine, 'SIZE UNKNOWN · 1.0 GB')
  assert.equal(row.progress, 0)
  assert.equal(rowOf(view({ status: 'done', total_bytes: 0, media: { video_size: undefined } })).downloadedLine, 'SIZE UNKNOWN')
})

test('row artwork prefers the landscape still and falls back to the poster', () => {
  assert.equal(rowOf(view()).rowArtwork, 'https://img.test/poster.jpg')
  assert.equal(rowOf(view({ media: { landscape_artwork: 'https://img.test/still.jpg' } })).rowArtwork, 'https://img.test/still.jpg')
})

test('the Active filter includes failed transfers and counts follow the native view model', () => {
  const rows = [
    rowOf(view({ job_id: 'a' })),
    rowOf(view({ job_id: 'b', status: 'failed', failure: 'network' })),
    rowOf(view({ job_id: 'c', status: 'done' })),
  ]
  const transfers = rows.filter((row) => row.state !== 'ondisk')
  const ready = rows.filter((row) => row.state === 'ondisk')
  assert.deepEqual(filterCounts(transfers, ready), { all: 3, active: 2, ready: 1, failed: 1 })
  assert.equal(matchesFilter(rows[1]!, 'active'), true)
  assert.equal(matchesFilter(rows[2]!, 'active'), false)
})

test('selection keeps a visible pick, else prefers the first ready download', () => {
  const transfer = rowOf(view({ job_id: 't' }))
  const ready = rowOf(view({ job_id: 'r', status: 'done' }))
  assert.equal(resolveSelection('t', [ready], [transfer])?.id, 't')
  assert.equal(resolveSelection('gone', [ready], [transfer])?.id, 'r')
  assert.equal(resolveSelection(null, [], [transfer])?.id, 't')
  assert.equal(resolveSelection(null, [], []), null)
})

test('the rate reads paused, idle or moving', () => {
  assert.equal(rateText(12.345, false), '12.3 MB/s')
  assert.equal(rateText(12.345, true), 'PAUSED')
  assert.deepEqual(rateFigure(0, false), { value: '0.0', unit: 'MB/s' })
  assert.deepEqual(rateFigure(3, true), { value: 'PAUSED', unit: '' })
  assert.equal(rateTone(0, false), 'idle')
  assert.equal(rateTone(1, false), 'normal')
  assert.equal(rateTone(1, true), 'paused')
  assert.equal(queueLine(0), 'No active transfers')
  assert.equal(queueLine(1), '1 active transfer')
  assert.equal(queueLine(3), '3 active transfers')
})

test('the chart is 28 slots against the window peak, floored, with the newest four lit', () => {
  const bars = chartBars([1, 2, 4])
  assert.equal(bars.length, 28)
  assert.deepEqual(bars.slice(0, 25).map((bar) => bar.fraction), new Array(25).fill(CHART_FLOOR))
  assert.equal(bars[27]!.fraction, CHART_CEILING)
  assert.equal(bars[26]!.fraction, 0.5 * CHART_CEILING)
  assert.equal(bars[25]!.fraction, 0.25 * CHART_CEILING)
  assert.deepEqual(bars.slice(24).map((bar) => bar.opacity), [1, 1, 1, 1])
  assert.equal(bars[0]!.opacity, 0.35)
  assert.ok(Math.abs(bars[23]!.opacity - (0.35 + (23 / 28) * 0.45)) < 1e-9)
  // An idle minute rests every bar on the floor; a longer history keeps the newest slots.
  assert.ok(chartBars([]).every((bar) => bar.fraction === CHART_FLOOR))
  const long = chartBars([...new Array(30).fill(0), 8, ...new Array(27).fill(2)])
  assert.equal(long[0]!.fraction, CHART_CEILING)
  assert.equal(long[27]!.fraction, 0.25 * CHART_CEILING)
  assert.equal(peakText([0.4, 3.25, 1]), 'PEAK 3.3 MB/S')
  assert.equal(peakText([]), 'PEAK 0.0 MB/S')
})

test('the summary counts every state it has, and is empty with nothing', () => {
  const rows = [
    rowOf(view({ job_id: 'a' })),
    rowOf(view({ job_id: 'b', status: 'queued' })),
    rowOf(view({ job_id: 'c', status: 'done' })),
    rowOf(view({ job_id: 'd', status: 'done' })),
    rowOf(view({ job_id: 'e', status: 'done' })),
  ]
  assert.equal(summaryLine(rows), '1 downloading · 1 waiting · 3 ready')
  assert.equal(summaryLine([rowOf(view({ status: 'failed', failure: 'network' }))]), '1 failed')
  assert.equal(summaryLine([]), '')
  assert.equal(readyHeadline(3, 53.4 * 1024 ** 3), '3 · 53.4 GB')
})

test('connections are summed over running transfers only', () => {
  const items = [
    view({ job_id: 'a', active_connections: 5 }),
    view({ job_id: 'b', status: 'paused', active_connections: 3 }),
    view({ job_id: 'c', status: 'done' }),
  ]
  assert.equal(totalConnections(items), 5)
  assert.equal(connectionsLabel(1), '1 connection')
  assert.equal(connectionsLabel(6), '6 connections')
  assert.deepEqual(rateNote(items), { kind: 'connections', text: '5 connections' })
  assert.deepEqual(rateNote([view({ active_connections: 0 })]), { kind: 'note', text: 'Connecting' })
  assert.deepEqual(rateNote([view({ status: 'paused' })]), { kind: 'note', text: 'No active transfers' })
  assert.deepEqual(rateNote([view({ status: 'done' })]), { kind: 'note', text: 'Idle' })
  assert.deepEqual(rateNote([]), { kind: 'note', text: 'Idle' })
})

test('storage counts finished files by their agreed size and transfers by what arrived', () => {
  const figures = storageFigures(
    [
      view({ status: 'done', total_bytes: 600, downloaded_bytes: 600 }),
      view({ status: 'failed', total_bytes: 1000, downloaded_bytes: 200 }),
    ],
    200,
  )
  assert.equal(figures.storedBytes, 600)
  assert.equal(figures.inFlightBytes, 200)
  assert.equal(figures.usedFraction, 0.8)
  assert.equal(figures.storedFraction, 0.6)
  assert.equal(freeLine(undefined), 'free space unknown')
  assert.equal(freeLine(1024 ** 3), '1.0 GB free')
  assert.deepEqual(splitBytes(55.5 * 1024 ** 3), { value: '55.5', unit: 'GB' })
  assert.deepEqual(splitBytes(0), { value: '0', unit: 'B' })
  assert.equal(addedLabel(0), 'ADDED UNKNOWN')
})
