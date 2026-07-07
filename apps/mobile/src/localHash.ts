import * as FileSystem from 'expo-file-system/legacy'
import {
  computeVideoHashFromChunks,
  VIDEO_HASH_CHUNK_BYTES,
  type VideoHashResult,
} from '@halo/core'

function base64ToArrayBuffer(base64: string): ArrayBuffer {
  const binary = globalThis.atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}

/**
 * OpenSubtitles hash for a downloaded file: reads only the first/last 64 KiB
 * from disk, so it works offline and matches the hash of the original stream.
 */
export async function computeLocalVideoHash(fileUri: string): Promise<VideoHashResult> {
  const info = await FileSystem.getInfoAsync(fileUri)
  if (!info.exists || info.size === undefined || info.size < VIDEO_HASH_CHUNK_BYTES * 2) {
    throw new Error(`File missing or too small to hash: ${fileUri}`)
  }
  const read = (position: number) =>
    FileSystem.readAsStringAsync(fileUri, {
      encoding: FileSystem.EncodingType.Base64,
      position,
      length: VIDEO_HASH_CHUNK_BYTES,
    })
  const [head, tail] = await Promise.all([read(0), read(info.size - VIDEO_HASH_CHUNK_BYTES)])
  return computeVideoHashFromChunks(info.size, base64ToArrayBuffer(head), base64ToArrayBuffer(tail))
}
