import assert from 'node:assert/strict'
import test from 'node:test'
import { cardArtworkUrl, metahubPosterUrl } from '../src/components/artworkUrl.ts'

test('a TMDB original image is asked for at card size', () => {
  assert.equal(
    cardArtworkUrl('https://image.tmdb.org/t/p/original/n8Df1H1TmcmZfjGgWh5uQyRFzHy.jpg'),
    'https://image.tmdb.org/t/p/w500/n8Df1H1TmcmZfjGgWh5uQyRFzHy.jpg',
  )
  assert.equal(
    cardArtworkUrl('https://image.tmdb.org/t/p/original/a.jpg', 342),
    'https://image.tmdb.org/t/p/w342/a.jpg',
  )
})

test('other images and TMDB sizes already chosen are left alone', () => {
  for (const url of [
    'https://image.tmdb.org/t/p/w342/a.jpg',
    'https://images.metahub.space/poster/medium/tt1/img',
    'https://m.media-amazon.com/images/M/x._V1_UY396_.jpg',
    'http://image.tmdb.org/t/p/original/a.jpg',
    'https://example.com/image.tmdb.org/t/p/original/a.jpg',
  ]) {
    assert.equal(cardArtworkUrl(url), url)
  }
})

test('an IMDb title falls back on its metahub poster; other ids have none', () => {
  assert.equal(metahubPosterUrl('tt12735488'), 'https://images.metahub.space/poster/medium/tt12735488/img')
  for (const id of [undefined, '', 'tt', 'tmdb:1234', 'mf:42', 'tt1:1:2', 'xtt1']) {
    assert.equal(metahubPosterUrl(id), undefined)
  }
})
