import { languageLabel } from '@halo/core'
import type { DownloadView } from './downloadsStore'
import {
  downloadEtaSeconds,
  formatDownloadBytes,
  formatDownloadEta,
  requiresNewSource,
  THROUGHPUT_SLOTS,
} from './downloadsLogic'
import type { Medal } from '../components/medal'
import { medalFor, NOT_PARSED } from '../sources/sourcePresentation'
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
  /** The episode label alone, for the badge on an episode's card. */
  episodeLabel: string | undefined
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
  /** The queue row's mono line: `1.2 of 4.0 GB`, then percent, then time left or the state. */
  transferMeta: string[]
  /** The ready card's title: the show and episode title for an episode. */
  cardTitle: string
  /** The ready card's mono line: `2160p · HEVC · 20.0 GB · ENG`. */
  cardMeta: string
  /** The card's quality badge (`4K HDR`), gold for the gold medal; none when the release named no quality. */
  qualityBadge: { label: string; gold: boolean } | null
  /** The pane's mono line under the title: the tag, and the episode title for an episode. */
  paneMeta: string
  /** The quality medal, as the source picker stamped this file. */
  medal: Medal
  /** `2160p · HEVC`, for the pane's facts table. */
  qualityLine: string
  subsChip: string
  subsMuted: boolean
  /** The subtitle fact: the language's name, or that there is none. */
  subs: string
  addedLabel: string
  fileName: string
  poster: string | undefined
  /** Landscape still for the 16:9 art, falling back to the poster. */
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

/** `1.2 of 4.0 GB` when both sizes share a unit, else both with their own. */
export function bytesOfTotal(done: number, total: number): string {
  const [doneValue, doneUnit] = formatDownloadBytes(done).split(' ')
  const totalText = formatDownloadBytes(total)
  if (doneUnit && doneUnit === totalText.split(' ')[1]) return `${doneValue} of ${totalText}`
  return `${formatDownloadBytes(done)} of ${totalText}`
}

/**
 * What a queue row says under its title. A moving transfer has its time left,
 * the others say why they are not moving; a failure's sentence stands alone,
 * since it is longer than the line has room for beside anything else.
 */
