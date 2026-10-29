import assert from 'node:assert/strict'
import test from 'node:test'
import type { Stream } from '@halo/core'
import {
  detailsFor,
  formatStreamSize,
  medalFor,
  metaLine,
  sanitizeAddonName,
  statusLabel,
  type DeviceContext,
} from '../src/sources/sourcePresentation.ts'
import {
  buildListing,
  buildResolveSummary,
  filterCounts,
  offlineCopyFor,
  resolveSources,
  sameReleaseFile,
  saveStateFor,
  selectionStops,
  type AddonStreamGroup,
  type VideoDownload,
} from '../src/sources/sourcesModel.ts'
import { parseStreamInfo } from '../src/sources/streamInfo.ts'

const device: DeviceContext = {
  lineMbps: 0,
  durationSeconds: 0,
}

function stream(title: string, extra: Partial<Stream> = {}): Stream {
  return { url: `https://cdn.test/${encodeURIComponent(title)}`, title, ...extra }
}

function resolve(groups: AddonStreamGroup[], options: Partial<Parameters<typeof resolveSources>[0]> = {}) {
  return resolveSources({ groups, errors: [], durationSeconds: 0, ...options })
}

function download(overrides: Partial<VideoDownload> = {}): VideoDownload {
  return {
    jobId: 'job1',
    releaseName: 'Show.S01E01.2160p.HEVC.mkv',
    status: 'done',
    sizeBytes: 13 * 1024 ** 3,
    fraction: 1,
    subtitleLang: null,
    ...overrides,
  }
}

