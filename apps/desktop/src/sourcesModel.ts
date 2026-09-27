import type { AddonError, Stream } from '@halo/core'
import {
  NOT_PARSED,
  SPEED,
  TIER,
  addonFailureCopy,
  countLabel,
  detailsFor,
  formatStreamSize,
  isHdr,
  isSurround,
  metaLineFor,
  pickHeadline,
  reasonFor,
  bitrateWarning,
  sanitizeAddonName,
  speedOf,
  statusLabel,
  tierOf,
  type DeviceContext,
  type MetaLine,
  type SourceDetails,
  type SourceEntry,
  type SourceStatus,
} from './sourcePresentation'
import { compareStreams, hasIdentifyingFilename, parseStreamInfo, type StreamInfo } from './streamInfo'

/**
 * The sources sheet's model, ported from the native WinUI Halo Desktop's
 * `SourceService::ApplyResolve` and `SourcesViewModel::Rebuild`: which sources
 * exist, how they rank, how the list groups them, and what the header and
 * footer say about the resolve. Pure: the sheet feeds it the query results and
 * renders what comes back.
 */

/** Named the same way in the list heading and in the footer's provider column. */
export const LOCAL_PROVIDER_NAME = 'On this device'

export const FILTERS = ['all', 'playsNow', 'ultraHd', 'fullHd', 'hd'] as const
export type SourceFilter = (typeof FILTERS)[number]

export const SORTS = ['Recommended', 'Best picture', 'Smallest file', 'Fastest start'] as const
export type SourceSort = (typeof SORTS)[number]

export interface AddonStreamGroup {
  addonId: string
  addonName: string
  streams: Stream[]
}

/** A completed download of the video the sheet was opened for. */
export interface LocalFile {
  jobId: string
  /** The release file name the download was saved under. */
  releaseName: string
  sizeBytes: number
}

/** What playing or saving a source needs; the display entry carries none of it. */
export interface SourceRecord {
  key: string
  addonId: string
  /** The stream to play or save; for a local file, the addon stream it matched, if any. */
  stream: Stream | null
  info: StreamInfo
  /** Set for a file already on this device, which plays from disk. */
  downloadJobId: string | null
}

export interface ProviderRow {
  name: string
  value: string
  answered: boolean
}

export interface Resolve {
  /** Every source, local files first, in rank order. */
  pool: SourceEntry[]
  records: Map<string, SourceRecord>
  providers: ProviderRow[]
  answeredProviders: number
  failedProviders: number
  /** `Torrentio did not answer in time`: the banner title for a single failure. */
  firstFailure: string
  localCount: number
  summary: string
  smallestBytes: number
}

/**
 * The ratio form ("3 of 4 providers") only appears when somebody failed.
 * Files on the device are counted apart: they are not a provider.
 */
