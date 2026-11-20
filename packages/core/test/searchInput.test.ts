import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { classifySearchInput } from '../src/search/input'

/* The shared vectors (test/vectors/search-input.json); see their `about` line. */
const vectors = JSON.parse(readFileSync(new URL('./vectors/search-input.json', import.meta.url), 'utf8')) as {
  rows: Array<{ input: string; kind: string } & Record<string, string>>
}

test('vectors: pasted input reads as its kind, with its fields', () => {
  assert.ok(vectors.rows.length >= 60)
  for (const row of vectors.rows) {
    const { input, ...expected } = row
    const actual = classifySearchInput(input) as unknown as Record<string, string>
    const label = input.length > 80 ? `${input.slice(0, 80)}…` : input
    for (const [field, value] of Object.entries(expected)) assert.equal(actual[field], value, `${label}: ${field}`)
  }
})

test('a magnet keeps nothing of its trackers', () => {
  const input = classifySearchInput(
    'magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Film&tr=udp%3A%2F%2Ftracker.example%3A1337',
  )
  assert.deepEqual(input, { kind: 'magnet', infoHash: 'c12fe1c06bba254a9dc9f519b335aa7c1367a88a', displayName: 'Film' })
})

test('empty input is words', () => {
  assert.deepEqual(classifySearchInput(''), { kind: 'text' })
})
