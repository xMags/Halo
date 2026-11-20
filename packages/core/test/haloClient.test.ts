import assert from 'node:assert/strict'
import test from 'node:test'
import { HaloApiError, HaloClient } from '../src/api/client'

function clientAnswering(status: number, body: unknown, seen: string[] = []) {
  return new HaloClient({
    baseUrl: 'http://127.0.0.1:8787/',
    fetch: (async (input: string | URL | Request, init?: RequestInit) => {
      seen.push(`${init?.method ?? 'GET'} ${String(input)}`)
      return new Response(JSON.stringify(body), { status })
    }) as typeof fetch,
  })
}

test('a server without capabilities offers none; another failure throws', async () => {
  assert.deepEqual(await clientAnswering(404, { error: 'not found' }).getCapabilities(), {
    people: false,
    lookup: false,
    magnets: false,
    magnetsCachedOnly: true,
  })
  await assert.rejects(clientAnswering(500, {}).getCapabilities(), (err: unknown) => err instanceof HaloApiError && err.status === 500)
  assert.equal((await clientAnswering(200, { people: true, lookup: false }).getCapabilities()).people, true)
})

test('people and magnet calls check their ids before anything is sent', async () => {
  const seen: string[] = []
  const client = clientAnswering(200, {}, seen)
  await assert.rejects(client.searchPeople(' a '))
  await assert.rejects(client.getPerson(0))
  await assert.rejects(client.getPerson(1.5))
  await assert.rejects(client.lookup('tvdb', '12a'))
  await assert.rejects(client.lookup('imdb', 'xx123'))
  await assert.rejects(client.getMagnet('mg_../x'))
  await assert.rejects(client.forgetMagnet('abc'))
  await assert.rejects(client.addMagnet('C12FE1C06BBA254A9DC9F519B335AA7C1367A88A', null))
  assert.deepEqual(seen, [])
})

test('a magnet is added by its hash and name only, and its links land on this server', async () => {
  const seen: string[] = []
  let sent: unknown
  const client = new HaloClient({
    baseUrl: 'http://127.0.0.1:8787/v1/halo',
    fetch: (async (input: string | URL | Request, init?: RequestInit) => {
      seen.push(`${init?.method} ${String(input)}`)
      sent = JSON.parse(String(init?.body))
      return Response.json({
        id: 'mg_abc',
        state: 'ready',
        name: 'Film',
        sizeBytes: 10,
        progress: 1,
        files: [{ id: '1', name: 'a.mkv', sizeBytes: 10, playUrl: 'https://api.elsewhere.test/v1/halo/play/m1.a.b/a.mkv' }],
        identified: null,
      })
    }) as typeof fetch,
  })
  const request = await client.addMagnet('c12fe1c06bba254a9dc9f519b335aa7c1367a88a', 'Film')
  assert.deepEqual(seen, ['POST http://127.0.0.1:8787/v1/halo/magnets'])
  assert.deepEqual(sent, { infoHash: 'c12fe1c06bba254a9dc9f519b335aa7c1367a88a', name: 'Film' })
  assert.equal(request.files[0]!.playUrl, 'http://127.0.0.1:8787/v1/halo/play/m1.a.b/a.mkv')
})
