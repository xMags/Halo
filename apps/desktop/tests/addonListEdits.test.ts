import assert from 'node:assert/strict'
import test from 'node:test'
import { addAddon, moveAddon, removeAddon, sameList } from '../src/settings/addonListEdits.ts'

// What the server has now: "phone" was added on another device after this
// PC last read the list.
const current = ['cinemeta', 'phone', 'torrent']

test('each edit keeps an addon another device added', () => {
  assert.deepEqual(addAddon('subs')(current), ['cinemeta', 'phone', 'torrent', 'subs'])
  assert.deepEqual(removeAddon('torrent')(current), ['cinemeta', 'phone'])
  assert.deepEqual(moveAddon('torrent', 'cinemeta')(current), ['torrent', 'cinemeta', 'phone'])
})

test('an edit about an addon that is no longer there changes nothing', () => {
  assert.deepEqual(addAddon('phone')(current), current)
  assert.deepEqual(removeAddon('gone')(current), current)
  assert.deepEqual(moveAddon('gone', 'cinemeta')(current), current)
  assert.deepEqual(moveAddon('torrent', 'gone')(current), current)
})

test('lists compare by order as well as content', () => {
  assert.equal(sameList(current, [...current]), true)
  assert.equal(sameList(current, ['phone', 'cinemeta', 'torrent']), false)
})
