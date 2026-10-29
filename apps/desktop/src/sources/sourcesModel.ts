import type { AddonError, Stream } from '@halo/core'
import type { Medal } from '../components/medal'
import type { DownloadStatus } from '../downloads/downloadsLogic'
import {
  NOT_PARSED,
  SPEED,
  TIER,
  addonFailureCopy,
  bitrateWarning,
  countLabel,
  detailsFor,
  formatStreamSize,
  medalFor,
  metaLine,
  sanitizeAddonName,
  speedOf,
  statusLabel,
  tierOf,
  type DeviceContext,
  type SourceDetails,
  type SourceEntry,
  type SourceStatus,
} from './sourcePresentation'
import { compareStreams, hasIdentifyingFilename, parseStreamInfo, type StreamInfo } from './streamInfo'

/**
 * The sources sheet's model, ported from the native WinUI Halo Desktop's
 * `SourceService::ApplyResolve` and `SourcesViewModel::Rebuild` and laid out
 * as the phone's sources page: which sources exist, how they rank, how the
 * list groups them, and what the header and footer say about the resolve.
 * A finished download is not one of them: it gets its own card above the
 * list. Pure: the sheet feeds it the query results and renders what comes back.
 */

export const FILTERS = ['all', 'ultraHd', 'fullHd', 'hd'] as const
export type SourceFilter = (typeof FILTERS)[number]

export const SORTS = ['Recommended', 'Best picture', 'Smallest file', 'Fastest start'] as const
export type SourceSort = (typeof SORTS)[number]

export interface AddonStreamGroup {
  addonId: string
  addonName: string
  streams: Stream[]
}

/** What playing or saving a source needs; the display entry carries none of it. */
export interface SourceRecord {
  key: string
  addonId: string
  stream: Stream
  info: StreamInfo
}

export interface ProviderRow {
  name: string
  value: string
  answered: boolean
}

export interface Resolve {
  /** Every source, in rank order. */
  pool: SourceEntry[]
  records: Map<string, SourceRecord>
  providers: ProviderRow[]
  answeredProviders: number
  failedProviders: number
  /** `Torrentio did not answer in time`: the banner title for a single failure. */
  firstFailure: string
  /** `240 sources · 3 addons`, the header's count line. */
  summary: string
}

/** The ratio form ("1 of 2 addons") only appears when somebody failed. */
export function buildResolveSummary(sourceCount: number, answeredProviders: number, failedProviders: number): string {
  const addons = answeredProviders + failedProviders
  if (addons === 0) return 'No addon answered'
  const asked =
    failedProviders === 0
      ? countLabel(addons, 'addon', 'addons')
      : `${answeredProviders} of ${countLabel(addons, 'addon', 'addons')}`
  return `${countLabel(sourceCount, 'source', 'sources')} · ${asked}`
}

/** The file name without any directory, trimmed. */
function releaseLeafName(value: string): string {
  const separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'))
  return (separator === -1 ? value : value.slice(separator + 1)).trim()
}

/** Case-insensitive leaf match; an empty name never matches, not even another. */
export function sameReleaseFile(saved: string, candidate: string): boolean {
  const left = releaseLeafName(saved)
  const right = releaseLeafName(candidate)
  if (!left || !right || left.length !== right.length) return false
  return left.toLowerCase() === right.toLowerCase()
}

function statusOf(info: StreamInfo): SourceStatus {
  if (info.cached === null) return 'unknown'
  return info.cached ? 'instant' : 'uncached'
}

function makeEntry(
  record: SourceRecord,
  providerId: string,
  provider: string,
  rank: number,
  durationSeconds: number,
): SourceEntry {
  const { info } = record
  const quality = info.quality ?? NOT_PARSED
  const status = statusOf(info)
  const sizeBytes = info.sizeBytes ?? 0
  return {
    key: record.key,
    providerId,
    provider,
    status,
    quality,
    range: info.dynamicRange ?? 'SDR',
    codec: info.codec ?? NOT_PARSED,
    audio: info.audio ?? NOT_PARSED,
    languages: info.languages.length > 0 ? info.languages.join(' · ') : NOT_PARSED,
    size: formatStreamSize(info.sizeBytes),
    file: info.filename,
    subtitleLanguages: (record.stream.subtitles ?? [])
      .map((subtitle) => subtitle.lang)
      .filter((lang) => lang.length > 0),
    tier: tierOf(quality),
    speed: speedOf(status),
    sizeBytes,
    rank,
    neededMbps: sizeBytes > 0 && durationSeconds > 0 ? (sizeBytes * 8) / durationSeconds / 1_000_000 : 0,
  }
}

