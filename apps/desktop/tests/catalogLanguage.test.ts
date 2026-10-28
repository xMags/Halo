import assert from 'node:assert/strict'
import test from 'node:test'
import { catalogExtra, languageChoices } from '../src/home/catalogLanguage.ts'

const tmdbLanguage = {
  extra: [{ name: 'genre', options: ['English', 'Hindi', 'Tamil', 'Telugu', 'French', 'German', 'Japanese', 'Korean'] }],
}
const cinemetaTop = { extra: [{ name: 'genre', options: ['Action', 'Drama', 'Comedy', 'Romance'] }, { name: 'skip' }] }
const tmdbYear = { extra: [{ name: 'genre', options: ['2026', '2025', '2024'] }] }

test('a catalog whose genre choices are languages offers them', () => {
  assert.deepEqual(languageChoices(tmdbLanguage), tmdbLanguage.extra[0]!.options)
  assert.equal(languageChoices(cinemetaTop), null)
  assert.equal(languageChoices(tmdbYear), null)
  assert.equal(languageChoices({}), null)
  assert.equal(languageChoices({ extra: [{ name: 'genre', isRequired: true, options: tmdbLanguage.extra[0]!.options }] }), null)
})

test('the account language narrows only a language catalog that offers it', () => {
  assert.deepEqual(catalogExtra(tmdbLanguage, 'Tamil'), { genre: 'Tamil' })
  assert.deepEqual(catalogExtra(tmdbLanguage, 'Klingon'), {})
  assert.deepEqual(catalogExtra(tmdbLanguage, undefined), {})
  assert.deepEqual(catalogExtra(cinemetaTop, 'Hindi'), {})
})
