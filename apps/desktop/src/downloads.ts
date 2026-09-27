import type { MetaDetail } from '@halo/core'
import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { useSyncExternalStore } from 'react'
import { getClient } from './api'
import {
  downloadEtaSeconds,
  downloadFailureMessage,
  downloadProgress,
  downloadStatusLabel,
  formatDownloadBytes,
  formatDownloadEta,
  isNewLinePeak,
  isPausedAll,
  nextDownloadedEpisode,
  opaqueDownloadOwner,
  planPauseAll,
  planResumeAll,
  pushThroughputSample,
  requiresNewSource,
  selectLandscapeArtwork,
  type DownloadFailureCode,
  type DownloadStatus,
} from './downloadsLogic'
import { getLocalPrefs, setLocalPrefs } from './localPrefs'
export type { DownloadFailureCode, DownloadStatus } from './downloadsLogic'

export interface DownloadMedia {
  video_id: string
  item_id: string
  media_type: string
  meta_id?: string
  title: string
  show_name?: string
  episode_label?: string
  poster?: string
  /** Episode still or title backdrop for the row thumbnail; set by the engine, never by a start request. */
  landscape_artwork?: string
  addon_id?: string
  binge_group?: string
  filename?: string
  video_size?: number
  video_hash?: string
  stream_name?: string
  stream_title?: string
}

export interface DownloadSubtitleRequest {
  url: string
  lang: string
  id: string
  headers?: Record<string, string>
}

export interface DownloadStartRequest {
  media: DownloadMedia
  url: string
  headers?: Record<string, string>
  subtitle?: DownloadSubtitleRequest
  replace_existing?: boolean
}

export interface DownloadView {
  job_id: string
  media: DownloadMedia
  file_name: string
  subtitle_file_name?: string
  subtitle_lang?: string
  status: DownloadStatus
  total_bytes: number
  downloaded_bytes: number
  bytes_per_second: number
  source_fingerprint: string
  failure?: DownloadFailureCode
  explicit_pause: boolean
  created_at: number
  updated_at: number
}

/** What asking for a download did; swapping a video's saved source is never implicit. */
export type StartOutcome =
  | { outcome: 'started'; download: DownloadView }
  | { outcome: 'already_exists'; download: DownloadView }
  | { outcome: 'replacement_required' }

export interface DirectoryInfo {
  path: string
  exists: boolean
  free_bytes?: number
}

export interface PlaybackFiles {
  video_path: string
  subtitle_path?: string
}

export function listDownloads(): Promise<DownloadView[]> {
  return invoke<DownloadView[]>('downloads_list')
}

export function startDownload(request: DownloadStartRequest): Promise<StartOutcome> {
  return invoke<StartOutcome>('downloads_start', {
    request: {
      ...request,
      headers: request.headers ?? {},
      replace_existing: request.replace_existing ?? false,
    },
  })
}

export function pauseDownload(jobId: string): Promise<void> {
  return invoke('downloads_pause', { jobId })
}

export function resumeDownload(jobId: string): Promise<void> {
  return invoke('downloads_resume', { jobId })
}

export function removeDownload(jobId: string): Promise<void> {
  return invoke('downloads_remove', { jobId })
}

export function attachDownloadSubtitle(
  jobId: string,
  subtitle: DownloadSubtitleRequest,
): Promise<void> {
  return invoke('downloads_attach_subtitle', {
    jobId,
    subtitle: { ...subtitle, headers: subtitle.headers ?? {} },
  })
}

export function getPlaybackPath(jobId: string): Promise<string> {
  return invoke<string>('downloads_playback_path', { jobId })
}

export function getPlaybackFiles(jobId: string): Promise<PlaybackFiles> {
  return invoke<PlaybackFiles>('downloads_playback_files', { jobId })
}

export function getDirectoryInfo(): Promise<DirectoryInfo> {
  return invoke<DirectoryInfo>('downloads_directory_info')
}

export function setDownloadDirectory(directory: string): Promise<string> {
  return invoke<string>('downloads_set_directory', { directory })
}

export async function chooseDownloadDirectory(): Promise<string | null> {
  return invoke<string | null>('downloads_choose_directory')
}