function transferMetaOf(item: DownloadView, total: number, progress: number): string[] {
  if (item.status === 'failed') return [leadOf(item)]
  const parts =
    total > 0
      ? [bytesOfTotal(item.downloaded_bytes, total), percentText(progress)]
      : [`${formatDownloadBytes(item.downloaded_bytes)} so far`]
  if (item.status === 'downloading') {
    const eta = downloadEtaSeconds({ ...item, total_bytes: total })
    if (eta !== null) parts.push(formatDownloadEta(eta))
    else if (item.bytes_per_second <= 0) parts.push('Waiting for the source')
  } else if (item.status === 'paused') {
    parts.push('Paused by you')
  }
  return parts
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
  const medal = medalFor({ quality, range: info.dynamicRange ?? 'SDR' })
  const name = media.show_name ?? media.title
  const sub = media.show_name ? media.title : item.file_name
  const newSource = item.status === 'failed' && requiresNewSource(item.failure)
  const retryable = item.status === 'failed' && !newSource
  const language = item.subtitle_lang
  const progress = state === 'ondisk' ? 1 : total > 0 ? Math.min(1, item.downloaded_bytes / total) : 0
  const shownSub = sub === name ? '' : sub
  const downloadedLine = downloadedLineOf(item, total)
  const tag = media.episode_label || media.media_type.toUpperCase() || 'VIDEO'
  // Only what the release name actually stated: an unparsed quality or codec
  // is left out rather than printed as UNKNOWN on a card.
  const cardMeta = [info.quality, info.codec, downloadedLine, language ? language.toUpperCase() : 'NO SUBS']
  return {
    id: item.job_id,
    tag,
    episodeLabel: media.show_name ? media.episode_label || undefined : undefined,
    name,
    sub: shownSub,
    state,
    progress,
    lead: leadOf(item),
    tone: toneOf(state),
    downloadedLine,
    transferMeta: transferMetaOf(item, total, progress),
    cardTitle: media.show_name && shownSub ? `${name} · ${shownSub}` : name,
    cardMeta: cardMeta.filter((part): part is string => Boolean(part)).join(' · '),
    qualityBadge: info.quality
      ? { label: medal.tag ? `${medal.plate} ${medal.tag}` : medal.plate, gold: medal.finish === 'gold' }
      : null,
    paneMeta: media.show_name && shownSub ? `${tag} · ${shownSub}` : tag,
    medal,
    // The pane leaves out whichever half the release name did not state.
    qualityLine: [info.quality, info.codec].filter(Boolean).join(' · ') || NOT_PARSED,
    subsChip: language ? `SUB ${language.toUpperCase()}` : 'NO SUBS',
    subsMuted: !language,
    subs: language ? languageLabel(language) : 'None',
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

/**
 * The header's summary of the whole page, from every download rather than the
 * filtered view: `1 downloading · 1 waiting · 3 ready`. Empty parts are left
 * out, and so is the line when there is nothing at all.
 */
export function summaryLine(rows: readonly DownloadRow[]): string {
  const count = (state: DownloadState) => rows.filter((row) => row.state === state).length
  const parts: Array<[number, string]> = [
    [count('downloading'), 'downloading'],
    [count('queued'), 'waiting'],
    [count('paused'), 'paused'],
    [count('failed'), 'failed'],
    [count('ondisk'), 'ready'],
  ]
  return parts
    .filter(([value]) => value > 0)
    .map(([value, label]) => `${value} ${label}`)
    .join(' · ')
}

/** The Ready section's count and the space those files take. */
export function readyHeadline(count: number, bytes: number): string {
  return `${count} · ${formatDownloadBytes(bytes)}`
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

/** The big figure and its unit, drawn apart; a bulk pause has no unit. */
export function rateFigure(rate: number, pausedAll: boolean): { value: string; unit: string } {
  return pausedAll ? { value: 'PAUSED', unit: '' } : { value: megabytes(rate), unit: 'MB/s' }
}

/** Every running transfer's receiving connections, summed. */
export function totalConnections(items: readonly DownloadView[]): number {
  return items.reduce((total, item) => {
    if (item.status !== 'downloading') return total
    const connections = item.active_connections
    return total + (Number.isFinite(connections) && connections > 0 ? connections : 0)
  }, 0)
}

export function connectionsLabel(count: number): string {
  return `${count} connection${count === 1 ? '' : 's'}`
}

export interface RateNote {
  /** `connections` draws as the green pill; `note` as quiet text. */
  kind: 'connections' | 'note'
  text: string
}

/**
 * What sits beside the figure: the live connection count while something
 * downloads, a quiet note otherwise. A transfer still opening its first
 * connection says so rather than claiming zero connections.
 */
export function rateNote(items: readonly DownloadView[]): RateNote {
  const transfers = items.filter((item) => item.status !== 'done')
  if (transfers.length === 0) return { kind: 'note', text: 'Idle' }
  if (!transfers.some((item) => item.status === 'downloading')) {
    return { kind: 'note', text: queueLine(activeCount(items)) }
  }
  const connections = totalConnections(items)
  if (connections === 0) return { kind: 'note', text: 'Connecting' }
  return { kind: 'connections', text: connectionsLabel(connections) }
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
  /** Share of the chart's height, against the window's own peak; never below the floor. */
  fraction: number
  /** The newest bars are lit; older ones recede towards the start of the minute. */
  opacity: number
}

/** The shortest bar, so an idle minute still reads as a whole row. */
export const CHART_FLOOR = 0.04
/** How many of the newest bars draw at full strength. */
const LIT_BARS = 4

/**
 * The phone's chart: a fixed row of slots, oldest first, each drawn against
 * the window's own peak, so a steady slow link reads as steady instead of as
 * a flat line under a scale drawn for something faster. A freshly opened app
 * has only a few samples; the slots before them are empty, so those few are
 * not stretched across the row as a chart of enormous readings.
 */
export function chartBars(throughput: readonly number[]): ChartBar[] {
  const taken = throughput.slice(-THROUGHPUT_SLOTS)
  const samples = [...new Array<number>(THROUGHPUT_SLOTS - taken.length).fill(0), ...taken]
  const peak = Math.max(0, ...samples)
  return samples.map((value, index) => {
    const share = peak > 0 ? Math.min(1, Math.max(0, value) / peak) : 0
    const lit = index >= THROUGHPUT_SLOTS - LIT_BARS
    return {
      fraction: Math.max(CHART_FLOOR, share),
      opacity: lit ? 1 : 0.35 + (index / THROUGHPUT_SLOTS) * 0.45,
    }
  })
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
  return freeBytes === undefined ? 'free space unknown' : `${formatDownloadBytes(freeBytes)} free`
}

/** A size split for the big storage figure: `55.5` drawn large, `GB` beside it. */
export function splitBytes(bytes: number): { value: string; unit: string } {
  const [value = '0', unit = 'B'] = formatDownloadBytes(bytes).split(' ')
  return { value, unit }
}

/** The pane's "Added" fact drops the row label's prefix. */
export function addedFact(label: string): string {
  return label.startsWith('ADDED ') ? label.slice('ADDED '.length) : label
}

export function percentText(progress: number): string {
  return `${Math.round(progress * 100)}%`
}
