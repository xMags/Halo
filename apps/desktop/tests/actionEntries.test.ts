import assert from 'node:assert/strict'
import test from 'node:test'
import { isActionEntry, withoutActionEntries } from '../src/browse/actionEntries.ts'

test("MediaFusion's delete-all entry is not a title", () => {
  assert.equal(isActionEntry({ id: 'dltorbox', name: '🗑️💩 Delete all files' }), true)
  assert.equal(isActionEntry({ id: 'dlrealdebrid', name: 'Something' }), true)
  assert.equal(isActionEntry({ id: 'other', name: 'Clear all watched' }), true)
})

test('real titles stay, whatever their id', () => {
  for (const meta of [
    { id: 'tt12735488', name: 'Kalki 2898-AD' },
    { id: 'kitsu:1234', name: 'Delete' },
    { id: 'dl:42', name: 'Download Days' },
    { id: 'tt1', name: 'All Clear' },
    { id: 'tt2', name: 'Deleted Scenes' },
  ]) {
    assert.equal(isActionEntry(meta), false, meta.name)
  }
  assert.deepEqual(
    withoutActionEntries([
      { id: 'dltorbox', name: '🗑️💩 Delete all files' },
      { id: 'tt1', name: 'Minions & Monsters' },
    ]).map((meta) => meta.id),
    ['tt1'],
  )
})
