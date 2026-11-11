import assert from 'node:assert/strict'
import test from 'node:test'
import type { MetaPreview } from '../src/index'
import {
  byRelevance,
  hasNoPoster,
  interleave,
  nameRelevance,
  refineResults,
  searchProgress,
  uniqueMetas,
  type SearchTarget,
} from '../src/search/results'

const target = (key: string, addonName: string, type = 'movie'): SearchTarget => ({
  key,
  addonId: key,
  addonName,
  type,
  id: 'search',
  title: 'Search',
})
const meta = (id: string, type = 'movie'): MetaPreview => ({ id, type, name: id })

const cinemetaMovies = target('c/movie', 'Cinemeta')
const cinemetaSeries = target('c/series', 'Cinemeta', 'series')
const tmdbMovies = target('t/movie', 'TMDB')

test('rows appear as their catalogs answer, in addon order', () => {
  const progress = searchProgress(
    [cinemetaMovies, cinemetaSeries, tmdbMovies],
    [{ status: 'pending' }, { status: 'done', metas: [meta('s1', 'series')] }, { status: 'done', metas: [meta('m9')] }],
  )
  assert.deepEqual(progress.groups.map((g) => g.key), ['c/series', 't/movie'])
  assert.equal(progress.pending, 1)
})

test('the top match waits for earlier catalogs, so it never swaps once shown', () => {
  const targets = [cinemetaMovies, tmdbMovies]
  assert.equal(searchProgress(targets, [{ status: 'pending' }, { status: 'done', metas: [meta('m9')] }]).topMatch, null)
  assert.equal(searchProgress(targets, [{ status: 'done', metas: [meta('m1')] }, { status: 'pending' }]).topMatch?.id, 'm1')
  // An earlier catalog that failed or found nothing passes the choice on.
  assert.equal(searchProgress(targets, [{ status: 'failed' }, { status: 'done', metas: [meta('m9')] }]).topMatch?.id, 'm9')
  assert.equal(searchProgress(targets, [{ status: 'done', metas: [] }, { status: 'done', metas: [meta('m9')] }]).topMatch?.id, 'm9')
})

test('failed catalogs are reported, not dropped silently', () => {
  const progress = searchProgress([cinemetaMovies, tmdbMovies], [{ status: 'failed' }, { status: 'done', metas: [] }])
  assert.deepEqual(progress.failed.map((t) => t.addonName), ['Cinemeta'])
  assert.deepEqual(progress.groups, [])
  assert.equal(progress.total, 2)
  assert.equal(progress.pending, 0)
})

test('a catalog that lists a title twice shows it once', () => {
  assert.deepEqual(uniqueMetas([meta('a'), meta('a'), meta('a', 'series')]).map((m) => `${m.type}:${m.id}`), [
    'movie:a',
    'series:a',
  ])
})

const title = (id: string, type = 'movie'): MetaPreview => ({ id, type, name: id })
const ids = (metas: MetaPreview[]) => metas.map((meta) => `${meta.type}:${meta.id}`)

test('interleave takes turns between the lists and keeps each title once', () => {
  const cinemeta = [title('tt1'), title('tt2'), title('tt3'), title('tt4')]
  const tmdb = [title('tt2'), title('tt9')]
  const series = [title('tt5', 'series')]

  assert.deepEqual(ids(interleave([cinemeta, tmdb, series])), [
    'movie:tt1',
    'movie:tt2',
    'series:tt5',
    'movie:tt9',
    'movie:tt3',
    'movie:tt4',
  ])
})

test('interleave leaves out what is shown elsewhere', () => {
  assert.deepEqual(ids(interleave([[title('tt1'), title('tt2')]], [title('tt1')])), ['movie:tt2'])
})

const dated = (id: string, name: string, releaseInfo?: string, type = 'movie'): MetaPreview => ({
  id,
  type,
  name,
  ...(releaseInfo ? { releaseInfo } : {}),
})

