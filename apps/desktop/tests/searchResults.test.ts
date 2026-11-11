import assert from 'node:assert/strict'
import test from 'node:test'
import type { LibraryItem, MetaPreview } from '@halo/core'
import { libraryMatches, suggestedSearches } from '../src/search/searchResults.ts'

// The rules shared with the other clients are tested in @halo/core
// (packages/core/test/searchResults.test.ts); these are the desktop's own.

const ids = (metas: MetaPreview[]) => metas.map((meta) => `${meta.type}:${meta.id}`)

const item = (id: string, name: string, extra: Partial<LibraryItem> = {}): LibraryItem => ({
  id,
  type: id.split(':')[0]!,
  name,
  addedAt: 1,
  updatedAt: 1,
  ...extra,
})

test('libraryMatches finds every word, ignoring case and accents, newest first', () => {
  const library = [
    item('movie:tt1', 'Dune', { addedAt: 1 }),
    item('movie:tt2', 'Dune: Part Two', { addedAt: 5, poster: 'p.jpg' }),
    item('movie:tt3', 'Amélie', { addedAt: 3 }),
    item('series:tt4', 'Dune: Prophecy', { addedAt: 4, removedAt: 9 }),
  ]

  assert.deepEqual(libraryMatches(library, 'dune'), [
    { id: 'tt2', type: 'movie', name: 'Dune: Part Two', poster: 'p.jpg' },
    { id: 'tt1', type: 'movie', name: 'Dune' },
  ])
  assert.deepEqual(ids(libraryMatches(library, 'TWO dune')), ['movie:tt2'])
  assert.deepEqual(ids(libraryMatches(library, 'amelie')), ['movie:tt3'])
  assert.deepEqual(libraryMatches(library, '   '), [])
})

test('suggestedSearches offers past searches containing what is typed', () => {
  const history = [
    { term: 'dune part two', at: 3 },
    { term: 'Dune', at: 2 },
    { term: 'the office', at: 1 },
  ]
  assert.deepEqual(suggestedSearches(history, 'dun'), ['dune part two', 'Dune'])
  // What is already typed is not offered back.
  assert.deepEqual(suggestedSearches(history, 'dune'), ['dune part two'])
  assert.deepEqual(suggestedSearches(history, ''), [])
})
