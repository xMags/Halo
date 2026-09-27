export type DownloadStatus = 'queued' | 'downloading' | 'paused' | 'done' | 'failed'
export type DownloadFailureCode =
  | 'source_expired'
  | 'storage_full'
  | 'invalid_range'
  | 'missing_file'
  | 'network'
  | 'server_unavailable'
  | 'source_rejected'
  | 'protected_request_corrupt'
  | 'unknown'

export interface DownloadSummary {
  status: DownloadStatus
  failure?: DownloadFailureCode
  total_bytes: number
  downloaded_bytes: number
}

export interface DownloadedEpisodeSummary {
  videoId: string
  itemId: string
  episodeLabel?: string
  status: DownloadStatus
}

export async function opaqueDownloadOwner(serverUrl: string, userId: string): Promise<string> {
  const bytes = new TextEncoder().encode(`${serverUrl.replace(/\/$/, '')}\n${userId}`)
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(digest)]
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * Season and episode read back out of a label such as "S01E05", as one
 * sortable number. Addons and our own formatter disagree about whether the
 * halves are run together, so one separator between them is accepted. Each
 * number may run to five digits, so "S01E100" still finds its next episode.
 */
function episodeOrdinal(label?: string): number | null {
  const match = label?.match(/^\s*S(\d{1,5})[ ._-]?E(\d{1,5})\s*$/i)
  if (!match) return null
  return Number(match[1]) * 100_000 + Number(match[2])
}

export function nextDownloadedEpisode<T extends DownloadedEpisodeSummary>(
  currentVideoId: string,
  entries: T[],
): T | null {
  const current = entries.find((entry) => entry.videoId === currentVideoId)
  const currentOrdinal = episodeOrdinal(current?.episodeLabel)
  if (!current || currentOrdinal == null) return null
  return entries
    .filter((entry) => entry.itemId === current.itemId && entry.status === 'done')
    .map((entry) => ({ entry, ordinal: episodeOrdinal(entry.episodeLabel) }))
    .filter(
      (candidate): candidate is { entry: T; ordinal: number } =>
        candidate.ordinal != null && candidate.ordinal > currentOrdinal,
    )
    .sort((left, right) => left.ordinal - right.ordinal)[0]?.entry ?? null
}

/** Failures a retry of the same request cannot fix; mirrors the engine's rule. */
export function requiresNewSource(failure?: DownloadFailureCode): boolean {
  return (
    failure === 'source_expired' ||
    failure === 'invalid_range' ||
    failure === 'protected_request_corrupt' ||
    failure === 'missing_file'
  )
}

export function downloadProgress(item: DownloadSummary): number | null {
  if (item.total_bytes <= 0) return null
  return Math.max(0, Math.min(1, item.downloaded_bytes / item.total_bytes))
}

export function downloadStatusLabel(item: Pick<DownloadSummary, 'status' | 'failure'>): string {
  switch (item.status) {
    case 'queued': return 'Waiting'
    case 'downloading': return 'Downloading'
    case 'paused': return 'Paused'
    case 'done': return 'Ready offline'
    case 'failed': return requiresNewSource(item.failure) ? 'Choose source again' : 'Failed'
  }
}

export function downloadFailureMessage(failure?: DownloadFailureCode): string | null {
  switch (failure) {
    case 'source_expired': return 'This source expired. Choose a source again to continue.'
    case 'storage_full': return 'The download drive ran out of free space.'
    case 'invalid_range': return 'This source cannot safely resume the partial file.'
    case 'missing_file': return 'The downloaded file is no longer available.'
    case 'network': return 'The transfer stopped after repeated network failures.'
    case 'server_unavailable': return 'The source remained unavailable after several retries.'
    case 'source_rejected': return 'The source refused this download.'
    case 'protected_request_corrupt': return 'Choose the source again to rebuild the protected request.'
    case 'unknown': return 'The download could not be completed.'
    case undefined: return null
  }
}

