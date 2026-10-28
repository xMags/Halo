import assert from 'node:assert/strict'
import test from 'node:test'
import { movieVideoId, playableFromPreview } from '../src/browse/playableVideo.ts'

test('a TMDB film plays by the IMDb id its addon names', () => {
  const meta = { id: 'tmdb:969681', behaviorHints: { defaultVideoId: 'tt22084616' } }
  assert.equal(movieVideoId(meta), 'tt22084616')
  assert.equal(playableFromPreview(meta), true)
})

test('a film without a named video plays by its own id', () => {
  assert.equal(movieVideoId({ id: 'tt0111161' }), 'tt0111161')
  assert.equal(movieVideoId({ id: 'tt0111161', behaviorHints: { defaultVideoId: null } }), 'tt0111161')
  assert.equal(movieVideoId({ id: 'tt1', behaviorHints: { defaultVideoId: '  ' } }), 'tt1')
})

test('a card with an id stream addons do not know goes through the title page', () => {
  assert.equal(playableFromPreview({ id: 'tt0111161' }), true)
  assert.equal(playableFromPreview({ id: 'tmdb:969681' }), false)
})
