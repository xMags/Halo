import type { DownloadView } from './downloadsStore'
import { formatDownloadBytes, requiresNewSource, THROUGHPUT_SLOTS } from './downloadsLogic'
import { badgeTierLabel, isHdr, isPremiumTier, NOT_PARSED, tierOf } from '../sources/sourcePresentation'
import { parseStreamInfo } from '../sources/streamInfo'

/**
 * What the Downloads page says about each download and about the page as a
 * whole: a line-for-line port of the native client's `DownloadService`
 * display projection and `DownloadsViewModel`, so both clients word, colour
 * and gate everything the same way. Pure, so the page only lays it out.
 */

export type DownloadState = 'downloading' | 'queued' | 'paused' | 'failed' | 'ondisk'

/** One tone shared by a row's lead line, progress fill and the pane's copy of both. */
export type RowTone = 'normal' | 'caution' | 'critical'

export interface DownloadRow {
  id: string
  /** Episode label, else the media type (`MOVIE`), in the row's mono tag. */
  tag: string
  name: string
  /** The episode title under a show, the file name under a movie; empty when it would repeat the name. */
  sub: string
  state: DownloadState
  progress: number
  /** The mono lead line: what the transfer is doing now. */
  lead: string
  tone: RowTone
  /** `1.2 GB / 4.0 GB`, or the one size a finished file has. */
  downloadedLine: string
  qualityTier: string
  /** `HDR` beside the tier, or nothing. */
  qualityDetail: string
  gold: boolean
  /** `2160p · HEVC`, for the pane's facts table. */
  qualityLine: string
  subsChip: string
  subsMuted: boolean
  /** The subtitle fact, as a sentence. */
  subs: string
  addedLabel: string
  fileName: string
  poster: string | undefined
  /** Landscape still for the 74×42 thumbnail, falling back to the poster. */
  rowArtwork: string | undefined
  requiresNewSource: boolean
  canPause: boolean
  canResume: boolean
  canRetry: boolean
}

const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']

/** The engine's failure sentences, which the lead line prints as they are. */
const FAILURE_MESSAGES: Record<NonNullable<DownloadView['failure']>, string> = {
  source_expired: 'This source has expired. Choose a source again to continue.',
  storage_full: 'The device ran out of storage while downloading.',
  invalid_range: 'The source could not safely resume this download.',
  missing_file: 'This download is no longer on the device.',
  network: 'The download could not continue after repeated network failures.',
  server_unavailable: 'The source is still unavailable after repeated retries.',
  source_rejected: 'The source refused this download.',
  protected_request_corrupt: 'The protected download request could not be read. Choose the source again.',
  unknown: 'This download could not be completed.',
}

function stateOf(item: DownloadView): DownloadState {
  return item.status === 'done' ? 'ondisk' : item.status
}

/** `ADDED 05 SEP`, from the day the download was created, in local time. */
export function addedLabel(createdAt: number): string {
  if (!Number.isFinite(createdAt) || createdAt <= 0) return 'ADDED UNKNOWN'
  const date = new Date(createdAt)
  return `ADDED ${String(date.getDate()).padStart(2, '0')} ${MONTHS[date.getMonth()]}`
}

function leadOf(item: DownloadView): string {
  switch (item.status) {
    case 'failed':
      return FAILURE_MESSAGES[item.failure ?? 'unknown']
    case 'done':
      return 'READY FOR OFFLINE PLAYBACK'
    case 'paused':
      return 'PAUSED BY YOU'
    case 'queued':
      return 'WAITING FOR THE CURRENT TRANSFER'
    case 'downloading':
      return item.bytes_per_second > 0 ? `${formatDownloadBytes(item.bytes_per_second)}/s` : 'WAITING'
  }
}

function toneOf(state: DownloadState): RowTone {
  if (state === 'paused') return 'caution'
  if (state === 'failed') return 'critical'
  return 'normal'
}

/** The size the transfer agreed with the source, else what the addon promised. */
function totalOf(item: DownloadView): number {
  return item.total_bytes > 0 ? item.total_bytes : (item.media.video_size ?? 0)
}