export function downloadEtaSeconds(
  item: Pick<DownloadSummary, 'total_bytes' | 'downloaded_bytes'> & { bytes_per_second: number },
): number | null {
  if (item.total_bytes <= 0 || item.bytes_per_second <= 0) return null
  const remaining = item.total_bytes - item.downloaded_bytes
  return remaining > 0 ? Math.ceil(remaining / item.bytes_per_second) : null
}

export function formatDownloadEta(seconds: number): string {
  if (seconds < 60) return `${seconds}s left`
  const minutes = Math.ceil(seconds / 60)
  if (minutes < 60) return `${minutes}m left`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ${minutes % 60}m left`
}

export function formatDownloadBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  return `${(value / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

/** The throughput strip keeps one sample per second for this many seconds. */
export const THROUGHPUT_SLOTS = 30

/** Appends one sample, dropping the oldest once the window is full. */
export function pushThroughputSample(samples: readonly number[], value: number): number[] {
  const next = [...samples, Number.isFinite(value) && value > 0 ? value : 0]
  return next.length > THROUGHPUT_SLOTS ? next.slice(next.length - THROUGHPUT_SLOTS) : next
}

interface PausableRecord {
  job_id: string
  status: DownloadStatus
}

function isActive(status: DownloadStatus): boolean {
  return status === 'queued' || status === 'downloading'
}

/**
 * Paused as a page means nothing is moving and something the bulk pause
 * stopped is waiting to be told to move again. Queued counts as moving: it
 * starts on its own.
 */
export function isPausedAll(records: readonly PausableRecord[], pausedByBulk: ReadonlySet<string>): boolean {
  if (pausedByBulk.size === 0) return false
  let tracked = false
  for (const record of records) {
    if (isActive(record.status)) return false
    if (pausedByBulk.has(record.job_id)) {
      tracked = true
      if (record.status !== 'paused') return false
    }
  }
  return tracked
}

/**
 * What "Pause all" stops, and which ids it remembers. The remembered set is
 * what keeps "Resume all" from restarting a transfer the user paused on its
 * own; ids already paused by an earlier bulk pause stay remembered.
 */
export function planPauseAll(
  records: readonly PausableRecord[],
  pausedByBulk: ReadonlySet<string>,
): { pause: string[]; remembered: Set<string> } {
  const pause: string[] = []
  const remembered = new Set<string>()
  for (const record of records) {
    if (isActive(record.status)) {
      pause.push(record.job_id)
      remembered.add(record.job_id)
    } else if (record.status === 'paused' && pausedByBulk.has(record.job_id)) {
      remembered.add(record.job_id)
    }
  }
  return { pause, remembered }
}

/** The transfers "Resume all" restarts: only those the bulk pause stopped. */
export function planResumeAll(records: readonly PausableRecord[], pausedByBulk: ReadonlySet<string>): string[] {
  return records
    .filter((record) => record.status === 'paused' && pausedByBulk.has(record.job_id))
    .map((record) => record.job_id)
}

interface ArtworkMeta {
  background?: string
  videos?: ReadonlyArray<{ id: string; thumbnail?: string }>
}

/**
 * A download row's landscape artwork: the episode still, which is more
 * specific than the title backdrop, then the backdrop. Null when the
 * metadata has neither, so the row keeps its portrait poster.
 */
export function selectLandscapeArtwork(videoId: string, meta: ArtworkMeta): string | null {
  const still = meta.videos?.find((video) => video.id === videoId)?.thumbnail
  if (still) return still
  return meta.background || null
}

/** The fastest line reading kept, in megabits per second. */
export const MAX_LINE_MBPS = 100_000

/**
 * Whether a throughput sample is a new line-speed peak worth writing. Small
 * gains are ignored so the store is not rewritten every second of a fast
 * transfer.
 */
export function isNewLinePeak(sampleMbps: number, currentMbps: number): boolean {
  if (!Number.isFinite(sampleMbps) || sampleMbps <= 0) return false
  return Math.min(sampleMbps, MAX_LINE_MBPS) > currentMbps * 1.1
}
