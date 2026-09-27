import assert from 'node:assert/strict'
import test from 'node:test'
import { activeSettingsSection, isScrolledToEnd } from '../src/settingsRail.ts'

test('the rail lights the last section to reach the anchor band', () => {
  assert.equal(activeSettingsSection([0, 400, 900], 150, false), 0)
  assert.equal(activeSettingsSection([-420, -20, 480], 150, false), 1)
  // A boundary inside the band counts, so a section just under the top edge is the one being read.
  assert.equal(activeSettingsSection([-500, 120, 600], 150, false), 1)
  assert.equal(activeSettingsSection([-500, 151, 600], 150, false), 0)
})

test('a form scrolled to its end lights the last section even short of the anchor', () => {
  assert.equal(activeSettingsSection([-900, -300, 400], 150, true), 2)
})

test('an empty form lights the first entry', () => {
  assert.equal(activeSettingsSection([], 150, false), 0)
  assert.equal(activeSettingsSection([], 150, true), 0)
})

test('only a form that could scroll counts as scrolled to its end', () => {
  assert.equal(isScrolledToEnd(0, 600, 600), false)
  assert.equal(isScrolledToEnd(399.5, 1000, 600), true)
  assert.equal(isScrolledToEnd(380, 1000, 600), false)
})