function downloadedLineOf(item: DownloadView, total: number): string {
  const done = item.status === 'done'
  if (total <= 0) return done ? 'SIZE UNKNOWN' : `SIZE UNKNOWN · ${formatDownloadBytes(item.downloaded_bytes)}`
  if (done) return formatDownloadBytes(total)
  return `${formatDownloadBytes(item.downloaded_bytes)} / ${formatDownloadBytes(total)}`
}

export function rowOf(item: DownloadView): DownloadRow {
  const media = item.media
  const state = stateOf(item)
  const total = totalOf(item)
  const info = parseStreamInfo({
    name: media.stream_name,
    title: media.stream_title,
    behaviorHints: {
      filename: item.file_name,
      ...(total > 0 ? { videoSize: total } : {}),
    },
  })
  const quality = info.quality ?? NOT_PARSED
  const codec = info.codec ?? NOT_PARSED
  const tier = tierOf(quality)
  const name = media.show_name ?? media.title
  const sub = media.show_name ? media.title : item.file_name
  const newSource = item.status === 'failed' && requiresNewSource(item.failure)
  const retryable = item.status === 'failed' && !newSource
  const language = item.subtitle_lang
  return {
    id: item.job_id,
    tag: media.episode_label || media.media_type.toUpperCase() || 'VIDEO',
    name,
    sub: sub === name ? '' : sub,
    state,
    progress: state === 'ondisk' ? 1 : total > 0 ? Math.min(1, item.downloaded_bytes / total) : 0,
    lead: leadOf(item),
    tone: toneOf(state),
    downloadedLine: downloadedLineOf(item, total),
    qualityTier: badgeTierLabel(tier),
    qualityDetail: info.dynamicRange && isHdr(info.dynamicRange) ? 'HDR' : '',
    gold: isPremiumTier(tier),
    qualityLine: `${quality} · ${codec}`,
    subsChip: language ? `SUB ${language.toUpperCase()}` : 'NO SUBS',
    subsMuted: !language,
    subs: language ? `Subtitle: ${language}` : 'No subtitle sidecar',
    addedLabel: addedLabel(item.created_at),
    fileName: item.file_name,
    poster: media.poster,
    rowArtwork: media.landscape_artwork || media.poster,
    requiresNewSource: newSource,
    canPause: item.status === 'downloading' || item.status === 'queued',
    canResume: item.status === 'paused' || retryable,
    canRetry: retryable,
  }
}

/* ── The page ────────────────────────────────────────────────────────────── */

export const FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'active', label: 'Active' },
  { value: 'ready', label: 'Ready' },
  { value: 'failed', label: 'Failed' },
] as const
export type DownloadFilter = (typeof FILTERS)[number]['value']

/** Active is everything not yet on disk, failed transfers included. */
export function matchesFilter(row: DownloadRow, filter: DownloadFilter): boolean {
  switch (filter) {
    case 'active':
      return row.state !== 'ondisk'
    case 'ready':
      return row.state === 'ondisk'
    case 'failed':
      return row.state === 'failed'
    case 'all':
      return true
  }
}

export function filterCounts(transfers: readonly DownloadRow[], ready: readonly DownloadRow[]): Record<DownloadFilter, number> {
  return {
    all: transfers.length + ready.length,
    active: transfers.length,
    ready: ready.length,
    failed: transfers.filter((row) => row.state === 'failed').length,
  }
}

export function noMatchesLine(filter: DownloadFilter): string {
  switch (filter) {
    case 'active':
      return 'No active transfers right now.'
    case 'ready':
      return 'No downloads are ready to watch yet.'
    case 'failed':
      return 'No failed transfers need attention.'
    case 'all':
      return 'No downloads match this filter right now.'
  }
}

/**
 * The row the detail pane describes: the one the viewer picked if the filter
 * still shows it, else the first ready download, else the first transfer.
 */
export function resolveSelection(
  selectedId: string | null,
  ready: readonly DownloadRow[],
  transfers: readonly DownloadRow[],
): DownloadRow | null {
  return (
    ready.find((row) => row.id === selectedId) ??
    transfers.find((row) => row.id === selectedId) ??
    ready[0] ??
    transfers[0] ??
    null
  )
}

export function itemsLabel(count: number): string {
  return `${count} ITEMS`
}

