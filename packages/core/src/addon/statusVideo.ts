/**
 * Status videos: when a debrid addon cannot hand over the real file (the
 * torrent is not cached yet, the download failed, the account hit a limit),
 * it answers the play link with a short clip that says so instead of an
 * error. A player would play that clip as if it were the title, with a
 * seek bar and a quality badge, and nothing in the app would say the source
 * is not ready. These are recognised here so the app can say it itself.
 *
 * What is recognised, from each addon's own source:
 * - ElfHosted's instances redirect to their "slate" host
 *   (`slate.elfhosted.com/cache/<id>/playlist.m3u8`), whatever the reason.
 * - Torrentio redirects to `/videos/<status>_v<n>.mp4`
 *   (`downloading_v3.mp4`, `failed_rar_v3.mp4`, `limits_exceeded_v2.mp4`...).
 * - MediaFusion redirects to `/static/exceptions/<status>.mp4`.
 * - Comet, and anything else that serves its clip in place of the file,
 *   is caught by size: a video file of a few megabytes is never a film or
 *   an episode. That holds for addon streams only; a link the viewer pasted
 *   may well be a short clip, so callers turn the size rule off for those.
 */

/** What the clip is about, as far as its link tells. */
export type StatusVideoKind = 'downloading' | 'failed' | 'limit' | 'unknown'

/** Where a play link ended up after its redirects, and what the answer said about the file. */
export interface PlaybackAnswer {
  /** The final URL, after redirects. */
  url: string
  /** The response's Content-Type, if any. */
  contentType: string | null
  /** The whole file's size in bytes, when the answer told it. */
  totalBytes: number | null
}

/** No real title is this small; status clips are a few hundred kilobytes to a few megabytes. */
export const STATUS_VIDEO_MAX_BYTES = 8 * 1024 * 1024

const ELFHOSTED_SLATE_HOST = 'slate.elfhosted.com'
const TORRENTIO_STATUS = /^(downloading|download_failed|failed_[a-z_]+|limits_exceeded|blocked_access)_v\d+\.mp4$/
const MEDIAFUSION_STATUS = /^([a-z0-9_-]+)\.mp4$/
const MEDIAFUSION_DOWNLOADING = new Set(['torrent_downloading', 'torrent_not_downloaded'])
const MEDIAFUSION_LIMITS = new Set([
  'daily_download_limit',
  'exceed_remote_traffic_limit',
  'queue_full',
  'too_many_requests',
  'torrent_limit',
])
export interface StatusVideoOptions {
  /** Treat any small video file as a status clip. Off for links that may really be short clips. */
  bySize: boolean
}

/** Playlists are tiny text files whatever they stream, so size says nothing about them. */
const PLAYLIST_TYPE = /mpegurl|dash\+xml/i

/** The status clip this answer is, or null when it looks like the title itself. */
export function statusVideoKind(answer: PlaybackAnswer, options: StatusVideoOptions): StatusVideoKind | null {
  let url: URL
  try {
    url = new URL(answer.url)
  } catch {
    return null
  }
  if (url.hostname.toLowerCase() === ELFHOSTED_SLATE_HOST) return 'unknown'

  const segments = url.pathname.split('/').filter(Boolean)
  const file = segments.at(-1)?.toLowerCase() ?? ''
  const folder = segments.at(-2)?.toLowerCase() ?? ''

  const torrentio = folder === 'videos' ? TORRENTIO_STATUS.exec(file) : null
  if (torrentio) {
    const status = torrentio[1] ?? ''
    if (status === 'downloading') return 'downloading'
    if (status === 'limits_exceeded') return 'limit'
    return 'failed'
  }

  const mediaFusion = folder === 'exceptions' && segments.at(-3)?.toLowerCase() === 'static' ? MEDIAFUSION_STATUS.exec(file) : null
  if (mediaFusion) {
    const status = mediaFusion[1] ?? ''
    if (MEDIAFUSION_DOWNLOADING.has(status)) return 'downloading'
    if (MEDIAFUSION_LIMITS.has(status)) return 'limit'
    return 'failed'
  }

  if (!options.bySize) return null
  const type = answer.contentType?.toLowerCase() ?? ''
  if (
    type.startsWith('video/') &&
    !PLAYLIST_TYPE.test(type) &&
    answer.totalBytes !== null &&
    answer.totalBytes > 0 &&
    answer.totalBytes < STATUS_VIDEO_MAX_BYTES
  ) {
    return 'unknown'
  }
  return null
}

/** The file's full size from a ranged answer's Content-Range, or a whole answer's Content-Length. */
function totalBytesOf(res: Response): number | null {
  if (res.status === 206) {
    const total = res.headers.get('content-range')?.split('/')[1]?.trim()
    if (!total || !/^\d+$/.test(total)) return null
    return Number(total)
  }
  if (res.status === 200) {
    const length = res.headers.get('content-length')?.trim()
    if (!length || !/^\d+$/.test(length)) return null
    return Number(length)
  }
  return null
}

/**
 * Asks the play link for its first byte, following redirects, and says
 * whether the answer is a status clip. A ranged GET, not a HEAD: ElfHosted
 * answers HEAD on a good link with its slate, so a HEAD would condemn
 * sources that play. Only the headers are read; the request is aborted the
 * moment they arrive, because some hosts ignore Range and start sending the
 * whole clip.
 *
 * Fails open: a timeout, a network error or an error status says null, and
 * the player opens the link and reports whatever goes wrong itself.
 */
export async function probeStatusVideo(
  playUrl: string,
  doFetch: typeof fetch,
  options: StatusVideoOptions & { timeoutMs: number },
): Promise<StatusVideoKind | null> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), options.timeoutMs)
  try {
    const res = await doFetch(playUrl, { headers: { Range: 'bytes=0-0' }, signal: controller.signal })
    if (res.status !== 200 && res.status !== 206) return null
    return statusVideoKind({
      url: res.url || playUrl,
      contentType: res.headers.get('content-type'),
      totalBytes: totalBytesOf(res),
    }, options)
  } catch {
    return null
  } finally {
    clearTimeout(timer)
    controller.abort()
  }
}

/** What the player says in place of a status clip. */
export const STATUS_VIDEO_COPY: Record<StatusVideoKind, { title: string; text: string }> = {
  downloading: {
    title: 'Not ready yet',
    text: "Your debrid service is still downloading this source, and it can't tell how far along it is. Try again in a few minutes, or pick another source.",
  },
  failed: {
    title: "This source didn't work",
    text: "Your debrid service couldn't get this file. Pick another source.",
  },
  limit: {
    title: 'Debrid limit reached',
    text: 'Your debrid service has reached a download or usage limit. Try again later, or pick another source.',
  },
  unknown: {
    title: 'Not ready to play',
    text: "Your debrid service doesn't have this file ready. It may still be downloading. Try again in a few minutes, or pick another source.",
  },
}
