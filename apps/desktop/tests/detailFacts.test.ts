import assert from 'node:assert/strict'
import test from 'node:test'
import type { MetaDetail, Stream } from '@halo/core'
import {
  aboutRows,
  creditsOf,
  formatReleaseDate,
  formatRuntime,
  personSearchUrl,
  summarizeAvailability,
  trailerUrl,
} from '../src/browse/detail/detailFacts.ts'

const stream = (title: string, langs: string[] = []): Stream => ({
  url: 'https://example.test/v',
  title,
  subtitles: langs.map((lang, i) => ({ id: `${title}-${i}`, url: 'https://example.test/s', lang })),
})

test('availability groups by the picker tier, best first, gold only for 4K', () => {
  const summary = summarizeAvailability([
    stream('Movie.2025.1080p.WEB-DL.mkv', ['eng']),
    stream('Movie.2025.2160p.WEB-DL.mkv', ['eng', 'hin']),
    stream('Movie.2025.1080p.BluRay.mkv'),
    stream('Movie.2025.480p.mkv'),
    stream('Movie.2025.mkv'),
  ])
  assert.deepEqual(
    summary.groups.map((g) => [g.label, g.count, g.gold]),
    [
      ['4K', 1, true],
      ['FULL HD', 2, false],
      ['SD', 1, false],
      ['OTHER', 1, false],
    ],
  )
  assert.deepEqual(
    summary.groups.map((g) => g.medal),
    [
      { plate: '4K', tag: 'UHD', finish: 'gold' },
      { plate: '1080p', tag: 'FHD', finish: 'silver' },
      { plate: '480p', tag: null, finish: 'plain' },
      { plate: '—', tag: null, finish: 'plain' },
    ],
  )
  assert.equal(summary.total, 5)
  assert.equal(summary.subtitleLanguages, 2)
})

test('availability of nothing is empty, not a crash', () => {
  assert.deepEqual(summarizeAvailability([]), { groups: [], total: 0, subtitleLanguages: 0 })
})

test('runtime reads as hours and minutes, and passes odd text through', () => {
  assert.equal(formatRuntime('150 min'), '2 h 30 min')
  assert.equal(formatRuntime('120 min'), '2 h')
  assert.equal(formatRuntime('45 min'), '45 min')
  assert.equal(formatRuntime(undefined), null)
})

test('credits prefer the pictured cast, close with directors, and never repeat a name', () => {
  const credits = creditsOf({
    cast: ['Plain Name'],
    director: ['Mohit Suri', 'aneet padda'],
    app_extras: {
      cast: [
        { name: 'Ahaan Panday', character: 'Krish Kapoor', photo: 'https://img.test/a.jpg' },
        { name: 'Aneet Padda', character: 'Vaani Batra' },
      ],
    },
  })
  assert.deepEqual(credits, [
    { name: 'Ahaan Panday', role: 'Krish Kapoor', photo: 'https://img.test/a.jpg' },
    { name: 'Aneet Padda', role: 'Vaani Batra', photo: null },
    { name: 'Mohit Suri', role: 'Director', photo: null },
  ])
})

test('a cast card searches IMDb people for the name', () => {
  assert.equal(personSearchUrl(' Aneet Padda '), 'https://www.imdb.com/find/?q=Aneet%20Padda&s=nm')
})

test('the trailer links to YouTube, from either trailer list, and only for a real id', () => {
  assert.equal(trailerUrl({ trailerStreams: [{ ytId: 'PLl99DlL6b4' }] }), 'https://www.youtube.com/watch?v=PLl99DlL6b4')
  assert.equal(
    trailerUrl({ trailers: [{ source: 'clip123456', type: 'Clip' }, { source: 'xyXX8LXiNJ4', type: 'Trailer' }] }),
    'https://www.youtube.com/watch?v=xyXX8LXiNJ4',
  )
  assert.equal(trailerUrl({ trailerStreams: [{ ytId: 'x&list=evil' }] }), null)
  assert.equal(trailerUrl({}), null)
})

test('credits fall back to the plain cast names', () => {
  assert.deepEqual(creditsOf({ cast: ['Tim Robbins'] }), [{ name: 'Tim Robbins', role: null, photo: null }])
})

test('about rows keep only the facts the addon sent', () => {
  const meta = {
    id: 'tt1',
    type: 'movie',
    name: 'X',
    director: ['A'],
    released: '2025-07-18T00:00:00.000Z',
    country: 'India',
    awards: '  ',
  } as MetaDetail
  assert.deepEqual(aboutRows(meta), [
    ['Director', 'A'],
    ['Released', '18 July 2025'],
    ['Country', 'India'],
  ])
  assert.equal(formatReleaseDate('not a date'), null)
})