/** MB/s to one decimal, as the rate reads. */
function megabytes(value: number): string {
  return value.toFixed(1)
}

/** Combined rate of every transfer, in MB/s. */
export function aggregateRate(items: readonly DownloadView[]): number {
  return items.reduce((total, item) => total + item.bytes_per_second, 0) / 1024 ** 2
}

export function rateText(rate: number, pausedAll: boolean): string {
  return pausedAll ? 'PAUSED' : `${megabytes(rate)} MB/s`
}

/** Three tones for one number: moving, idle at zero, and held by the user. */
export function rateTone(rate: number, pausedAll: boolean): 'normal' | 'idle' | 'paused' {
  if (pausedAll) return 'paused'
  return rate > 0 ? 'normal' : 'idle'
}

export function activeCount(items: readonly DownloadView[]): number {
  return items.filter((item) => item.status === 'queued' || item.status === 'downloading').length
}

export function queueLine(active: number): string {
  if (active === 0) return 'No active transfers'
  return `${active} active transfer${active === 1 ? '' : 's'}`
}

export function peakText(throughput: readonly number[]): string {
  return `PEAK ${megabytes(Math.max(0, ...throughput))} MB/S`
}

export interface ChartBar {
  /** Pixels in the 38px strip; never below 2 so an idle slot still reads as a slot. */
  height: number
  /** The newest six samples draw in the accent. */
  recent: boolean
}

export const CHART_HEIGHT = 38

/**
 * The strip is a fixed window of samples. A freshly opened app has only a
 * couple, and without the empty leading slots those two would be stretched
 * across the whole cell and read as a chart of two enormous readings.
 */
export function chartBars(throughput: readonly number[]): ChartBar[] {
  const peak = Math.max(0, ...throughput)
  const taken = throughput.slice(-THROUGHPUT_SLOTS)
  const bars: ChartBar[] = []
  for (let slot = taken.length; slot < THROUGHPUT_SLOTS; slot += 1) bars.push({ height: 2, recent: false })
  taken.forEach((value, index) => {
    const height = peak > 0 ? (value / peak) * CHART_HEIGHT : 0
    bars.push({
      height: Math.max(2, Math.min(CHART_HEIGHT, height)),
      recent: index + 6 >= taken.length,
    })
  })
  return bars
}

export interface StorageFigures {
  storedBytes: number
  inFlightBytes: number
  freeBytes: number | undefined
  /** Share of the visible capacity that is stored or in flight. */
  usedFraction: number
  storedFraction: number
}

/** Capacity is what Halo can see: the free space plus what it already holds. */
export function storageFigures(items: readonly DownloadView[], freeBytes: number | undefined): StorageFigures {
  let storedBytes = 0
  let inFlightBytes = 0
  for (const item of items) {
    if (item.status === 'done') storedBytes += item.total_bytes > 0 ? item.total_bytes : item.downloaded_bytes
    else inFlightBytes += item.downloaded_bytes
  }
  const total = storedBytes + inFlightBytes + (freeBytes ?? 0)
  return {
    storedBytes,
    inFlightBytes,
    freeBytes,
    usedFraction: total > 0 ? (storedBytes + inFlightBytes) / total : 0,
    storedFraction: total > 0 ? storedBytes / total : 0,
  }
}

export function freeLine(freeBytes: number | undefined): string {
  return freeBytes === undefined ? 'FREE SPACE UNKNOWN' : `${formatDownloadBytes(freeBytes)} FREE`
}

export function folderPath(path: string | undefined): string {
  return path ? path : 'Folder unavailable'
}

/** The empty state's mono footer: `FOLDER · D:\HALO · 120.4 GB FREE`. */
export function folderLine(path: string | undefined, freeBytes: number | undefined): string {
  const line = `FOLDER · ${folderPath(path).toUpperCase()}`
  return freeBytes === undefined ? line : `${line} · ${formatDownloadBytes(freeBytes)} FREE`
}

/** The pane's "Added" fact drops the row label's prefix. */
export function addedFact(label: string): string {
  return label.startsWith('ADDED ') ? label.slice('ADDED '.length) : label
}

export function percentText(progress: number): string {
  return `${Math.round(progress * 100)}%`
}
