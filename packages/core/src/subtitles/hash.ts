/**
 * OpenSubtitles moviehash: file size + sum of the first and last 64 KiB read
 * as little-endian uint64 words, all mod 2^64, rendered as 16 hex chars.
 * Matching by hash instead of title guesswork is what makes subtitle results
 * accurate; addons accept it as the `videoHash` extra.
 *
 * Computed remotely with HTTP Range requests: no full download needed.
 */

const CHUNK = 65536n
const MASK = (1n << 64n) - 1n

export interface VideoHashResult {
  hash: string
  size: number
}

function sumUint64LE(buf: ArrayBuffer): bigint {
  const view = new DataView(buf)
  let sum = 0n
  const whole = view.byteLength - (view.byteLength % 8)
  for (let i = 0; i < whole; i += 8) {
    sum = (sum + view.getBigUint64(i, true)) & MASK
  }
  // Trailing bytes (files not multiple of 8) count as a zero-padded word.
  if (whole < view.byteLength) {
    let word = 0n
    for (let i = view.byteLength - 1; i >= whole; i--) {
      word = (word << 8n) | BigInt(view.getUint8(i))
    }
    sum = (sum + word) & MASK
  }
  return sum
}

async function fetchRange(
  url: string,
  start: bigint,
  end: bigint,
  doFetch: typeof fetch,
  signal?: AbortSignal,
): Promise<ArrayBuffer> {
  const res = await doFetch(url, { headers: { Range: `bytes=${start}-${end}` }, signal })
  // 200 means the host ignored Range and is sending the whole file — abort
  // rather than downloading gigabytes for a hash.
  if (res.status !== 206) {
    await res.body?.cancel()
    throw new Error(`Range not supported (${res.status}) for ${url}`)
  }
  return res.arrayBuffer()
}

/** Where the link led and how big the file is, from one ranged request. */
interface ResolvedSource {
  url: string
  size: bigint
}

/**
 * One byte asked for with a ranged GET, never a HEAD: a debrid addon's play
 * link may answer HEAD differently from GET (ElfHosted sends HEAD on a good
 * link to its placeholder clip), and the hash must describe the file the
 * player will get. The size comes from `Content-Range`, and the chunks are
 * then read from where the redirects ended, so both come from that one file
 * and the play link is resolved once rather than once per request.
 */
async function resolveSource(url: string, doFetch: typeof fetch, signal?: AbortSignal): Promise<ResolvedSource> {
  const res = await doFetch(url, { headers: { Range: 'bytes=0-0' }, signal })
  await res.body?.cancel()
  const total = res.headers.get('content-range')?.split('/')[1]?.trim() // "bytes 0-0/123456"
  if (res.status !== 206 || !total || !/^\d+$/.test(total)) {
    throw new Error(`Cannot determine file size for ${url} (${res.status})`)
  }
  return { url: res.url || url, size: BigInt(total) }
}

/** The 64 KiB chunk length the OpenSubtitles hash is defined over. */
export const VIDEO_HASH_CHUNK_BYTES = 65536

/**
 * Hash from already-read chunks — for local files where the caller reads the
 * first/last 64 KiB itself (e.g. downloaded videos on device).
 */
export function computeVideoHashFromChunks(
  size: number,
  head: ArrayBuffer,
  tail: ArrayBuffer,
): VideoHashResult {
  const hash = (BigInt(size) + sumUint64LE(head) + sumUint64LE(tail)) & MASK
  return { hash: hash.toString(16).padStart(16, '0'), size }
}

/**
 * Best-effort: callers should catch and fall back to name-based subtitle
 * search when the host rejects ranges.
 */
export async function computeVideoHash(
  url: string,
  opts: { fetch?: typeof fetch; signal?: AbortSignal } = {},
): Promise<VideoHashResult> {
  const doFetch = opts.fetch ?? fetch
  const source = await resolveSource(url, doFetch, opts.signal)
  const size = source.size
  if (size < CHUNK * 2n) throw new Error(`File too small to hash (${size} bytes)`)

  const [head, tail] = await Promise.all([
    fetchRange(source.url, 0n, CHUNK - 1n, doFetch, opts.signal),
    fetchRange(source.url, size - CHUNK, size - 1n, doFetch, opts.signal),
  ])

  const hash = (size + sumUint64LE(head) + sumUint64LE(tail)) & MASK
  return { hash: hash.toString(16).padStart(16, '0'), size: Number(size) }
}
