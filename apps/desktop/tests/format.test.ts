import assert from 'node:assert/strict'
import test from 'node:test'
import { videoIdTag } from '../src/format.ts'

test('a video id equal to its meta id is a movie', () => {
  assert.equal(videoIdTag('tt0111161', 'tt0111161'), 'MOVIE')
})

test('season and episode after the meta id become a zero-padded tag', () => {
  assert.equal(videoIdTag('tt0944947:1:2', 'tt0944947'), 'S01E02')
  assert.equal(videoIdTag('tt2861424:0:4', 'tt2861424'), 'S00E04')
  assert.equal(videoIdTag('tt0903747:5:16', 'tt0903747'), 'S05E16')
})

test('meta ids containing colons are stripped as a prefix, not split', () => {
  assert.equal(videoIdTag('kitsu:1376:3:7', 'kitsu:1376'), 'S03E07')
  // kitsu episodes carry no season.
  assert.equal(videoIdTag('kitsu:1376:5', 'kitsu:1376'), 'SERIES')
})

test('episode ids that do not parse fall back to SERIES', () => {
  assert.equal(videoIdTag('tt0944947:x:2', 'tt0944947'), 'SERIES')
  assert.equal(videoIdTag('tt0944947:1:2:3', 'tt0944947'), 'SERIES')
  assert.equal(videoIdTag('tt0944947:', 'tt0944947'), 'SERIES')
})
