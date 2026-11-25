import assert from 'node:assert/strict'
import test from 'node:test'
import { probeStatusVideo, statusVideoKind, type PlaybackAnswer } from '../src/addon/statusVideo'

const ADDON = { bySize: true }
const PASTED = { bySize: false }
const GB = 1024 * 1024 * 1024

function answer(url: string, contentType: string | null = 'video/mp4', totalBytes: number | null = 2 * GB): PlaybackAnswer {
  return { url, contentType, totalBytes }
}

test("ElfHosted's slate is a status clip whatever it streams", () => {
  assert.equal(statusVideoKind(answer('https://slate.elfhosted.com/cache/abc/playlist.m3u8', 'application/vnd.apple.mpegurl', 300), ADDON), 'unknown')
  assert.equal(statusVideoKind(answer('https://SLATE.elfhosted.com/x.mp4'), PASTED), 'unknown')
})

test("Torrentio's clips say what happened", () => {
  const base = 'https://torrentio.strem.fun/videos/'
  assert.equal(statusVideoKind(answer(`${base}downloading_v3.mp4`), PASTED), 'downloading')
  assert.equal(statusVideoKind(answer(`${base}limits_exceeded_v2.mp4`), PASTED), 'limit')
  for (const name of ['download_failed_v3', 'failed_rar_v3', 'failed_too_big_v2', 'failed_unavailable_v1', 'blocked_access_v2']) {
    assert.equal(statusVideoKind(answer(`${base}${name}.mp4`), PASTED), 'failed', name)
  }
})

test("MediaFusion's exception clips say what happened", () => {
  const base = 'https://mediafusion.elfhosted.com/static/exceptions/'
  assert.equal(statusVideoKind(answer(`${base}torrent_not_downloaded.mp4`), PASTED), 'downloading')
  assert.equal(statusVideoKind(answer(`${base}daily_download_limit.mp4`), PASTED), 'limit')
  assert.equal(statusVideoKind(answer(`${base}no_matching_file.mp4`), PASTED), 'failed')
})

test('real files are left alone, even with status words in their names', () => {
  for (const url of [
    'https://nexus-080.indi.tb-cdn.pw/dld/123?token=k',
    'https://cdn.example.com/videos/Downloading.2024.1080p.mkv',
    'https://cdn.example.com/exceptions/movie.mp4',
    'https://cdn.example.com/files/downloading_v3.mp4',
  ]) {
    assert.equal(statusVideoKind(answer(url), ADDON), null, url)
  }
  assert.equal(statusVideoKind(answer('not a url'), ADDON), null)
})

test('a tiny video from an addon is a status clip; playlists and pasted links are not', () => {
  assert.equal(statusVideoKind(answer('https://comet.example/playback/abc', 'video/mp4', 900_000), ADDON), 'unknown')
  assert.equal(statusVideoKind(answer('https://comet.example/playback/abc', 'video/mp4', 900_000), PASTED), null)
  assert.equal(statusVideoKind(answer('https://cdn.example/master.m3u8', 'application/x-mpegURL', 900), ADDON), null)
  assert.equal(statusVideoKind(answer('https://cdn.example/a.mkv', 'video/x-matroska', null), ADDON), null)
  assert.equal(statusVideoKind(answer('https://cdn.example/a.mkv', 'application/octet-stream', 900), ADDON), null)
})

function fakeFetch(res: Response, finalUrl: string, seen: RequestInit[] = []): typeof fetch {
  return (async (_input: RequestInfo | URL, init?: RequestInit) => {
    seen.push(init ?? {})
    Object.defineProperty(res, 'url', { value: finalUrl })
    return res
  }) as typeof fetch
}

test('the probe asks for one byte and reads where the link ended up', async () => {
  const seen: RequestInit[] = []
  const res = new Response('x', { status: 206, headers: { 'content-type': 'video/mp4', 'content-range': 'bytes 0-0/118743' } })
  const kind = await probeStatusVideo('https://halo.example/play/t', fakeFetch(res, 'https://torrentio.strem.fun/videos/downloading_v3.mp4', seen), { bySize: true, timeoutMs: 1000 })
  assert.equal(kind, 'downloading')
  assert.deepEqual(seen[0]?.headers, { Range: 'bytes=0-0' })
  assert.equal(seen[0]?.signal?.aborted, true, 'the request is dropped once the headers are in')
})

test('the probe takes the size from Content-Range, never a ranged Content-Length', async () => {
  const big = new Response('x', { status: 206, headers: { 'content-type': 'video/mp4', 'content-range': 'bytes 0-0/3000000000', 'content-length': '1' } })
  assert.equal(await probeStatusVideo('https://a.example/p', fakeFetch(big, 'https://cdn.example/f'), { bySize: true, timeoutMs: 1000 }), null)
  const whole = new Response('x', { status: 200, headers: { 'content-type': 'video/mp4', 'content-length': '500000' } })
  assert.equal(await probeStatusVideo('https://a.example/p', fakeFetch(whole, 'https://cdn.example/f'), { bySize: true, timeoutMs: 1000 }), 'unknown')
})

test('the probe fails open', async () => {
  const failing = (async () => {
    throw new Error('offline')
  }) as typeof fetch
  assert.equal(await probeStatusVideo('https://a.example/p', failing, { bySize: true, timeoutMs: 1000 }), null)
  const refused = new Response('no', { status: 502 })
  assert.equal(await probeStatusVideo('https://a.example/p', fakeFetch(refused, 'https://slate.elfhosted.com/x'), { bySize: true, timeoutMs: 1000 }), null)
  const hanging = ((_input: RequestInfo | URL, init?: RequestInit) =>
    new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('aborted'))))) as typeof fetch
  assert.equal(await probeStatusVideo('https://a.example/p', hanging, { bySize: true, timeoutMs: 20 }), null)
})
