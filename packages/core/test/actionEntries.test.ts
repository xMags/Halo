import assert from 'node:assert/strict'
import test from 'node:test'
import { isActionEntry, isActionStream, withoutActionEntries } from '../src/addon/actionEntries'
import { normalizeStreamsResponse } from '../src/addon/normalize'

test("MediaFusion's delete-all entry is not a title", () => {
  assert.equal(isActionEntry({ id: 'dltorbox', name: '🗑️💩 Delete all files' }), true)
  assert.equal(isActionEntry({ id: 'dlrealdebrid', name: 'Anything' }), true)
})

test('an action named any other way is still caught', () => {
  for (const name of ['🗑 Empty', 'Clear all watched', 'Remove all torrents', 'Wipe the library', 'Purge cache']) {
    assert.equal(isActionEntry({ id: 'x:1', name }), true, name)
  }
})

test('real titles stay, whatever their id or name', () => {
  for (const meta of [
    { id: 'tt12735488', name: 'Kalki 2898-AD' },
    { id: 'kitsu:1234', name: 'Delete' },
    { id: 'dl:42', name: 'Download Days' },
    { id: 'tt1', name: 'All Clear' },
    { id: 'tt2', name: 'Deleted Scenes' },
    { id: 'tt3', name: 'The Purge: Election Year' },
    { id: 'tt4', name: 'Delete History' },
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

test('a stream that would delete is never offered, however it is labelled', () => {
  assert.equal(
    isActionStream({ url: 'https://mf.test/streaming_provider/s/delete_all_watchlist?provider=torbox', name: 'TorBox' }),
    true,
  )
  assert.equal(isActionStream({ url: 'https://cdn.test/v.mkv', description: '🚨💀⚠ Delete all files in TorBox watchlist.' }), true)
  assert.equal(isActionStream({ url: 'https://cdn.test/Delete.History.2024.1080p.mkv', name: 'Delete History' }), false)
  assert.equal(isActionStream({ url: 'https://cdn.test/Deleted.Scenes.mkv', title: 'Deleted Scenes' }), false)
})

test('addon streams are filtered as they are read', () => {
  const streams = normalizeStreamsResponse({
    streams: [
      { url: 'https://cdn.test/film.mkv', name: 'Film' },
      { url: 'https://mf.test/streaming_provider/s/delete_all_watchlist?provider=torbox', name: 'TorBox' },
    ],
  })
  assert.deepEqual(
    streams.map((stream) => stream.url),
    ['https://cdn.test/film.mkv'],
  )
})