/** Ranking runs across the whole resolve, not within each addon. */
export function resolveSources(input: {
  groups: AddonStreamGroup[]
  errors: AddonError[]
  durationSeconds: number
}): Resolve {
  const records = new Map<string, SourceRecord>()
  const groupRecords: SourceRecord[][] = input.groups.map((group) => {
    const kept: SourceRecord[] = []
    group.streams.forEach((stream, index) => {
      if (!stream.url) return
      const record: SourceRecord = {
        key: `${group.addonId}:${index}`,
        addonId: group.addonId,
        stream,
        info: parseStreamInfo(stream),
      }
      records.set(record.key, record)
      kept.push(record)
    })
    return kept
  })

  const ranked = groupRecords.flat().sort((left, right) => compareStreams(left.info, right.info))
  const rankOf = new Map(ranked.map((record, position) => [record.key, position]))

  const pool: SourceEntry[] = []
  const providers: ProviderRow[] = []
  input.groups.forEach((group, index) => {
    const name = sanitizeAddonName(group.addonName)
    const kept = groupRecords[index]!
    for (const record of kept) {
      pool.push(makeEntry(record, group.addonId || name, name, rankOf.get(record.key)!, input.durationSeconds))
    }
    providers.push({ name, value: countLabel(kept.length, 'source', 'sources'), answered: true })
  })
  let firstFailure = ''
  for (const failure of input.errors) {
    const name = sanitizeAddonName(failure.name)
    const note = addonFailureCopy(failure.code)
    if (!firstFailure) firstFailure = `${name} ${note}`
    providers.push({ name, value: note, answered: false })
  }
  pool.sort((left, right) => left.rank - right.rank)

  return {
    pool,
    records,
    providers,
    answeredProviders: input.groups.length,
    failedProviders: input.errors.length,
    firstFailure,
    summary: buildResolveSummary(pool.length, input.groups.length, input.errors.length),
  }
}

export function matchesFilter(entry: SourceEntry, filter: SourceFilter): boolean {
  switch (filter) {
    case 'ultraHd':
      return entry.tier === TIER.ultraHd
    case 'fullHd':
      return entry.tier === TIER.fullHd
    case 'hd':
      return entry.tier === TIER.hd
    case 'all':
      return true
  }
}

/** Stable, so equal keys keep their rank order. */
export function sortEntries(entries: SourceEntry[], sort: SourceSort): SourceEntry[] {
  const sorted = [...entries]
  switch (sort) {
    case 'Best picture':
      return sorted.sort((left, right) => left.tier - right.tier || right.sizeBytes - left.sizeBytes)
    case 'Smallest file':
      // A source that never reported a size cannot claim to be the smallest.
      return sorted.sort((left, right) => {
        if ((left.sizeBytes === 0) !== (right.sizeBytes === 0)) return left.sizeBytes === 0 ? 1 : -1
        return left.sizeBytes - right.sizeBytes
      })
    case 'Fastest start':
      return sorted.sort((left, right) => left.speed - right.speed || left.rank - right.rank)
    case 'Recommended':
      return sorted.sort((left, right) => left.rank - right.rank)
  }
}

export interface SourceRow {
  kind: 'row'
  entry: SourceEntry
  status: string
  meta: string
  /** Said in the details, beside the bandwidth. */
  warning: string
  details: SourceDetails
}

export type ListItem =
  | { kind: 'header'; key: string; name: string; count: number }
  | SourceRow
  | { kind: 'more'; key: string; label: string }

export interface Pick {
  entry: SourceEntry
  status: string
  meta: string
  warning: string
  details: SourceDetails
}

export interface Listing {
  pick: Pick | null
  items: ListItem[]
}

/**
 * The recommended pick is the top-ranked source, shown in its own block and
 * not repeated below. The list groups the rest by provider in rank order;
 * under Recommended, sources that must download first wait behind one row.
 */
export function buildListing(
  resolve: Resolve,
  options: { filter: SourceFilter; sort: SourceSort; coldRevealed: boolean; device: DeviceContext },
): Listing {
  const { filter, sort, coldRevealed, device } = options
  const listed = sortEntries(
    resolve.pool.filter((entry) => matchesFilter(entry, filter)),
    sort,
  )
  const maximumNeededMbps = listed.reduce((maximum, entry) => Math.max(maximum, entry.neededMbps), 0)
  const top = resolve.pool[0]
  const pickEntry = sort === 'Recommended' && top && matchesFilter(top, filter) ? top : null

  const buckets: Array<{ id: string; name: string; entries: SourceEntry[] }> = []
  let hiddenCold = 0
  for (const entry of listed) {
    if (pickEntry && entry.key === pickEntry.key) continue
    if (sort === 'Recommended' && !coldRevealed && entry.speed === SPEED.needsDownload) {
      hiddenCold += 1
      continue
    }
    const bucket = buckets.find((candidate) => candidate.id === entry.providerId)
    if (bucket) bucket.entries.push(entry)
    else buckets.push({ id: entry.providerId, name: entry.provider, entries: [entry] })
  }

  const items: ListItem[] = []
  for (const bucket of buckets) {
    items.push({ kind: 'header', key: `header:${bucket.id}`, name: bucket.name, count: bucket.entries.length })
    for (const entry of bucket.entries) {
      items.push({
        kind: 'row',
        entry,
        status: statusLabel(entry.status),
        meta: metaLine(entry),
        warning: bitrateWarning(entry, device.lineMbps),
        details: detailsFor(entry, device, maximumNeededMbps),
      })
    }
  }
  if (hiddenCold !== 0) {
    items.push({
      kind: 'more',
      key: 'more',
      label: `Show ${countLabel(hiddenCold, 'source', 'sources')} that need downloading first`,
    })
  }

  const pick: Pick | null = pickEntry
    ? {
        entry: pickEntry,
        status: statusLabel(pickEntry.status),
        meta: metaLine(pickEntry),
        warning: bitrateWarning(pickEntry, device.lineMbps),
        details: detailsFor(pickEntry, device, maximumNeededMbps),
      }
    : null
  return { pick, items }
}