/** Reveals a saved file in Explorer, or the download folder when no job is given. */
export function openDownloadFolder(jobId?: string): Promise<void> {
  return invoke('downloads_open_folder', jobId ? { jobId } : {})
}

/* ── The app-wide downloads store ────────────────────────────────────────────
 *
 * One subscription to the engine for the whole app, rather than one per screen
 * that wants to know about downloads. It also owns what has to outlive a page
 * visit: the throughput history (sampled on a one-second clock, so its slots
 * always mean seconds no matter how many transfers report), which transfers a
 * bulk pause stopped, the last action's error, and the landscape artwork
 * lookups. Everything resets when the bound account changes.
 */

export interface DownloadsSnapshot {
  /** The signed-in account's downloads, oldest first. */
  downloads: DownloadView[]
  loading: boolean
  /** The list itself could not be read. */
  error: string | null
  /** The last pause, resume, removal or folder action failed with this. */
  actionError: string | null
  /** Combined transfer rate in MB/s, one sample per second, oldest first. */
  throughput: readonly number[]
  /** Nothing is moving and something a bulk pause stopped is waiting. */
  pausedAll: boolean
  directory: DirectoryInfo | null
}

const SAMPLE_MS = 1_000

let snapshot: DownloadsSnapshot = {
  downloads: [],
  loading: true,
  error: null,
  actionError: null,
  throughput: [],
  pausedAll: false,
  directory: null,
}
const listeners = new Set<() => void>()
/** Transfers the last "Pause all" stopped; only these does "Resume all" restart. */
let pausedByBulk = new Set<string>()
let boundAccount: string | null = null
/** Bumped on every account change so a late answer for the old one is dropped. */
let accountVersion = 0
let started = false
let sampler: number | null = null
const unlisteners: Array<() => void> = []
const artworkRequested = new Set<string>()
/** Shared per title, so several downloaded episodes fetch one series record. */
const metaRequests = new Map<string, Promise<MetaDetail>>()

function publish(patch: Partial<DownloadsSnapshot>): void {
  const next = { ...snapshot, ...patch }
  next.pausedAll = isPausedAll(next.downloads, pausedByBulk)
  snapshot = next
  for (const listener of listeners) listener()
}

function errorText(value: unknown): string {
  if (value instanceof Error) return value.message
  return typeof value === 'string' && value ? value : 'The download action failed. Try again.'
}

function byCreation(left: DownloadView, right: DownloadView): number {
  return left.created_at - right.created_at || left.job_id.localeCompare(right.job_id)
}

function setRecords(downloads: DownloadView[]): void {
  publish({ downloads })
  updateSampler()
  lookUpArtwork(downloads)
}

function applyChanged(view: DownloadView): void {
  // One download per video: a replacement stands in for what it replaced.
  const others = snapshot.downloads.filter(
    (item) => item.job_id !== view.job_id && item.media.video_id !== view.media.video_id,
  )
  setRecords([...others, view].sort(byCreation))
  // Progress ticks leave the disk alone; every other change may have moved it.
  if (view.status !== 'downloading') void refreshDirectory()
}

function applyRemoved(jobId: string): void {
  pausedByBulk.delete(jobId)
  setRecords(snapshot.downloads.filter((item) => item.job_id !== jobId))
  void refreshDirectory()
}

function ensureStarted(): void {
  if (started) return
  started = true
  const keep = (stop: () => void) => {
    unlisteners.push(stop)
  }
  void listen<DownloadView>('download-changed', (event) => applyChanged(event.payload)).then(keep)
  void listen<{ job_id: string }>('download-removed', (event) => applyRemoved(event.payload.job_id)).then(
    keep,
  )
  void refreshDownloads()
}

// A hot reload in development would otherwise stack a second set of listeners.
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    for (const stop of unlisteners) stop()
    if (sampler !== null) window.clearInterval(sampler)
  })
}

function resetForAccount(account: string | null): void {
  boundAccount = account
  accountVersion += 1
  pausedByBulk = new Set()
  artworkRequested.clear()
  metaRequests.clear()
  publish({ downloads: [], throughput: [], actionError: null, error: null })
}