test('interleave merges one title named by two addons with different ids', () => {
  const cinemeta = [dated('tt1160419', 'Dune', '2021'), dated('tt15239678', 'Dune: Part Two', '2024')]
  const tmdb = [dated('tmdb:438631', 'Dune', '2021'), dated('tmdb:693134', 'Dune Part Two', '2024')]
  const series = [dated('tt10466872', 'Dune: Prophecy', '2024–', 'series'), dated('tmdb:90228', 'Dune: Prophecy', '2024-2025', 'series')]

  // The better-ranked copy stays: Cinemeta's, which comes first.
  assert.deepEqual(ids(interleave([cinemeta, tmdb, series])), [
    'movie:tt1160419',
    'series:tt10466872',
    'movie:tt15239678',
  ])
})

test('interleave keeps same-named titles from different years or kinds, and any without a year', () => {
  const lists = [
    [dated('tt1160419', 'Dune', '2021'), dated('tt0087182', 'Dune', '1984')],
    [dated('tt0142032', 'Dune', '2000', 'series'), dated('tmdb:1', 'Dune'), dated('tmdb:2', 'Dune')],
  ]
  assert.deepEqual(ids(interleave(lists)), [
    'movie:tt1160419',
    'series:tt0142032',
    'movie:tt0087182',
    'movie:tmdb:1',
    'movie:tmdb:2',
  ])
})

test('interleave leaves out a copy from another addon of what is shown above', () => {
  const top = dated('tt1160419', 'Dune', '2021')
  assert.deepEqual(ids(interleave([[dated('tmdb:438631', 'Dune', '2021'), title('tt9')]], [top])), ['movie:tt9'])
})

const named = (id: string, name: string, type = 'movie'): MetaPreview => ({ id, type, name })

test('names that answer the query come first, each level keeping the order given', () => {
  const merged = [
    named('1', 'Doctor Stranger', 'series'),
    named('2', 'Doctor Strange in the Multiverse of Madness'),
    named('3', 'Amuthu Dosthara', 'series'),
    named('4', 'Dr. Strangelove'),
    named('5', 'Doctor Strange'),
    named('6', 'The Strange Case of Doctor Rx'),
  ]
  assert.deepEqual(
    byRelevance(merged, 'doctor strange').map((meta) => meta.id),
    // "Dr. Strangelove" reads as "doctor strangelove", which begins with the query as "Doctor Stranger" does.
    ['5', '2', '1', '4', '6', '3'],
  )
})

test('relevance ignores case, accents and punctuation', () => {
  assert.equal(nameRelevance('Amélie', 'amelie'), 0)
  assert.equal(nameRelevance('Spider-Man: No Way Home', 'spider man'), 1)
  assert.equal(nameRelevance('WALL·E', 'wall e'), 0)
})

test('with a query, the top match is the best-named first title, not the first catalog to answer', () => {
  const targets = [cinemetaMovies, cinemetaSeries, tmdbMovies]
  const film = named('m1', 'El Camino: A Breaking Bad Movie')
  const show = named('s1', 'Breaking Bad', 'series')
  const other = named('m9', 'Breaking Bad Habits')
  const done = (meta: MetaPreview) => ({ status: 'done' as const, metas: [meta] })
  // All answered: the series named exactly what was typed wins over the film asked first.
  assert.equal(searchProgress(targets, [done(film), done(show), done(other)], 'breaking bad').topMatch?.id, 's1')
  // Still waiting on a later catalog: a title named exactly what was typed is shown already, and stays...
  assert.equal(searchProgress(targets, [done(film), done(show), { status: 'pending' }], 'breaking bad').topMatch?.id, 's1')
  // ...but one that only begins with it waits, since a later catalog may hold the exact name...
  assert.equal(searchProgress(targets, [done(other), { status: 'pending' }, { status: 'pending' }], 'breaking bad').topMatch, null)
  assert.equal(searchProgress(targets, [done(other), done(film), done(show)], 'breaking bad').topMatch?.id, 's1')
  // ...and nothing is shown while an earlier catalog is out, which could still name a better one.
  assert.equal(searchProgress(targets, [{ status: 'pending' }, done(show), done(other)], 'breaking bad').topMatch, null)
  assert.equal(searchProgress(targets, [done(film), { status: 'pending' }, done(other)], 'breaking bad').topMatch, null)
  // A name that merely holds the words waits for everyone.
  assert.equal(searchProgress(targets, [done(film), { status: 'pending' }, { status: 'pending' }], 'breaking bad').topMatch, null)
  // A tie goes to the earlier catalog.
  assert.equal(searchProgress(targets, [done(other), done(film), done(film)], 'nothing alike').topMatch?.id, 'm9')
})

