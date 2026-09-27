import assert from 'node:assert/strict'
import test from 'node:test'
import {
  bufferingIndicatorDelayMs,
  bufferingIndicatorHoldMs,
  classifyVideoQuality,
  resolvePlayerShortcut,
  scrubPreviewOffset,
  shouldRequestScrubPreview,
  shouldRunUpNextCountdown,
  subtitlePreviewMetrics,
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

test('up and down change volume, and keep acting while held', () => {
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'ArrowUp' })), 'volume-up')
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'ArrowDown', repeat: true })), 'volume-down')
  assert.equal(resolvePlayerShortcut(shortcut({ code: 'KeyF', repeat: true })), null)
})

test('quality is classified by pixels, and dynamic range replaces the qualifier', () => {
  const video = (width: number, height: number, gamma: string | null = null, dolbyVision = false) =>
    classifyVideoQuality({ width, height, gamma, dolbyVision })
  assert.deepEqual(video(3840, 1600), { tier: '4K', detail: 'ULTRA HD' })
  assert.deepEqual(video(1920, 800), { tier: '1080P', detail: 'FULL HD' })
  assert.deepEqual(video(1280, 720, 'pq'), { tier: '720P', detail: 'HDR' })
  assert.deepEqual(video(3840, 2160, 'pq', true), { tier: '4K', detail: 'DOLBY VISION' })
  assert.deepEqual(video(720, 480, 'hlg'), { tier: 'SD', detail: 'HLG' })
  assert.equal(video(0, 0), null)
})

test('the loading indicator waits mid-playback, not at open, and never blinks', () => {
  assert.equal(bufferingIndicatorDelayMs(false), 0)
  assert.equal(bufferingIndicatorDelayMs(true), 350)
  assert.equal(bufferingIndicatorHoldMs(120), 380)
  assert.equal(bufferingIndicatorHoldMs(900), 0)
})

test('the subtitle preview scales its size, outline and shadow together', () => {
  assert.deepEqual(subtitlePreviewMetrics(100, 'normal'), { fontSize: 15, outlineWidth: 1.4, shadowOffset: 2 })
  assert.deepEqual(subtitlePreviewMetrics(200, 'thick'), { fontSize: 30, outlineWidth: 4.4, shadowOffset: 4 })
  assert.equal(subtitlePreviewMetrics(150, 'none').outlineWidth, 0)
})

test('scrub previews skip tiny moves and keep the card inside the track', () => {
  assert.equal(shouldRequestScrubPreview(10, null), true)
  assert.equal(shouldRequestScrubPreview(10.1, 10), false)
  assert.equal(shouldRequestScrubPreview(10.3, 10), true)
  assert.equal(shouldRequestScrubPreview(-1, null), false)
  assert.equal(scrubPreviewOffset(50, 200, 1000), 0)
  assert.equal(scrubPreviewOffset(500, 200, 1000), 400)
  assert.equal(scrubPreviewOffset(990, 200, 1000), 800)
  assert.equal(scrubPreviewOffset(50, 200, 150), 0)
})
