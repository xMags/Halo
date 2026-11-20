import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { buildMagnetCard, magnetFileFor, type MagnetCardInput, type MagnetPhase } from '../src/magnets/policy'
import { magnetPlayLinkOn, readMagnet, type MagnetRequest } from '../src/magnets/readers'
import { readCapabilities } from '../src/people/readers'

/* The shared vectors (test/vectors/magnets.json); see their `about` line. */
const vectors = JSON.parse(readFileSync(new URL('./vectors/magnets.json', import.meta.url), 'utf8')) as {
  server: string
  capabilities: Array<{ body: unknown; expect: unknown }>
  playLinks: {
    rebuilt: Array<{ url: string; on: string; link: string }>
    refused: string[]
    refusedServer: Array<{ url: string; on: string }>
  }
  readerAccepted: Array<{ case: string; body: unknown; expect: Record<string, unknown> }>
  readerRefused: unknown[]
  fileFor: Array<{ magnet: MagnetRequest; type: string; videoId: string; file: string | null }>
  cards: Array<{ phase: MagnetPhase; request?: MagnetRequest; expect?: Record<string, unknown>; expectTailStartsWith?: string }>
}

/** The vectors' size formatter. */
function formatSize(bytes: number | null): string {
  if (bytes === null) return 'UNKNOWN'
  const gib = bytes / 1024 ** 3
  return `${gib.toFixed(gib >= 10 ? 1 : 2)} GB`
}

/** Every field `expected` names matches, nested objects and arrays included. */
function assertFields(actual: unknown, expected: unknown, label: string): void {
  if (Array.isArray(expected)) {
    assert.ok(Array.isArray(actual), label)
    assert.equal(actual.length, expected.length, `${label}: length`)
    expected.forEach((item, index) => assertFields(actual[index], item, `${label}[${index}]`))
    return
  }
  if (expected !== null && typeof expected === 'object') {
    for (const [key, value] of Object.entries(expected)) assertFields((actual as Record<string, unknown>)?.[key], value, `${label}.${key}`)
    return
  }
  assert.equal(actual, expected, label)
}

test('vectors: capabilities', () => {
  for (const row of vectors.capabilities) assert.deepEqual(readCapabilities(row.body), row.expect, JSON.stringify(row.body))
  assert.throws(() => readCapabilities([true]))
})

test('vectors: play links are rebuilt on the app server, or refused', () => {
  for (const row of vectors.playLinks.rebuilt) assert.equal(magnetPlayLinkOn(row.url, row.on), row.link, row.url)
  for (const url of vectors.playLinks.refused) assert.equal(magnetPlayLinkOn(url, vectors.server), null, url.slice(0, 100))
  for (const row of vectors.playLinks.refusedServer) assert.equal(magnetPlayLinkOn(row.url, row.on), null)
})

test('vectors: magnet answers', () => {
  for (const row of vectors.readerAccepted) {
    const request = readMagnet(row.body, vectors.server)
    const { nameLength, namePrefix, ...fields } = row.expect
    assertFields(request, fields, row.case)
    if (typeof nameLength === 'number') assert.equal(request.name.length, nameLength, row.case)
    if (typeof namePrefix === 'string') assert.ok(request.name.startsWith(namePrefix), row.case)
  }
  vectors.readerRefused.forEach((body, index) => assert.throws(() => readMagnet(body, vectors.server), `refused[${index}]`))
})

test('vectors: the file a title page offers', () => {
  for (const row of vectors.fileFor) {
    assert.equal(magnetFileFor(row.magnet, row.type, row.videoId)?.id ?? null, row.file, `${row.type} ${row.videoId}`)
  }
})

test('vectors: the action card', () => {
  for (const [index, row] of vectors.cards.entries()) {
    const input: MagnetCardInput = { phase: row.phase, linkName: 'Dune.2021.2160p', request: row.request ?? null, cachedOnly: true, formatSize }
    const card = buildMagnetCard(input)
    const { identityId, unidentifiedFileId, ...fields } = row.expect ?? {}
    assertFields(card, fields, `card ${index}`)
    if (identityId !== undefined) assert.equal(card.identity?.id ?? null, identityId, `card ${index}`)
    if (unidentifiedFileId !== undefined) assert.equal(card.unidentifiedFile?.id ?? null, unidentifiedFileId, `card ${index}`)
    if (row.expectTailStartsWith) assert.ok(card.tail.startsWith(row.expectTailStartsWith), `card ${index}: ${card.tail}`)
    for (const text of [card.title, card.secondary, card.mono, card.plain, card.tail, card.actionLabel]) {
      assert.ok(!text.includes('—'), `card ${index} uses an em dash`)
    }
  }
})

test('a server that fetches uncached torrents words not-cached otherwise', () => {
  const request = readMagnet({ id: 'mg_a', state: 'not_cached', sizeBytes: 0 }, vectors.server)
  const card = buildMagnetCard({ phase: 'answered', linkName: '', request, cachedOnly: false, formatSize })
  assert.equal(card.secondary, "The server's debrid account couldn't start it. Try another magnet for the same title.")
})