const full = (id: string, name: string, extra: Partial<MetaPreview> = {}): MetaPreview => ({
  id,
  type: 'movie',
  name,
  releaseInfo: '2016',
  poster: `https://image.tmdb.org/t/p/w500/${id}.jpg`,
  ...extra,
})

test('results that share no word with the query go, unless nothing names it', () => {
  const results = [full('tmdb:1', 'Doctor Strange'), full('tmdb:2', 'Faust and the Devil'), full('tmdb:3', 'Dr. Strange')]
  assert.deepEqual(
    refineResults(results, 'doctor strange').map((meta) => meta.id),
    ['tmdb:1', 'tmdb:3'],
  )
  // An addon answering in another language names nothing: all of it stays.
  const localized = [full('tmdb:1', 'डॉक्टर स्ट्रेंज'), full('tmdb:2', 'कुछ और')]
  assert.equal(refineResults(localized, 'doctor strange').length, 2)
})

test('a title with neither a year nor artwork goes, and one missing either comes last', () => {
  const noPoster = 'https://image.tmdb.org/t/p/w500null'
  const results = [
    full('tmdb:1', 'Doctor Strange Love', { poster: noPoster }),
    full('tmdb:2', 'Untitled Doctor Strange Movie', { poster: noPoster, releaseInfo: '' }),
    full('tt3', 'Doctor Strange 3', { releaseInfo: undefined, poster: 'https://images.metahub.space/poster/small/tt3/img' }),
    full('tt4', 'Doctor Strange: Multiversal Madness', { poster: 'https://images.metahub.space/poster/small/tt4/img' }),
    full('tmdb:5', 'Doctor Strange in the Multiverse of Madness'),
    full('tmdb:6', 'The Strange Case of Doctor Rx'),
  ]
  // tt3 and tt4 have poster links, and the links are found to be dead.
  const dead = new Set(['movie:tt3', 'movie:tt4'])
  assert.deepEqual(
    refineResults(results, 'doctor strange', dead).map((meta) => meta.id),
    ['tmdb:5', 'tmdb:6', 'tmdb:1', 'tt4'],
  )
  // Before the links are known to be dead, those titles are judged on what the answer says.
  assert.deepEqual(
    refineResults(results, 'doctor strange').map((meta) => meta.id),
    ['tt4', 'tmdb:5', 'tmdb:6', 'tmdb:1', 'tt3'],
  )
})

test('the TMDB addon\'s link to a missing image is no poster', () => {
  assert.equal(hasNoPoster({ poster: 'https://image.tmdb.org/t/p/w500null' }), true)
  assert.equal(hasNoPoster({ poster: 'https://image.tmdb.org/t/p/w500/abc.jpg' }), false)
  assert.equal(hasNoPoster({}), true)
})

test('two addons\' copies of a title are one, by IMDb id, and the kept copy gains what the other knew', () => {
  const cinemeta: MetaPreview = {
    id: 'tt0035767',
    type: 'movie',
    name: 'The Crime Doctor\'s Strangest Case',
    releaseInfo: '1943',
    poster: 'https://images.metahub.space/poster/small/tt0035767/img',
  }
  const tmdb = {
    id: 'tmdb:84650',
    type: 'movie',
    name: 'Crime Doctor: Strangest Case',
    releaseInfo: '1944',
    imdbRating: '6.3',
    poster: 'https://image.tmdb.org/t/p/w500/4at.jpg',
    imdb_id: 'tt0035767',
  } as MetaPreview
  const merged = interleave([[cinemeta], [tmdb]])
  assert.equal(merged.length, 1)
  assert.equal(merged[0]!.id, 'tt0035767')
  assert.equal(merged[0]!.imdbRating, '6.3')
  assert.equal(merged[0]!.poster, 'https://image.tmdb.org/t/p/w500/4at.jpg')
})

