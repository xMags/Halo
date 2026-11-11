import assert from 'node:assert/strict'
import test from 'node:test'
import { fetchWithHeaders, headerLines, playbackHeaders } from '../src/player/requestHeaders'

test('usable headers are kept as they are, commas included', () => {
  const headers = { Referer: 'https://site.test/', Cookie: 'a=1, b=2' }
  assert.deepEqual(playbackHeaders(headers), headers)
  assert.deepEqual(headerLines(headers), ['Referer: https://site.test/', 'Cookie: a=1, b=2'])
})

test('headers that could break out of their line or are not plain names are dropped', () => {
  assert.equal(playbackHeaders({ 'X-A': 'ok\r\nInjected: 1', 'Bad Name': 'x', 'X-B:': 'x', 'X-C': 5 }), undefined)
})

test('headers the player and the connection own are never taken from an addon', () => {
  assert.deepEqual(playbackHeaders({ Range: 'bytes=0-1', HOST: 'evil.test', Referer: 'https://a.test/' }), {
    Referer: 'https://a.test/',
  })
})

test('nothing usable means no headers at all', () => {
  assert.equal(playbackHeaders(undefined), undefined)
  assert.equal(playbackHeaders([]), undefined)
  assert.deepEqual(headerLines(undefined), [])
})

test('a fetch carries the stream headers and keeps the request\'s own Range', async () => {
  const seen: Array<Record<string, string>> = []
  const base = (async (_input: RequestInfo | URL, init?: RequestInit) => {
    seen.push(init?.headers as Record<string, string>)
    return new Response('')
  }) as typeof fetch
  await fetchWithHeaders(base, { Referer: 'https://a.test/' })('https://cdn.test/v', { headers: { Range: 'bytes=0-9' } })
  assert.deepEqual(seen[0], { Referer: 'https://a.test/', Range: 'bytes=0-9' })
  assert.equal(fetchWithHeaders(base, undefined), base)
})