export async function refreshDownloads(): Promise<void> {
  const version = accountVersion
  try {
    const downloads = await listDownloads()
    if (version !== accountVersion) return
    setRecords(downloads)
    publish({ loading: false, error: null })
  } catch (value) {
    if (version !== accountVersion) return
    publish({ loading: false, error: errorText(value) })
  }
  void refreshDirectory()
}

async function refreshDirectory(): Promise<void> {
  try {
    publish({ directory: await getDirectoryInfo() })
  } catch {
    // The folder line keeps its last reading; the next change retries.
  }
}

/** Binds the engine to an account; `accountKey` is the opaque download owner. */
export async function setDownloadsAccount(accountKey: string): Promise<DownloadView[]> {
  ensureStarted()
  const downloads = await invoke<DownloadView[]>('downloads_set_account', { accountKey })
  if (accountKey !== boundAccount) resetForAccount(accountKey)
  setRecords(downloads)
  publish({ loading: false })
  void refreshDirectory()
  return downloads
}

export async function clearDownloadsAccount(): Promise<void> {
  await invoke('downloads_clear_account')
  resetForAccount(null)
}

/* ── Throughput ──────────────────────────────────────────────────────────── */

function updateSampler(): void {
  if (sampler !== null) return
  if (!snapshot.downloads.some((item) => item.status === 'downloading')) return
  sampler = window.setInterval(sampleThroughput, SAMPLE_MS)
}

function sampleThroughput(): void {
  const running = snapshot.downloads.filter((item) => item.status === 'downloading')
  const bytesPerSecond = running.reduce((total, item) => total + item.bytes_per_second, 0)
  const throughput = pushThroughputSample(snapshot.throughput, bytesPerSecond / 1024 ** 2)
  // Transfers are the only sustained throughput this app produces, so they are
  // also the only honest measurement of the line behind it. Megabits, because
  // the number is compared with what a line is sold as; and only these
  // once-a-second samples of the engine's windowed rate, since a raw progress
  // slice would record a burst as the line speed.
  const lineMbps = (bytesPerSecond * 8) / 1_000_000
  if (isNewLinePeak(lineMbps, getLocalPrefs().measuredLineMbps)) {
    setLocalPrefs({ measuredLineMbps: lineMbps })
  }
  publish({ throughput })
  if (running.length > 0) void refreshDirectory()
  // Sampling carries on after the last transfer ends so the trace falls to
  // zero and the peak drains with it, then stops once only zeros remain.
  if (running.length === 0 && throughput.every((value) => value === 0) && sampler !== null) {
    window.clearInterval(sampler)
    sampler = null
  }
}

/* ── Landscape artwork ───────────────────────────────────────────────────── */

function metaFor(type: string, id: string): Promise<MetaDetail> {
  const key = `${type}\n${id}`
  let request = metaRequests.get(key)
  if (!request) {
    request = Promise.resolve().then(async () => (await getClient().getMeta(type, id)).meta)
    metaRequests.set(key, request)
  }
  return request
}

/**
 * Looks up, once per download, the episode still or backdrop its row draws
 * instead of a cropped portrait poster, and hands it to the engine to keep.
 */
function lookUpArtwork(downloads: readonly DownloadView[]): void {
  const version = accountVersion
  for (const item of downloads) {
    const media = item.media
    if (media.landscape_artwork || !media.meta_id || !media.media_type || !media.video_id) continue
    if (artworkRequested.has(item.job_id)) continue
    artworkRequested.add(item.job_id)
    void metaFor(media.media_type, media.meta_id)
      .then((meta) => {
        const artwork = selectLandscapeArtwork(media.video_id, meta)
        if (!artwork || version !== accountVersion) return
        return invoke('downloads_set_landscape_artwork', { jobId: item.job_id, artwork })
      })
      .catch(() => undefined)
  }
}

/* ── Actions ─────────────────────────────────────────────────────────────── */

/** Runs one engine action; its failure, or its success, replaces the last error. */
async function runAction(action: () => Promise<unknown>): Promise<void> {
  const version = accountVersion
  let failure: string | null = null
  try {
    await action()
  } catch (value) {
    failure = errorText(value)
  }
  if (version !== accountVersion) return
  publish({ actionError: failure })
  await refreshDownloads()
}