test('with a screenful of complete results, the ones without artwork are left out', () => {
  const complete = Array.from({ length: 8 }, (_, index) => full(`tmdb:${index}`, `Doctor Strange ${index}`))
  const artless = full('tmdb:99', 'Doctor Strange Love', { poster: 'https://image.tmdb.org/t/p/w500null' })
  assert.equal(refineResults([...complete, artless], 'doctor strange').some((meta) => meta.id === 'tmdb:99'), false)
  // One short of a screenful: the artless one is still worth listing, last.
  assert.equal(refineResults([...complete.slice(1), artless], 'doctor strange').at(-1)?.id, 'tmdb:99')
})

test('a short form is the word it stands for', () => {
  assert.equal(nameRelevance('Dr. Strange', 'doctor strange'), 0)
  assert.equal(nameRelevance('Doctor Strange', 'dr strange'), 0)
  assert.equal(nameRelevance('Mr. Robot', 'mister robot'), 0)
})

test('with a screenful naming every word, titles naming only some are left out', () => {
  const named = Array.from({ length: 8 }, (_, index) => full(`tmdb:${index}`, `Doctor Strange ${index}`))
  const loose = [full('tmdb:90', 'Doctor Sleep'), full('tmdb:91', 'Strangers')]
  assert.deepEqual(
    refineResults([...named, ...loose], 'doctor strange').map((meta) => meta.id),
    named.map((meta) => meta.id),
  )
  // One short of a screenful, the loose ones are still worth listing, after the rest.
  assert.equal(refineResults([...named.slice(1), ...loose], 'doctor strange').length, 9)
})

test('an entry made from a file name is the title it names, and the IMDb copy leads', () => {
  const fromFile: MetaPreview = {
    id: 'mf1706190',
    type: 'movie',
    name: 'Doctor Strange (2016)',
    poster: 'https://mediafusion.test/poster/movie/mf1706190.jpg',
  }
  const real: MetaPreview = {
    id: 'tt1211837',
    type: 'movie',
    name: 'Doctor Strange',
    releaseInfo: '2016',
    poster: 'https://live.metahub.space/poster/medium/tt1211837/img',
  }
  // Whichever is ranked first, one card remains, under the IMDb id and without the drawn poster.
  for (const lists of [[[fromFile], [real]], [[real], [fromFile]]]) {
    const merged = interleave(lists)
    assert.equal(merged.length, 1)
    assert.equal(merged[0]!.id, 'tt1211837')
    assert.equal(merged[0]!.name, 'Doctor Strange')
    assert.equal(merged[0]!.poster, real.poster)
  }
  // With no real copy to fold into, it keeps its place with the year out of its name.
  const alone = interleave([[fromFile]])[0]!
  assert.equal(alone.name, 'Doctor Strange')
  assert.equal(alone.releaseInfo, '2016')
})

test('a poster an addon draws for its own entry is no poster', () => {
  assert.equal(hasNoPoster({ poster: 'https://mediafusion.test/poster/movie/mf1706190.jpg' }), true)
  assert.equal(hasNoPoster({ poster: 'https://mediafusion.test/poster/series/mf12.png' }), true)
  assert.equal(hasNoPoster({ poster: 'https://mediafusion.test/poster/movie/mf12.jpg?v=3' }), true)
  // Known by its id too, wherever the addon serves the card from.
  assert.equal(hasNoPoster({ id: 'mf12', poster: 'https://cdn.test/cards/12.jpg' }), true)
  assert.equal(hasNoPoster({ id: 'tt12', poster: 'https://cdn.test/cards/12.jpg' }), false)
  assert.equal(hasNoPoster({ poster: 'https://live.metahub.space/poster/medium/tt1211837/img' }), false)
})
