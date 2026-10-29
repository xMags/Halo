import assert from 'node:assert/strict'
import test from 'node:test'
import type { MetaPreview } from '@halo/core'
import { hasMorePages, newTitles, supportsSkip } from '../src/browse/catalogPaging.ts'

const meta = (id: string, type = 'movie'): MetaPreview => ({ id, type, name: id })

test('only a catalog that declares skip pages, in either manifest form', () => {
  assert.equal(supportsSkip({ extra: [{ name: 'genre' }, { name: 'skip' }] }), true)
  assert.equal(supportsSkip({ extraSupported: ['search', 'skip'] }), true)
  assert.equal(supportsSkip({ extra: [{ name: 'genre' }] }), false)
  assert.equal(supportsSkip({}), false)
})

test('a page adds only unseen titles the grid keeps, in the addon order', () => {
  const shown = [meta('a'), meta('b')]
  const page = [meta('b'), meta('c'), meta('d', 'series'), meta('e'), meta('c')]
  const added = newTitles(shown, page, (m) => m.type === 'movie')
  assert.deepEqual(
    added.map((m) => m.id),
    ['c', 'e'],
  )
})

test('the same id under another type is a different title', () => {
  assert.deepEqual(
    newTitles([meta('a')], [meta('a', 'series')], () => true).map((m) => m.type),
    ['series'],
  )
})

test('paging stops at an empty page or one the addon already sent', () => {
  const received = [meta('a'), meta('b')]
  assert.equal(hasMorePages(received, []), false)
  // An addon that ignores skip answers with its first page again.
  assert.equal(hasMorePages(received, [meta('a'), meta('b')]), false)
  assert.equal(hasMorePages(received, [meta('b'), meta('c')]), true)
})
