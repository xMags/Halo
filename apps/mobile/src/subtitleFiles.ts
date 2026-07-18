// The legacy API mirrors downloads.ts — see the comment there.
import * as FileSystem from 'expo-file-system/legacy'
import type { Subtitle } from '@halo/core'

/**
 * Player-selected external subtitles are persisted here (documents, not
 * cache): the files are tiny, and a sub chosen once should keep working next
 * session and offline. Filenames are stable per (video, subtitle) so
 * re-selecting is a disk hit instead of a re-download, and the subtitles
 * sheet can mark which variants already exist locally. No eviction — growth
 * is bounded by how many subs a person actually picks.
 */
const SUBS_DIR = `${FileSystem.documentDirectory}subs/`

function sanitize(part: string): string {
  return part.replace(/[^a-zA-Z0-9._-]/g, '_')
}

/** djb2 — bounds addon-emitted subtitle ids (sometimes whole URLs) to a stable short token. */
function shortHash(value: string): string {
  let hash = 5381
  for (let i = 0; i < value.length; i++) hash = ((hash << 5) + hash + value.charCodeAt(i)) >>> 0
  return hash.toString(16)
}

/**
 * Extension for a subtitle URL. Deliberately restricted to real subtitle
 * extensions — resolver-style URLs (".php?id=…") must not leak their server
 * extension into a filename VLC uses for format sniffing. ASS/SRT bytes are
 * saved untouched: libVLC renders both natively, converting would strip
 * styling.
 */
export function subtitleExtension(url: string): string {
  const match = url.match(/\.(ass|ssa|srt|vtt|sub)(\?|$)/i)
  return match ? match[1]!.toLowerCase() : 'srt'
}

export function subtitleFileName(videoId: string, sub: Subtitle): string {
  const id = sanitize(sub.id)
  const bounded = id.length > 40 ? `${id.slice(0, 24)}.${shortHash(sub.id)}` : id
  return `${sanitize(videoId)}--${sanitize(sub.lang)}--${bounded}.${subtitleExtension(sub.url)}`
}

/** Absolute uri for a stored subtitle file name (no existence check). */
export function localSubtitleUri(fileName: string): string {
  return `${SUBS_DIR}${fileName}`
}

/** File names present for this video — feeds the "available locally" markers. */
export async function listLocalSubtitles(videoId: string): Promise<Set<string>> {
  const names = await FileSystem.readDirectoryAsync(SUBS_DIR).catch(() => [] as string[])
  const prefix = `${sanitize(videoId)}--`
  return new Set(names.filter((name) => name.startsWith(prefix)))
}

/** Local uri for the sub, downloading only when it isn't on disk yet. */
export async function ensureLocalSubtitle(videoId: string, sub: Subtitle): Promise<string> {
  await FileSystem.makeDirectoryAsync(SUBS_DIR, { intermediates: true }).catch(() => undefined)
  const target = `${SUBS_DIR}${subtitleFileName(videoId, sub)}`
  const info = await FileSystem.getInfoAsync(target)
  if (info.exists) return target
  try {
    const result = await FileSystem.downloadAsync(sub.url, target)
    // downloadAsync writes the response body even for error statuses; an HTML
    // 404 page saved as .srt would poison every future disk hit.
    if (result.status !== 200 && result.status !== 206) throw new Error(`subtitle fetch ${result.status}`)
    return result.uri
  } catch (err) {
    await FileSystem.deleteAsync(target, { idempotent: true }).catch(() => undefined)
    throw err
  }
}
