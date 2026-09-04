import assert from 'node:assert/strict'
import test from 'node:test'
import {
  resolvePlayerShortcut,
  shouldRunUpNextCountdown,
  upNextCancelAction,
  videoTopMarginRatio,
} from '../src/playerLogic.ts'

const shortcut = (overrides: Partial<Parameters<typeof resolvePlayerShortcut>[0]> = {}) => ({
  code: 'Space',
  ctrlKey: false,
  altKey: false,
  shiftKey: false,
  metaKey: false,
  repeat: false,
  interactiveTarget: false,
  ...overrides,
})

test('player shortcuts ignore modifiers, controls, and repeated toggles', () => {
  assert.equal(resolvePlayerShortcut(shortcut()), 'toggle-pause')
  assert.equal(resolvePlayerShortcut(shortcut({ ctrlKey: true })), null)
  assert.equal(resolvePlayerShortcut(shortcut({ interactiveTarget: true })), null)
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'Escape', interactiveTarget: true })), 'escape')
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'KeyF', repeat: true })), null)
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'ArrowRight', repeat: true })), 'seek-forward')
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'KeyZ' })), 'toggle-fill')
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'KeyZ', repeat: true })), null)
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'KeyZ', ctrlKey: true })), null)
})

test('windowed video reserves only the title bar', () => {
  assert.equal(videoTopMarginRatio(true, 800, 32), 0)
  assert.equal(videoTopMarginRatio(false, 800, 32), 0.04)
  assert.equal(videoTopMarginRatio(false, 0, 32), 0)
})

test('up-next countdown and cancel semantics depend on actual end-of-file', () => {
  assert.equal(shouldRunUpNextCountdown(true, false), false)
  assert.equal(shouldRunUpNextCountdown(true, true), true)
  assert.equal(upNextCancelAction(false), 'dismiss')
  assert.equal(upNextCancelAction(true), 'exit')
})
