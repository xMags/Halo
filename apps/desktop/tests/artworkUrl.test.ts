import assert from 'node:assert/strict'
import test from 'node:test'
import { cardArtworkUrl } from '../src/components/artworkUrl.ts'

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
