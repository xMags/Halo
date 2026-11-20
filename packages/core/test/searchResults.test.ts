import assert from 'node:assert/strict'
import test from 'node:test'
import type { MetaPreview } from '../src/index'
import { uniqueMetas } from '../src/search/results'

// The rest of the shared search rules are held to test/vectors/search-board.json (searchBoard.test.ts).

const meta = (id: string, type = 'movie'): MetaPreview => ({ id, type, name: id })

test('a catalog that lists a title twice shows it once', () => {
  assert.deepEqual(uniqueMetas([meta('a'), meta('a'), meta('a', 'series')]).map((m) => `${m.type}:${m.id}`), [
    'movie:a',
    'series:a',
  ])
})

const title = (id: string, type = 'movie'): MetaPreview => ({ id, type, name: id })
const ids = (metas: MetaPreview[]) => metas.map((meta) => `${meta.type}:${meta.id}`)