test('sizes print as binary gigabytes, two decimals under ten', () => {
  assert.equal(formatStreamSize(8.42 * 1024 ** 3), '8.42 GB')
  assert.equal(formatStreamSize(34.2 * 1024 ** 3), '34.2 GB')
  assert.equal(formatStreamSize(null), 'UNKNOWN')
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

test('a saved download stays out of the ranked list; its own source is listed and marked saved', () => {
  const result = resolve([{ addonId: 'a', addonName: 'Addon', streams: [
    stream('Show.S01E01.2160p.HEVC\n⚡ Cached', { behaviorHints: { filename: 'Show.S01E01.2160p.HEVC.mkv' } }),
    stream('Show.S01E01.1080p\n4.1 GB'),
  ] }])
  assert.deepEqual(result.pool.map((entry) => entry.key), ['a:0', 'a:1'])
  assert.equal(result.summary, '2 sources · 1 addon')
  assert.deepEqual(result.providers.map((row) => `${row.name}: ${row.value}`), ['Addon: 2 sources'])
  assert.deepEqual(filterCounts(result.pool), { all: 2, ultraHd: 1, fullHd: 1, hd: 0 })

  const listing = buildListing(result, { filter: 'all', sort: 'Recommended', coldRevealed: false, device })
  assert.equal(listing.pick?.entry.key, 'a:0')

  const saved = download()
  assert.deepEqual(saveStateFor(result.records.get('a:0'), saved, false), { kind: 'saved' })
  assert.deepEqual(saveStateFor(result.records.get('a:1'), saved, false), { kind: 'ready', replaces: true })
  assert.deepEqual(saveStateFor(result.records.get('a:1'), null, false), { kind: 'ready', replaces: false })
})

test('save states follow the download this source started, and a start in flight', () => {
  const [record] = resolve([{ addonId: 'a', addonName: 'Addon', streams: [
    stream('x', { behaviorHints: { filename: 'show.s01e01.2160p.hevc.MKV' } }),
  ] }]).records.values()
  assert.deepEqual(saveStateFor(record, download({ status: 'downloading', fraction: 0.42 }), false), {
    kind: 'saving',
    fraction: 0.42,
  })
  assert.deepEqual(saveStateFor(record, download({ status: 'queued', fraction: null }), false), {
    kind: 'saving',
    fraction: null,
  })
  // Starting it again leads to Downloads, where a stopped transfer resumes.
  assert.deepEqual(saveStateFor(record, download({ status: 'paused' }), false), { kind: 'ready', replaces: false })
  assert.deepEqual(saveStateFor(record, null, true), { kind: 'saving', fraction: null })
  assert.deepEqual(saveStateFor(undefined, null, false), { kind: 'unavailable' })

  // A nameless stream parses to the placeholder, which never matches a saved file.
  const [nameless] = resolve([{ addonId: 'a', addonName: 'Addon', streams: [{ url: 'https://cdn.test/1' }] }]).records.values()
  assert.deepEqual(saveStateFor(nameless, download({ releaseName: nameless!.info.filename }), false), {
    kind: 'ready',
    replaces: true,
  })
})

test('the saved copy card reads its quality from the release, with codec, size and subtitle', () => {
  const copy = offlineCopyFor(download({ releaseName: 'Film.2024.2160p.DV.HEVC.Atmos.mkv', subtitleLang: 'eng' }))
  assert.equal(copy.jobId, 'job1')
  assert.deepEqual(copy.medal, { plate: '4K', tag: 'DV', finish: 'gold' })
  assert.equal(copy.meta, 'HEVC · 13.0 GB · ENG')

  const bare = offlineCopyFor(download({ releaseName: 'Home video.mkv', sizeBytes: 0 }))
  assert.equal(bare.medal, null)
  assert.equal(bare.meta, 'NO SUBS')
})

test('release names match on the leaf, case-insensitively, and never when empty', () => {
  assert.equal(sameReleaseFile('C:\\dl\\Movie.2024.mkv', 'movie.2024.MKV'), true)
  assert.equal(sameReleaseFile('', ''), false)
  assert.equal(sameReleaseFile('Movie.mkv', 'Movie.mp4'), false)
})

test('the count line names the ratio only when an addon failed', () => {
  assert.equal(buildResolveSummary(240, 3, 0), '240 sources · 3 addons')
  assert.equal(buildResolveSummary(1, 1, 0), '1 source · 1 addon')
  assert.equal(buildResolveSummary(5, 3, 1), '5 sources · 3 of 4 addons')
  assert.equal(buildResolveSummary(0, 0, 1), '0 sources · 0 of 1 addon')
  assert.equal(buildResolveSummary(0, 0, 0), 'No addon answered')
})

test('failed providers carry their failure copy into the footer and banner', () => {
  const result = resolveSources({
    groups: [],
    errors: [{ id: 'x', name: 'Torrentio', code: 'timeout' }],
    durationSeconds: 0,
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
  assert.equal(listing.pick?.status, 'Plays instantly')
  assert.deepEqual(
    listing.items.map((item) => (item.kind === 'row' ? item.entry.key : item.kind === 'header' ? `[${item.name} ${item.count}]` : item.label)),
    ['[Beta 1]', 'b:0', 'Show 2 sources that need downloading first'],
  )
  assert.deepEqual(selectionStops(listing), ['a:0', 'b:0'])

  const revealed = buildListing(result, { filter: 'all', sort: 'Recommended', coldRevealed: true, device })
  assert.deepEqual(
    revealed.items.flatMap((item) => (item.kind === 'header' ? [`${item.name} ${item.count}`] : [])),
    ['Beta 2', 'Alpha 1'],
  )

  // Any other sort drops the pick block and shows everything.
  const bySize = buildListing(result, { filter: 'all', sort: 'Smallest file', coldRevealed: false, device })
  assert.equal(bySize.pick, null)
  assert.equal(bySize.items.filter((item) => item.kind === 'row').length, 4)
  assert.deepEqual(filterCounts(result.pool), { all: 4, ultraHd: 1, fullHd: 2, hd: 1 })

  // A filter that leaves the top source out has no pick; the rest still group.
  const fullHd = buildListing(result, { filter: 'fullHd', sort: 'Recommended', coldRevealed: true, device })
  assert.equal(fullHd.pick, null)
  assert.deepEqual(selectionStops(fullHd), ['b:0', 'b:1'])
})

test('the meta line is codec, sound and size, leaving out what the release did not name', () => {
  const [full, bare] = resolve([{ addonId: 'a', addonName: 'Alpha', streams: [
    stream('A.2160p DV HEVC DDP 5.1 ENG JPN 8.42 GB', { subtitles: [{ id: '1', url: 'u', lang: 'eng' }] }),
    stream('A.1080p'),
  ] }]).pool
  assert.equal(metaLine(full!), 'HEVC · DDP 5.1 · 8.42 GB')
  assert.equal(metaLine(bare!), '')

  const single = resolve([{ addonId: 'a', addonName: 'Alpha', streams: [stream('A.2160p HEVC Atmos [RD+] 20 GB')] }])
  const listing = buildListing(single, { filter: 'all', sort: 'Recommended', coldRevealed: false, device })
  assert.equal(listing.pick?.meta, 'HEVC · ATMOS · 20.0 GB')
})

test('a source too heavy for the measured line says so in its details', () => {
  const result = resolve(
    [{ addonId: 'a', addonName: 'Alpha', streams: [stream('A.2160p [RD+] 20 GB'), stream('A.1080p [RD+] 2 GB')] }],
    { durationSeconds: 3600 },
  )
  const listing = buildListing(result, {
    filter: 'all',
    sort: 'Recommended',
    coldRevealed: false,
    device: { lineMbps: 10, durationSeconds: 3600 },
  })
  assert.equal(listing.pick?.warning, 'Needs 48 Mbps, more than your line')
  const row = listing.items.find((item) => item.kind === 'row')
  assert.equal(row?.kind === 'row' ? row.warning : null, '')
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

test('medals are gold, silver and bronze by tier, tagged by range, and tagless below HD', () => {
  const medal = (quality: string, range = 'SDR') => medalFor({ quality, range })
  assert.deepEqual(medal('2160p', 'DV'), { plate: '4K', tag: 'DV', finish: 'gold' })
  assert.deepEqual(medal('2160p'), { plate: '4K', tag: 'UHD', finish: 'gold' })
  assert.deepEqual(medal('1080p', 'HDR10'), { plate: '1080p', tag: 'HDR', finish: 'silver' })
  assert.deepEqual(medal('1440p'), { plate: '1440p', tag: 'QHD', finish: 'silver' })
  assert.deepEqual(medal('720p'), { plate: '720p', tag: 'HD', finish: 'bronze' })
  assert.deepEqual(medal('480p'), { plate: '480p', tag: null, finish: 'plain' })
  assert.deepEqual(medal('UNKNOWN'), { plate: '—', tag: null, finish: 'plain' })
})

test('audio codecs read with their channel count glued on', () => {
  const audio = (title: string) => parseStreamInfo({ url: 'https://cdn.test/x', title }).audio
  assert.equal(audio('Show.S01E01.1080p.WEB-DL.DDP5.1.H.264'), 'DDP 5.1')
  assert.equal(audio('Film.2160p.DD+5.1.HEVC'), 'DDP 5.1')
  assert.equal(audio('Film.1080p.DD+.x264'), 'DDP')
  assert.equal(audio('Film.720p.DD5.1.x264'), 'DD 5.1')
  assert.equal(audio('Film.720p.WEB.AAC2.0.H.264'), 'AAC 2.0')
  assert.equal(audio('Film.2160p.DTS-HD.MA7.1'), 'DTS-HD')
  assert.equal(audio('Film.2160p.TrueHD7.1.HEVC'), 'TRUEHD')
  // A letter after the name is another word, not this codec.
  assert.equal(audio('Film.1080p.Ddplus.x264'), null)
})

test('the DTS-HD codec and a .ts extension are not read as a quality', () => {
  const quality = (title: string) => parseStreamInfo({ url: 'https://cdn.test/x', title }).quality
  assert.equal(quality('Movie.BluRay.DTS-HD.MA.5.1-GRP'), null)
  assert.equal(quality('Movie.BluRay.DTSHD-GRP'), null)
  assert.equal(quality('Movie.Remux-GRP.ts\n💾 20 GB'), null)
  // The real tokens still count.
  assert.equal(quality('Movie.HD.WEB.DTS-HD-GRP'), '720p')
  assert.equal(quality('Movie.2023.TS.x264-GRP'), 'SD')
  assert.equal(quality('Movie.1080p.DTS-HD.MA.5.1'), '1080p')
})

test('a source size is always whole bytes, as the download engine requires', () => {
  const size = (title: string, videoSize?: number) =>
    parseStreamInfo({
      url: 'https://cdn.test/x',
      title,
      ...(videoSize != null ? { behaviorHints: { videoSize } } : {}),
    }).sizeBytes

  // 29.35 GB multiplies out to 31514322534.4 bytes.
  assert.equal(size('Inception.2010.2160p.mkv 29.35 GB'), 31514322534)
  assert.equal(size('Movie.mkv 700.5 MB'), 734527488)
  // An addon's own fractional figure is rounded too, and one of zero means unknown.
  assert.equal(size('Movie.mkv', 1234.6), 1235)
  assert.equal(size('Movie.mkv 2 GB', 0), 2 * 1024 ** 3)
  assert.equal(size('Movie.mkv'), null)
})
