import assert from 'node:assert/strict'
import test from 'node:test'
import { computeVideoHash, computeVideoHashFromChunks, VIDEO_HASH_CHUNK_BYTES } from '../src/subtitles/hash'

const PLAY_LINK = 'https://addon.example/playback/abc'
const FILE_URL = 'https://cdn.example/dld/1'
const SLATE_URL = 'https://slate.elfhosted.com/cache/x/playlist.m3u8'

/** A file of `size` bytes whose byte i is i % 251, served the way a debrid play link is. */
function server(size: number) {
  const calls: Array<{ url: string; method: string; range: string | null }> = []
  const bytes = (from: number, to: number) => Uint8Array.from({ length: to - from + 1 }, (_, i) => (from + i) % 251)
  const doFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const range = new Headers(init?.headers).get('range')
    calls.push({ url, method, range })
    // Like ElfHosted: HEAD on a good link goes to the placeholder clip.
    if (method === 'HEAD') return withUrl(new Response(null, { status: 200, headers: { 'content-length': '300' } }), SLATE_URL)
    const match = /^bytes=(\d+)-(\d+)$/.exec(range ?? '')
    if (!match) return withUrl(new Response('whole file', { status: 200 }), FILE_URL)
    const from = Number(match[1])
    const to = Math.min(Number(match[2]), size - 1)
    return withUrl(
      new Response(bytes(from, to), { status: 206, headers: { 'content-range': `bytes ${from}-${to}/${size}` } }),
      FILE_URL,
    )
  }) as typeof fetch
  return { calls, doFetch, bytes }
}

function withUrl(res: Response, url: string): Response {
  Object.defineProperty(res, 'url', { value: url })
  return res
}

test('the hash takes its size from a ranged GET, never a HEAD', async () => {
  const size = 3 * VIDEO_HASH_CHUNK_BYTES + 17
  const { calls, doFetch, bytes } = server(size)
  const result = await computeVideoHash(PLAY_LINK, { fetch: doFetch })
  const expected = computeVideoHashFromChunks(
    size,
    bytes(0, VIDEO_HASH_CHUNK_BYTES - 1).buffer,
    bytes(size - VIDEO_HASH_CHUNK_BYTES, size - 1).buffer,
  )
  assert.deepEqual(result, expected)
  assert.equal(calls.some((call) => call.method === 'HEAD'), false)
})

test('the chunks are read from where the play link led, so it resolves once', async () => {
  const { calls, doFetch } = server(4 * VIDEO_HASH_CHUNK_BYTES)
  await computeVideoHash(PLAY_LINK, { fetch: doFetch })
  assert.deepEqual(
    calls.map((call) => call.url),
    [PLAY_LINK, FILE_URL, FILE_URL],
  )
})

test('a host that ignores ranges gives no hash', async () => {
  const doFetch = (async () => withUrl(new Response('whole file', { status: 200 }), FILE_URL)) as typeof fetch
  await assert.rejects(computeVideoHash(PLAY_LINK, { fetch: doFetch }), /Cannot determine file size/)
})

test('a file smaller than two chunks gives no hash', async () => {
  const { doFetch } = server(VIDEO_HASH_CHUNK_BYTES)
  await assert.rejects(computeVideoHash(PLAY_LINK, { fetch: doFetch }), /too small/)
})