export function buildResolveSummary(
  sourceCount: number,
  answeredProviders: number,
  failedProviders: number,
  localCount: number,
  elapsedSeconds: number,
): string {
  const providers = answeredProviders + failedProviders
  if (providers !== 0) {
    let summary = `${sourceCount}${sourceCount === 1 ? ' source from ' : ' sources from '}`
    if (failedProviders !== 0) summary += `${answeredProviders} of `
    summary += `${providers}${providers === 1 ? ' provider, found in ' : ' providers, found in '}`
    summary += `${elapsedSeconds.toFixed(1)} seconds`
    if (localCount !== 0) summary += `, plus ${localCount} saved on this device`
    return summary
  }
  if (localCount === 0) return 'No provider answered'
  return `${localCount}${localCount === 1 ? ' file saved on this device' : ' files saved on this device'}`
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

function statusOf(info: StreamInfo, onDisk: boolean): SourceStatus {
  if (onDisk) return 'ondisk'
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
  const range = info.dynamicRange ?? 'SDR'
  const audio = info.audio ?? NOT_PARSED
  const status = statusOf(info, record.downloadJobId !== null)
  const sizeBytes = info.sizeBytes ?? 0
  return {
    key: record.key,
    providerId,
    provider,
    status,
    quality,
    range,
    codec: info.codec ?? NOT_PARSED,
    audio,
    languages: info.languages.length > 0 ? info.languages.join(' · ') : NOT_PARSED,
    size: formatStreamSize(info.sizeBytes),
    file: info.filename,
    subtitleLanguages: (record.stream?.subtitles ?? [])
      .map((subtitle) => subtitle.lang)
      .filter((lang) => lang.length > 0),
    tier: tierOf(quality),
    speed: speedOf(status),
    hdr: isHdr(range),
    surround: isSurround(audio),
    sizeBytes,
    rank,
    neededMbps: sizeBytes > 0 && durationSeconds > 0 ? (sizeBytes * 8) / durationSeconds / 1_000_000 : 0,
  }
}

/**
 * Local files are built first so an addon stream offering the same release is
 * folded into the saved copy instead of listed twice; the first such stream
 * lends the copy its subtitles. Ranking runs across the whole resolve: a file
 * on the device beats anything that must come over the network.
 */
export function resolveSources(input: {
  groups: AddonStreamGroup[]
  errors: AddonError[]
  localFiles: LocalFile[]
  durationSeconds: number
  elapsedSeconds: number
}): Resolve {
  const records = new Map<string, SourceRecord>()
  const locals: SourceRecord[] = input.localFiles.map((file) => {
    const record: SourceRecord = {
      key: `local:${file.jobId}`,
      addonId: '',
      stream: null,
      info: parseStreamInfo({
        behaviorHints: {
          filename: file.releaseName,
          ...(file.sizeBytes > 0 ? { videoSize: file.sizeBytes } : {}),
        },
      }),
      downloadJobId: file.jobId,
    }
    records.set(record.key, record)
    return record
  })

  const groupRecords: SourceRecord[][] = input.groups.map((group) => {
    const kept: SourceRecord[] = []
    group.streams.forEach((stream, index) => {
      if (!stream.url) return
      const info = parseStreamInfo(stream)
      // A nameless stream parses to the shared placeholder, which identifies
      // nothing, so it can never be folded into a saved file.
      const saved = hasIdentifyingFilename(info)
        ? locals.find(
            (local) =>
              hasIdentifyingFilename(local.info) && sameReleaseFile(local.info.filename, info.filename),
          )
        : undefined
      if (saved) {
        if (!saved.stream) {
          saved.stream = stream
          saved.addonId = group.addonId
        }
        return
      }
      const record: SourceRecord = {
        key: `${group.addonId}:${index}`,
        addonId: group.addonId,
        stream,
        info,
        downloadJobId: null,
      }
      records.set(record.key, record)
      kept.push(record)
    })
    return kept
  })

  const ranked = [...locals, ...groupRecords.flat()].sort((left, right) => {
    const leftLocal = left.downloadJobId !== null
    const rightLocal = right.downloadJobId !== null
    if (leftLocal !== rightLocal) return leftLocal ? -1 : 1
    return compareStreams(left.info, right.info)
  })
  const rankOf = new Map(ranked.map((record, position) => [record.key, position]))

  const pool: SourceEntry[] = locals.map((record) =>
    makeEntry(record, 'local', LOCAL_PROVIDER_NAME, rankOf.get(record.key)!, input.durationSeconds),
  )
  const providers: ProviderRow[] = []
  if (locals.length > 0) {
    providers.push({ name: LOCAL_PROVIDER_NAME, value: countLabel(locals.length, 'file', 'files'), answered: true })
  }
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

  const smallestBytes = pool.reduce(
    (smallest, entry) =>
      entry.sizeBytes !== 0 && (smallest === 0 || entry.sizeBytes < smallest) ? entry.sizeBytes : smallest,
    0,
  )
  const sourceCount = groupRecords.reduce((total, kept) => total + kept.length, 0)
  return {
    pool,
    records,
    providers,
    answeredProviders: input.groups.length,
    failedProviders: input.errors.length,
    firstFailure,
    localCount: locals.length,
    summary: buildResolveSummary(
      sourceCount,
      input.groups.length,
      input.errors.length,
      locals.length,
      input.elapsedSeconds,
    ),
    smallestBytes,
  }
}

export function matchesFilter(entry: SourceEntry, filter: SourceFilter): boolean {
  switch (filter) {
    case 'playsNow':
      return entry.speed === SPEED.immediate
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
  meta: MetaLine
  warning: string
  reason: string
  details: SourceDetails
}

export type ListItem =
  | { kind: 'header'; key: string; name: string; note: string; count: string }
  | SourceRow
  | { kind: 'more'; key: string; label: string }

export interface Pick {
  entry: SourceEntry
  status: string
  headline: string
  meta: MetaLine
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
    items.push({
      kind: 'header',
      key: `header:${bucket.id}`,
      name: bucket.name,
      note: bucket.name === LOCAL_PROVIDER_NAME ? 'Saved on this device' : '',
      count: countLabel(bucket.entries.length, 'source', 'sources'),
    })
    for (const entry of bucket.entries) {
      items.push({
        kind: 'row',
        entry,
        status: statusLabel(entry.status),
        // Rows leave the range to their quality plate; only the pick spells it out.
        meta: metaLineFor(entry, false, device.preferredSubtitleLanguage),
        warning: bitrateWarning(entry, device.lineMbps),
        reason: reasonFor(entry, pickEntry, entry.sizeBytes !== 0 && entry.sizeBytes === resolve.smallestBytes),
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
        headline: pickHeadline(pickEntry, resolve.pool.length === 1),
        meta: metaLineFor(pickEntry, true, device.preferredSubtitleLanguage),
        details: detailsFor(pickEntry, device, maximumNeededMbps),
      }
    : null
  return { pick, items }
}

/** How many sources each filter pill would show. */
export function filterCounts(pool: SourceEntry[]): Record<SourceFilter, number> {
  const counts = { all: 0, playsNow: 0, ultraHd: 0, fullHd: 0, hd: 0 }
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
