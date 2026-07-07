import { useSyncExternalStore } from 'react'
import AsyncStorage from '@react-native-async-storage/async-storage'
// The legacy API is the one with resumable downloads + progress callbacks;
// the SDK 54+ object API has no resume/progress story yet.
import * as FileSystem from 'expo-file-system/legacy'

export interface DownloadEntry {
  /** Video id (movie meta id or episode id) — one download per video. */
  id: string
  itemId: string
  type: string
  title: string
  /** Show/movie name without episode suffix — used to group Downloads by title. */
  showName?: string
  /** e.g. "S01E02"; unset for movies. */
  episodeLabel?: string
  /** Release filename from the stream's behaviorHints — improves subtitle matching. */
  filename?: string
  poster?: string
  streamUrl: string
  fileUri: string
  /** External subtitle downloaded alongside the video (ASS/SRT, kept as-is). */
  subtitleUri?: string
  subtitleLang?: string
  status: 'downloading' | 'paused' | 'done' | 'error'
  totalBytes: number
  downloadedBytes: number
  createdAt: number
}

const STORAGE_KEY = 'halo.downloads.v1'
const DOWNLOADS_DIR = `${FileSystem.documentDirectory}downloads/`

let entries: DownloadEntry[] = []
let loaded = false
const listeners = new Set<() => void>()
/** Live resumable tasks; entries without one are paused as far as iOS knows. */
const tasks = new Map<string, FileSystem.DownloadResumable>()
/** Persisted resume payloads (from savable()) so pauses survive restarts. */
const resumeData = new Map<string, string>()

function emit(): void {
  for (const listener of listeners) listener()
}

async function persist(): Promise<void> {
  const savedResume: Record<string, string> = {}
  for (const [id, data] of resumeData) savedResume[id] = data
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify({ entries, resumeData: savedResume }))
}

function update(id: string, patch: Partial<DownloadEntry>): void {
  entries = entries.map((e) => (e.id === id ? { ...e, ...patch } : e))
  emit()
  void persist()
}

export async function initDownloads(): Promise<void> {
  if (loaded) return
  loaded = true
  await FileSystem.makeDirectoryAsync(DOWNLOADS_DIR, { intermediates: true }).catch(() => undefined)
  const raw = await AsyncStorage.getItem(STORAGE_KEY)
  if (!raw) return
  try {
    const saved = JSON.parse(raw) as { entries: DownloadEntry[]; resumeData: Record<string, string> }
    // In-flight tasks don't survive a cold start — surface them as paused.
    entries = saved.entries.map((e) => (e.status === 'downloading' ? { ...e, status: 'paused' } : e))
    for (const [id, data] of Object.entries(saved.resumeData ?? {})) resumeData.set(id, data)
    emit()
  } catch {
    entries = []
  }
}

function extensionFromUrl(url: string): string {
  const path = new URL(url).pathname
  const match = path.match(/\.([a-z0-9]{2,4})$/i)
  return match ? match[1]!.toLowerCase() : 'mkv'
}

function sanitizeId(id: string): string {
  return id.replace(/[^a-zA-Z0-9._-]/g, '_')
}

export interface StartDownloadOptions {
  id: string
  itemId: string
  type: string
  title: string
  showName?: string
  episodeLabel?: string
  filename?: string
  poster?: string
  streamUrl: string
  subtitle?: { url: string; lang: string }
}

