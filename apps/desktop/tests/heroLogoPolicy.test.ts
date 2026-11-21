import assert from 'node:assert/strict'
import test from 'node:test'
import {
  chooseHeroLogoTreatment,
  heroLogoCoverage,
  heroLogoLuminance,
  isHeroLogoUrl,
} from '../src/browse/detail/heroLogoPolicy.ts'

function assertNear(actual: number | null, expected: number) {
  assert.ok(actual !== null && Math.abs(actual - expected) < 0.001, `${actual} is not about ${expected}`)
}

/** `count` pixels of one straight-alpha RGBA colour. */
function pixels(count: number, rgba: [number, number, number, number]): number[] {
  return Array.from({ length: count }, () => rgba).flat()
}

test('only an absolute https address is fetched', () => {
  assert.equal(isHeroLogoUrl('https://images.metahub.space/logo/medium/tt1/img'), true)
  assert.equal(isHeroLogoUrl('HTTPS://example.com/logo.png'), true)
  assert.equal(isHeroLogoUrl('http://example.com/logo.png'), false)
  assert.equal(isHeroLogoUrl('https://'), false)
  assert.equal(isHeroLogoUrl('https://example.com/a logo.png'), false)
  assert.equal(isHeroLogoUrl('data:image/png;base64,AAAA'), false)
})

test('luminance weighs each visible pixel by its opacity and skips clear ones', () => {
  assertNear(heroLogoLuminance(pixels(4, [255, 255, 255, 255])), 1)
  assertNear(heroLogoLuminance(pixels(4, [0, 0, 0, 255])), 0)
  // Clear pixels count for nothing, whatever colour they hold.
  const mark = [...pixels(1, [255, 255, 255, 255]), ...pixels(3, [0, 0, 0, 0])]
  assertNear(heroLogoLuminance(mark), 1)
  // Half white at full opacity, half black at a third: mostly white.
  const mixed = [...pixels(1, [255, 255, 255, 255]), ...pixels(1, [0, 0, 0, 85])]
  assertNear(heroLogoLuminance(mixed), 0.75)
})

test('a logo too faint to see, or bytes that are not whole pixels, cannot be judged', () => {
  assert.equal(heroLogoLuminance(pixels(1000, [255, 255, 255, 0])), null)
  assert.equal(heroLogoLuminance([255, 255, 255]), null)
  assert.equal(heroLogoLuminance([]), null)
  assert.equal(heroLogoCoverage([1, 2, 3]), null)
})

test('coverage is the mean opacity of the box', () => {
  assert.equal(heroLogoCoverage(pixels(4, [255, 255, 255, 255])), 1)
  assert.equal(heroLogoCoverage([...pixels(1, [255, 255, 255, 255]), ...pixels(3, [0, 0, 0, 0])]), 0.25)
})

test('the dark theme always shows a logo as drawn', () => {
  assert.equal(chooseHeroLogoTreatment(1, 0.3, false), 'original')
  assert.equal(chooseHeroLogoTreatment(null, null, false), 'original')
})

test('the light theme keeps dark logos, inks light marks and names light pictures or unjudged logos', () => {
  assert.equal(chooseHeroLogoTreatment(0.2, 0.3, true), 'original')
  assert.equal(chooseHeroLogoTreatment(0.55, 0.3, true), 'original')
  assert.equal(chooseHeroLogoTreatment(0.9, 0.3, true), 'inked')
  assert.equal(chooseHeroLogoTreatment(0.9, 0.9, true), 'inked')
  assert.equal(chooseHeroLogoTreatment(0.9, 0.95, true), 'name')
  assert.equal(chooseHeroLogoTreatment(0.9, null, true), 'name')
  assert.equal(chooseHeroLogoTreatment(null, 0.3, true), 'name')
})
