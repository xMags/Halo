import assert from 'node:assert/strict'
import test from 'node:test'
import { languageCodeVariants, languageMatches } from '../src/subtitles/languages'

test('a track tagged with a two-letter or regional code matches the three-letter preference', () => {
  assert.equal(languageMatches('en', 'eng'), true)
  assert.equal(languageMatches('en-US', 'eng'), true)
  assert.equal(languageMatches('EN_gb', 'eng'), true)
  assert.equal(languageMatches('de', 'ger'), true)
  assert.equal(languageMatches('deu', 'ger'), true)
  assert.equal(languageMatches('English', 'eng'), true)
})

test('different languages do not match, including ones that share letters', () => {
  assert.equal(languageMatches('por', 'eng'), false)
  assert.equal(languageMatches('es', 'eng'), false)
  assert.equal(languageMatches('und', 'eng'), false)
  assert.equal(languageMatches('enm', 'eng'), false)
})

test('Brazilian Portuguese stays apart from Portuguese', () => {
  assert.equal(languageMatches('pt-BR', 'pob'), true)
  assert.equal(languageMatches('pt', 'por'), true)
  assert.equal(languageMatches('pt-BR', 'por'), false)
  assert.equal(languageMatches('pt', 'pob'), false)
})

test('a preference lists every spelling of its language, its own first', () => {
  assert.deepEqual(languageCodeVariants('eng'), ['eng', 'en'])
  assert.deepEqual(languageCodeVariants('ger'), ['ger', 'deu', 'de'])
  assert.deepEqual(languageCodeVariants('pob'), ['pob', 'pt-br'])
  assert.deepEqual(languageCodeVariants(''), [])
})