/** Attempts every id, then reports the first failure. */
async function forEachJob(ids: readonly string[], act: (jobId: string) => Promise<void>): Promise<void> {
  let first: unknown = null
  for (const id of ids) {
    try {
      await act(id)
    } catch (value) {
      first ??= value
    }
  }
  if (first !== null) throw first
}

function find(jobId: string): DownloadView | undefined {
  return snapshot.downloads.find((item) => item.job_id === jobId)
}

export function pauseTransfer(jobId: string): void {
  const item = find(jobId)
  if (!item || (item.status !== 'queued' && item.status !== 'downloading')) return
  void runAction(() => pauseDownload(jobId))
}

/** Resumes a paused transfer or retries a failed one a retry can fix. */
export function resumeTransfer(jobId: string): void {
  const item = find(jobId)
  if (!item || (item.status !== 'paused' && item.status !== 'failed')) return
  void runAction(() => resumeDownload(jobId))
}

/** Cancels an unfinished transfer and removes its partial file. */
export function cancelTransfer(jobId: string): void {
  const item = find(jobId)
  if (!item || item.status === 'done') return
  void runAction(() => removeDownload(jobId))
}

/** Deletes a finished download and its subtitle from this device. */
export function deleteReady(jobId: string): void {
  const item = find(jobId)
  if (!item || item.status !== 'done') return
  void runAction(() => removeDownload(jobId))
}

export function pauseAll(): void {
  const plan = planPauseAll(snapshot.downloads, pausedByBulk)
  pausedByBulk = plan.remembered
  publish({})
  if (plan.pause.length === 0) return
  void runAction(() => forEachJob(plan.pause, pauseDownload))
}

export function resumeAll(): void {
  if (!snapshot.pausedAll) return
  const ids = planResumeAll(snapshot.downloads, pausedByBulk)
  pausedByBulk = new Set()
  publish({})
  void runAction(() => forEachJob(ids, resumeDownload))
}

export function retryFailedTransfers(): void {
  const ids = snapshot.downloads
    .filter((item) => item.status === 'failed' && !requiresNewSource(item.failure))
    .map((item) => item.job_id)
  if (ids.length === 0) return
  void runAction(() => forEachJob(ids, resumeDownload))
}

/** Opens the download folder, or reveals one download's file in it. */
export function showInExplorer(jobId?: string): void {
  void runAction(async () => {
    try {
      await openDownloadFolder(jobId)
    } catch {
      throw new Error(
        jobId ? 'The downloaded file could not be shown.' : 'The download folder could not be opened.',
      )
    }
  })
}

/** Asks for a new download folder; `failed` means the choice could not be used. */
export async function changeDownloadFolder(): Promise<'changed' | 'cancelled' | 'failed'> {
  try {
    const chosen = await chooseDownloadDirectory()
    if (!chosen) return 'cancelled'
    await setDownloadDirectory(chosen)
    publish({ actionError: null })
    await refreshDirectory()
    return 'changed'
  } catch {
    return 'failed'
  }
}

function subscribe(listener: () => void): () => void {
  ensureStarted()
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function currentSnapshot(): DownloadsSnapshot {
  return snapshot
}

/** Everything the Downloads page draws; re-renders on every engine change. */
export function useDownloadsSnapshot(): DownloadsSnapshot {
  return useSyncExternalStore(subscribe, currentSnapshot, currentSnapshot)
}

/** The signed-in account's downloads, for screens that only need the list. */
export function useDownloads(): {
  downloads: DownloadView[]
  loading: boolean
  error: string | null
  refresh: () => Promise<void>
} {
  const { downloads, loading, error } = useDownloadsSnapshot()
  return { downloads, loading, error, refresh: refreshDownloads }
}

export {
  downloadEtaSeconds,
  downloadFailureMessage,
  downloadProgress,
  downloadStatusLabel,
  formatDownloadBytes,
  formatDownloadEta,
  nextDownloadedEpisode,
  opaqueDownloadOwner,
  requiresNewSource,
}
