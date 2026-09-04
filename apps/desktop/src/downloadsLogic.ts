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

function episodeOrdinal(label?: string): number | null {
  const match = label?.match(/^S(\d+)E(\d+)$/i)
  if (!match) return null
  return Number(match[1]) * 10_000 + Number(match[2])
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
    case 'failed': return item.failure === 'source_expired' ? 'Choose source again' : 'Failed'
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