/** How many sources each filter pill would show. */
export function filterCounts(pool: SourceEntry[]): Record<SourceFilter, number> {
  const counts = { all: 0, ultraHd: 0, fullHd: 0, hd: 0 }
  for (const entry of pool) {
    for (const filter of FILTERS) {
      if (matchesFilter(entry, filter)) counts[filter] += 1
    }
  }
  return counts
}

/** The footer's quality mix: always the three tiers, even when one is empty. */
export function qualityMix(pool: SourceEntry[]): Array<{ tier: 0 | 1 | 2; count: number; share: number }> {
  return ([TIER.ultraHd, TIER.fullHd, TIER.hd] as const).map((tier) => {
    const count = pool.filter((entry) => entry.tier === tier).length
    return { tier, count, share: pool.length > 0 ? count / pool.length : 0 }
  })
}

/** Keyboard stops, top to bottom: the pick, then every source row. */
export function selectionStops(listing: Listing): string[] {
  return [
    ...(listing.pick ? [listing.pick.entry.key] : []),
    ...listing.items.flatMap((item) => (item.kind === 'row' ? [item.entry.key] : [])),
  ]
}

/** This video's download, whatever its state: the engine keeps one per video. */
export interface VideoDownload {
  jobId: string
  /** The release file name the download was saved under. */
  releaseName: string
  /** The stream's own name and title, which can say what the file name does not. */
  streamName?: string
  streamTitle?: string
  status: DownloadStatus
  sizeBytes: number
  /** Zero to one, or null while the size is unknown. */
  fraction: number | null
  /** The subtitle saved beside it, if any. */
  subtitleLang: string | null
}

/** The finished download's card, above the filters and outside the ranking. */
export interface OfflineCopy {
  jobId: string
  /** Null when the release name does not say its resolution. */
  medal: Medal | null
  /** `HEVC · 13.0 GB · ENG`: codec, size, and the saved subtitle or `NO SUBS`. */
  meta: string
}

export function offlineCopyFor(download: VideoDownload): OfflineCopy {
  const info = parseStreamInfo({
    ...(download.streamName ? { name: download.streamName } : {}),
    ...(download.streamTitle ? { title: download.streamTitle } : {}),
    behaviorHints: { filename: download.releaseName },
  })
  const parts = [
    info.codec,
    download.sizeBytes > 0 ? formatStreamSize(download.sizeBytes) : null,
    download.subtitleLang ? download.subtitleLang.toUpperCase() : 'NO SUBS',
  ]
  return {
    jobId: download.jobId,
    medal: info.quality ? medalFor({ quality: info.quality, range: info.dynamicRange ?? 'SDR' }) : null,
    meta: parts.filter((part): part is string => part != null && part.length > 0).join(' · '),
  }
}

/** What saving a source means right now; one download per video decides it. */
export type SaveState =
  /** Nothing to save from: the source has no address. */
  | { kind: 'unavailable' }
  /** `replaces` when the video is already held from another source. */
  | { kind: 'ready'; replaces: boolean }
  | { kind: 'saving'; fraction: number | null }
  | { kind: 'saved' }

/**
 * Only the source the download came from wears its state, matched by release
 * name since debrid links change between resolves. Any other source can be
 * saved instead, which replaces it after asking. A paused or failed transfer
 * of this same source reads as ready: starting it again leads to Downloads,
 * where it resumes.
 */
export function saveStateFor(
  record: SourceRecord | undefined,
  download: VideoDownload | null,
  starting: boolean,
): SaveState {
  if (!record?.stream.url) return { kind: 'unavailable' }
  if (starting) return { kind: 'saving', fraction: null }
  if (!download) return { kind: 'ready', replaces: false }
  const fromHere =
    hasIdentifyingFilename(record.info) && sameReleaseFile(download.releaseName, record.info.filename)
  if (!fromHere) return { kind: 'ready', replaces: true }
  switch (download.status) {
    case 'done':
      return { kind: 'saved' }
    case 'queued':
    case 'downloading':
      return { kind: 'saving', fraction: download.fraction }
    case 'paused':
    case 'failed':
      return { kind: 'ready', replaces: false }
  }
}