export async function startDownload(opts: StartDownloadOptions): Promise<void> {
  if (entries.some((e) => e.id === opts.id && e.status !== 'error')) return

  const fileUri = `${DOWNLOADS_DIR}${sanitizeId(opts.id)}.${extensionFromUrl(opts.streamUrl)}`
  const entry: DownloadEntry = {
    id: opts.id,
    itemId: opts.itemId,
    type: opts.type,
    title: opts.title,
    showName: opts.showName,
    episodeLabel: opts.episodeLabel,
    filename: opts.filename,
    poster: opts.poster,
    streamUrl: opts.streamUrl,
    fileUri,
    status: 'downloading',
    totalBytes: 0,
    downloadedBytes: 0,
    createdAt: Date.now(),
  }
  entries = [...entries.filter((e) => e.id !== opts.id), entry]
  emit()
  void persist()

  // Subtitles are small — grab the chosen one up front, format untouched
  // (libVLC renders ASS/SRT natively; converting would only lose styling).
  if (opts.subtitle) {
    const subExt = extensionFromUrl(opts.subtitle.url)
    const subUri = `${DOWNLOADS_DIR}${sanitizeId(opts.id)}.${subExt === 'mkv' ? 'srt' : subExt}`
    try {
      await FileSystem.downloadAsync(opts.subtitle.url, subUri)
      update(opts.id, { subtitleUri: subUri, subtitleLang: opts.subtitle.lang })
    } catch {
      // Video without its subtitle beats no download at all.
    }
  }

  await runTask(opts.id, () =>
    FileSystem.createDownloadResumable(opts.streamUrl, fileUri, {}, progressHandler(opts.id)),
  )
}

function progressHandler(id: string) {
  let lastEmit = 0
  return (progress: FileSystem.DownloadProgressData) => {
    // Throttle: progress fires constantly on fast links; 500ms is plenty for UI.
    const now = Date.now()
    if (now - lastEmit < 500) return
    lastEmit = now
    update(id, {
      downloadedBytes: progress.totalBytesWritten,
      totalBytes: progress.totalBytesExpectedToWrite,
    })
  }
}

async function runTask(id: string, create: () => FileSystem.DownloadResumable): Promise<void> {
  const task = create()
  tasks.set(id, task)
  try {
    const result = await task.downloadAsync()
    tasks.delete(id)
    // undefined result means pauseAsync interrupted it — state already set.
    if (!result) return
    if (result.status === 200 || result.status === 206) {
      update(id, { status: 'done', downloadedBytes: entryById(id)?.totalBytes ?? 0 })
      resumeData.delete(id)
    } else {
      update(id, { status: 'error' })
    }
  } catch {
    tasks.delete(id)
    update(id, { status: 'error' })
  }
}

export async function pauseDownload(id: string): Promise<void> {
  const task = tasks.get(id)
  if (!task) return
  try {
    await task.pauseAsync()
    resumeData.set(id, JSON.stringify(task.savable()))
    tasks.delete(id)
    update(id, { status: 'paused' })
  } catch {
    update(id, { status: 'error' })
  }
}

export async function resumeDownload(id: string): Promise<void> {
  const entry = entryById(id)
  if (!entry || tasks.has(id)) return
  update(id, { status: 'downloading' })
  const saved = resumeData.get(id)
  if (saved) {
    const parsed = JSON.parse(saved) as FileSystem.DownloadPauseState
    await runTask(id, () =>
      FileSystem.createDownloadResumable(
        parsed.url,
        parsed.fileUri,
        parsed.options,
        progressHandler(id),
        parsed.resumeData,
      ),
    )
  } else {
    // No resume payload (e.g. killed mid-download) — restart from scratch.
    await runTask(id, () =>
      FileSystem.createDownloadResumable(entry.streamUrl, entry.fileUri, {}, progressHandler(id)),
    )
  }
}

export async function removeDownload(id: string): Promise<void> {
  const entry = entryById(id)
  if (!entry) return
  const task = tasks.get(id)
  if (task) {
    await task.pauseAsync().catch(() => undefined)
    tasks.delete(id)
  }
  resumeData.delete(id)
  await FileSystem.deleteAsync(entry.fileUri, { idempotent: true }).catch(() => undefined)
  if (entry.subtitleUri) {
    await FileSystem.deleteAsync(entry.subtitleUri, { idempotent: true }).catch(() => undefined)
  }
  entries = entries.filter((e) => e.id !== id)
  emit()
  void persist()
}

function entryById(id: string): DownloadEntry | undefined {
  return entries.find((e) => e.id === id)
}

export function getDownload(id: string): DownloadEntry | undefined {
  return entryById(id)
}

const getSnapshot = () => entries

export function useDownloads(): DownloadEntry[] {
  return useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange)
      return () => listeners.delete(onChange)
    },
    getSnapshot,
    getSnapshot,
  )
}
