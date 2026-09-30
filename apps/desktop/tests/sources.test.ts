import assert from 'node:assert/strict'
import test from 'node:test'
import type { Stream } from '@halo/core'
import {
  detailsFor,
  formatStreamSize,
  metaLineFor,
  ordinal,
  pickHeadline,
  reasonFor,
  sanitizeAddonName,
  statusLabel,
  type DeviceContext,
} from '../src/sources/sourcePresentation.ts'
import {
  buildListing,
  buildResolveSummary,
  filterCounts,
  resolveSources,
  sameReleaseFile,
  selectionStops,
  type AddonStreamGroup,
} from '../src/sources/sourcesModel.ts'

const device: DeviceContext = {
  preferredSubtitleLanguage: null,
  lineMbps: 0,
  durationSeconds: 0,
  watchedSeconds: 0,
  watchedDurationSeconds: 0,
}

function stream(title: string, extra: Partial<Stream> = {}): Stream {
  return { url: `https://cdn.test/${encodeURIComponent(title)}`, title, ...extra }
}

function resolve(groups: AddonStreamGroup[], options: Partial<Parameters<typeof resolveSources>[0]> = {}) {
  return resolveSources({ groups, errors: [], localFiles: [], durationSeconds: 0, elapsedSeconds: 1.23, ...options })
}

test('sizes print as binary gigabytes, two decimals under ten', () => {
  assert.equal(formatStreamSize(8.42 * 1024 ** 3), '8.42 GB')
  assert.equal(formatStreamSize(34.2 * 1024 ** 3), '34.2 GB')
  assert.equal(formatStreamSize(null), 'UNKNOWN')
})

test('ordinals follow English, including the teens', () => {
  assert.deepEqual([1, 2, 3, 4, 11, 12, 13, 21, 22, 112].map(ordinal), [
    '1st', '2nd', '3rd', '4th', '11th', '12th', '13th', '21st', '22nd', '112th',
  ])
})

test('an addon that said nothing about caching reads as a possible wait', () => {
  assert.equal(statusLabel('instant'), 'Plays instantly')
  assert.equal(statusLabel('unknown'), 'May download before it plays')
  assert.equal(statusLabel('uncached'), 'Downloads before it plays')
})

test('addon names that look like URLs, paths or tokens are withheld', () => {
  assert.equal(sanitizeAddonName('Torrentio'), 'Torrentio')
  assert.equal(sanitizeAddonName('https://evil.test/manifest.json'), 'An addon')
  assert.equal(sanitizeAddonName('a'.repeat(40)), 'An addon')
  assert.equal(sanitizeAddonName(undefined), 'An addon')
  assert.equal(sanitizeAddonName('  Comet\u0007 '), 'Comet')
})

test('local files rank first, and a matching addon stream folds into them', () => {
  const result = resolve(
    [{ addonId: 'a', addonName: 'Addon', streams: [
      stream('Show.S01E01.2160p.HEVC\n⚡ Cached', { behaviorHints: { filename: 'Show.S01E01.2160p.mkv' }, subtitles: [{ id: '1', url: 'u', lang: 'eng' }] }),
      stream('Show.S01E01.1080p\n4.1 GB'),
    ] }],
    { localFiles: [{ jobId: 'job1', releaseName: 'show.s01e01.2160p.MKV', sizeBytes: 5 * 1024 ** 3 }] },
  )
  assert.deepEqual(result.pool.map((entry) => entry.key), ['local:job1', 'a:1'])
  assert.equal(result.pool[0]!.status, 'ondisk')
  assert.equal(result.pool[0]!.provider, 'On this device')
  assert.deepEqual(result.pool[0]!.subtitleLanguages, ['eng'])
  assert.equal(result.records.get('local:job1')!.stream?.url, 'https://cdn.test/Show.S01E01.2160p.HEVC%0A%E2%9A%A1%20Cached')
  assert.equal(result.summary, '1 source from 1 provider, found in 1.2 seconds, plus 1 saved on this device')
  assert.deepEqual(result.providers.map((row) => `${row.name}: ${row.value}`), [
    'On this device: 1 file',
    'Addon: 1 source',
  ])
})

test('release names match on the leaf, case-insensitively, and never when empty', () => {
  assert.equal(sameReleaseFile('C:\\dl\\Movie.2024.mkv', 'movie.2024.MKV'), true)
  assert.equal(sameReleaseFile('', ''), false)
  assert.equal(sameReleaseFile('Movie.mkv', 'Movie.mp4'), false)
})

test('the summary names the ratio only when a provider failed', () => {
  assert.equal(buildResolveSummary(5, 3, 1, 0, 2.04), '5 sources from 3 of 4 providers, found in 2.0 seconds')
  assert.equal(buildResolveSummary(0, 0, 0, 0, 0), 'No provider answered')
  assert.equal(buildResolveSummary(0, 0, 0, 2, 0), '2 files saved on this device')
})

