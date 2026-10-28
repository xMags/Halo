import assert from 'node:assert/strict'
import test from 'node:test'
import type { AddonEntry, ManifestCatalog } from '@halo/core'
import { pickGenreCatalog } from '../src/browse/similarTitles.ts'

function addon(id: string, catalogs: ManifestCatalog[]): AddonEntry {
  return {
    id,
    manifest: { id, version: '1.0.0', name: `Addon ${id}`, catalogs, resources: [], types: [] },
    position: 0,
  } as unknown as AddonEntry
}

test('the first addon whose catalog filters this type by the leading genre wins', () => {
  const addons = [
    addon('a', [{ type: 'series', id: 'top', extra: [{ name: 'genre' }] }]),
    addon('b', [{ type: 'movie', id: 'top', extra: [{ name: 'genre', options: ['Drama', 'Comedy'] }] }]),
    addon('c', [{ type: 'movie', id: 'popular', extra: [{ name: 'genre' }] }]),
  ]
  assert.deepEqual(pickGenreCatalog(addons, 'movie', ['Drama']), {
    addonId: 'b',
    addonName: 'Addon b',
    catalogId: 'top',
    genre: 'Drama',
  })
})

test('a catalog listing its genres is skipped for one it does not list', () => {
  const addons = [addon('a', [{ type: 'movie', id: 'top', extra: [{ name: 'genre', options: ['Comedy'] }] }])]
  assert.equal(pickGenreCatalog(addons, 'movie', ['Musical']), null)
  assert.equal(pickGenreCatalog(addons, 'movie', ['Musical', 'Comedy'])?.genre, 'Comedy')
})

test('catalogs needing another extra, or with no genre filter, are never picked', () => {
  const addons = [
    addon('a', [{ type: 'movie', id: 'search', extra: [{ name: 'genre' }, { name: 'search', isRequired: true }] }]),
    addon('b', [{ type: 'movie', id: 'top', extra: [{ name: 'skip' }] }]),
    addon('c', [{ type: 'movie', id: 'legacy', extraSupported: ['genre'], extraRequired: ['search'] }]),
  ]
  assert.equal(pickGenreCatalog(addons, 'movie', ['Drama']), null)
})

test('the legacy extraSupported list counts as a genre filter', () => {
  const addons = [addon('a', [{ type: 'movie', id: 'legacy', extraSupported: ['genre'] }])]
  assert.equal(pickGenreCatalog(addons, 'movie', ['Drama'])?.catalogId, 'legacy')
})
