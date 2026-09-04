import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { useCallback, useEffect, useState } from 'react'
import { downloadEtaSeconds, downloadFailureMessage, downloadProgress, downloadStatusLabel, formatDownloadBytes, formatDownloadEta, nextDownloadedEpisode, opaqueDownloadOwner, type DownloadFailureCode, type DownloadStatus } from './downloadsLogic'
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

export function setDownloadsAccount(accountKey: string): Promise<DownloadView[]> {
  return invoke<DownloadView[]>('downloads_set_account', { accountKey }).then((value) => {
    window.dispatchEvent(new Event('halo-download-account'))
    return value
  })
}

export function clearDownloadsAccount(): Promise<void> {
  return invoke('downloads_clear_account')
}

export function startDownload(request: DownloadStartRequest): Promise<DownloadView> {
  return invoke<DownloadView>('downloads_start', {
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

export function useDownloads(): {
  downloads: DownloadView[]
  loading: boolean
  error: string | null
  refresh: () => Promise<void>
} {
  const [downloads, setDownloads] = useState<DownloadView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      setDownloads(await listDownloads())
      setError(null)
    } catch (value) {
      setError(value instanceof Error ? value.message : String(value))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
    const onAccount = () => { void refresh() }
    window.addEventListener('halo-download-account', onAccount)
    let disposed = false
    let unlisten: (() => void) | undefined
    void listen<DownloadView>('download-changed', (event) => {
      if (disposed) return
      setDownloads((current) => {
        const next = current.filter(
          (item) =>
            item.job_id !== event.payload.job_id &&
            item.media.video_id !== event.payload.media.video_id,
        )
        return [...next, event.payload].sort((a, b) => a.created_at - b.created_at)
      })
    }).then((stop) => {
      if (disposed) stop()
      else unlisten = stop
    })
    return () => {
      disposed = true
      window.removeEventListener('halo-download-account', onAccount)
      unlisten?.()
    }
  }, [refresh])

  return { downloads, loading, error, refresh }
}

export { downloadEtaSeconds, downloadFailureMessage, downloadProgress, downloadStatusLabel, formatDownloadBytes, formatDownloadEta, nextDownloadedEpisode, opaqueDownloadOwner }