test('failed providers carry their failure copy into the footer and banner', () => {
  const result = resolveSources({
    groups: [],
    errors: [{ id: 'x', name: 'Torrentio', code: 'timeout' }],
    localFiles: [],
    durationSeconds: 0,
    elapsedSeconds: 0,
  })
  assert.equal(result.firstFailure, 'Torrentio did not answer in time')
  assert.deepEqual(result.providers, [{ name: 'Torrentio', value: 'did not answer in time', answered: false }])
})

test('the listing groups by provider, lifts the pick, and hides sources that must download first', () => {
  const result = resolve([
    { addonId: 'a', addonName: 'Alpha', streams: [
      stream('A.2160p [RD+] 20 GB'),
      stream('A.720p 1 GB'),
    ] },
    { addonId: 'b', addonName: 'Beta', streams: [
      stream('B.1080p [RD+] 6 GB'),
      stream('B.1080p [RD download] 4 GB'),
    ] },
  ])
  const listing = buildListing(result, { filter: 'all', sort: 'Recommended', coldRevealed: false, device })
  assert.equal(listing.pick?.entry.key, 'a:0')
  assert.equal(listing.pick?.headline, 'The best picture here that starts with no waiting.')
  assert.deepEqual(
    listing.items.map((item) => (item.kind === 'row' ? item.entry.key : item.kind === 'header' ? `[${item.name} ${item.count}]` : item.label)),
    ['[Beta 1 source]', 'b:0', 'Show 2 sources that need downloading first'],
  )
  assert.deepEqual(selectionStops(listing), ['a:0', 'b:0'])

  const revealed = buildListing(result, { filter: 'all', sort: 'Recommended', coldRevealed: true, device })
  assert.deepEqual(
    revealed.items.flatMap((item) => (item.kind === 'header' ? [`${item.name} ${item.count}`] : [])),
    ['Beta 2 sources', 'Alpha 1 source'],
  )

  // Any other sort drops the pick block and shows everything.
  const bySize = buildListing(result, { filter: 'all', sort: 'Smallest file', coldRevealed: false, device })
  assert.equal(bySize.pick, null)
  assert.equal(bySize.items.filter((item) => item.kind === 'row').length, 4)
  assert.deepEqual(filterCounts(result.pool), { all: 4, playsNow: 2, ultraHd: 1, fullHd: 2, hd: 1 })
})

test('row reasons compare against the pick', () => {
  const result = resolve([{ addonId: 'a', addonName: 'Alpha', streams: [
    stream('A.2160p HDR Atmos [RD+] 20 GB'),
    stream('A.2160p DDP 2.0 [RD+] 18 GB'),
    stream('A.1080p Atmos HDR [RD+] 2 GB'),
  ] }])
  const [pick, stereo, lower] = result.pool
  assert.equal(reasonFor(stereo!, pick!, false), '2nd · stereo only')
  assert.equal(reasonFor(lower!, pick!, true), '3rd · lower picture')
  assert.equal(reasonFor(lower!, null, true), '3rd')
  assert.equal(pickHeadline(pick!, false), 'The best picture here that starts with no waiting.')
})

test('the meta strip and subtitle plate follow the preferred language', () => {
  const [entry] = resolve([{ addonId: 'a', addonName: 'Alpha', streams: [
    stream('A.2160p DV DDP 5.1 ENG JPN 8.42 GB', { subtitles: [{ id: '1', url: 'u', lang: 'eng' }, { id: '2', url: 'u', lang: 'ger' }] }),
  ] }]).pool
  assert.deepEqual(metaLineFor(entry!, true, null), { line: '8.42 GB · DOLBY VISION · 5.1 · EN/JA', subtitles: '2 SUBS', hasSubtitles: true })
  assert.equal(metaLineFor(entry!, false, null).line, '8.42 GB · 5.1 · EN/JA')
  assert.deepEqual(metaLineFor(entry!, false, 'eng').subtitles, 'SUB EN')
  assert.deepEqual(metaLineFor(entry!, false, 'spa'), { line: '8.42 GB · 5.1 · EN/JA', subtitles: 'NO ES SUBS', hasSubtitles: false })
})

test('details spell out what is and is not known', () => {
  const [entry] = resolve(
    [{ addonId: 'a', addonName: 'Alpha', streams: [stream('A.1080p HEVC 10bit DDP 5.1 [RD+] 3 GB')] }],
    { durationSeconds: 3600 },
  ).pool
  const details = detailsFor(entry!, device, 0)
  assert.equal(details.resolution, '1920 × 1080')
  assert.equal(details.picture, 'SDR')
  assert.equal(details.codec, 'H.265 10-bit')
  assert.equal(details.sound, 'Surround 5.1')
  assert.equal(details.channels, '5.1')
  assert.deepEqual(details.audioLanguages, [{ label: 'NOT LISTED', muted: true }])
  assert.deepEqual(details.subtitles, [{ label: 'NONE INCLUDED', muted: true }])
  assert.equal(details.cacheLabel, 'Already cached for you')
  assert.equal(details.mbpsLabel, '7 Mbps')
  assert.equal(details.lineLabel, 'HEAVIEST HERE 7 MBPS')
  assert.equal(details.headroom, 'Line speed has not been measured yet')
  assert.equal(details.meterFraction, 1)
})
