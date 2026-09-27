import { invoke } from '@tauri-apps/api/core'

/**
 * Seek-bar thumbnails from a second, hidden mpv instance (`scrub_preview.rs`).
 * `open` only records the file; nothing connects until the first request.
 */

export interface ScrubPreviewFrame {
  width: number
  height: number
  /** Opaque RGBA, ready for `ImageData`. */
  pixels: Uint8ClampedArray<ArrayBuffer>
}

export function openScrubPreview(source: string): Promise<void> {
  return invoke('scrub_preview_open', { source })
}

export function closeScrubPreview(): Promise<void> {
  return invoke('scrub_preview_close')
}

/**
 * The frame nearest the keyframe at `seconds`, or null when a newer request
 * superseded this one or the decode failed. The shell answers with raw bytes:
 * width and height as little-endian u32s, then the pixels.
 */
export async function requestScrubPreview(seconds: number): Promise<ScrubPreviewFrame | null> {
  const buffer = await invoke<ArrayBuffer>('scrub_preview_request', { seconds })
  if (buffer.byteLength < 8) return null
  const header = new DataView(buffer, 0, 8)
  const width = header.getUint32(0, true)
  const height = header.getUint32(4, true)
  if (width === 0 || height === 0 || buffer.byteLength !== 8 + width * height * 4) return null
  return { width, height, pixels: new Uint8ClampedArray(buffer, 8, width * height * 4) }
}
