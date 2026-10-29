import assert from 'node:assert/strict'
import test from 'node:test'
import type { WatchState } from '@halo/core'
import {
  bufferingIndicatorDelayMs,
  bufferingIndicatorHoldMs,
  classifyVideoQuality,
  resolvePlayerShortcut,
  resumePosition,
  scrubPreviewOffset,
  shouldRequestScrubPreview,
  shouldRunUpNextCountdown,
  subtitleControls,
  subtitleFormat,
  subtitlePreviewMetrics,
  upNextCancelAction,
  videoTopMarginRatio,
} from '../src/player/playerLogic.ts'

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
  assert.deepEqual(video(3840, 1600), { plate: '4K', tag: 'UHD', finish: 'gold' })
  assert.deepEqual(video(1920, 800), { plate: '1080p', tag: 'FHD', finish: 'silver' })
  assert.deepEqual(video(1280, 720, 'pq'), { plate: '720p', tag: 'HDR', finish: 'bronze' })
  assert.deepEqual(video(3840, 2160, 'pq', true), { plate: '4K', tag: 'DV', finish: 'gold' })
  assert.deepEqual(video(720, 480, 'hlg'), { plate: 'SD', tag: 'HLG', finish: 'plain' })
  assert.deepEqual(video(720, 480), { plate: 'SD', tag: null, finish: 'plain' })
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

test('resumePosition opens exactly where the video was left', () => {
  const saved = (positionSec: number, extra: Partial<WatchState> = {}): WatchState => ({
    videoId: 'tt1',
    itemId: 'tt1',
    positionSec,
    durationSec: 10_000,
    watched: false,
    updatedAt: 1,
    ...extra,
  })
  assert.equal(resumePosition(saved(1669), 9949), 1669)
  // Anything recorded is something the title page offers to resume.
  assert.equal(resumePosition(saved(12), 9949), 12)
  // Close to the end still resumes until the row is marked watched.
  assert.equal(resumePosition(saved(9400), 9949), 9400)
  assert.equal(resumePosition(saved(9400, { watched: true }), 9949), null)
  // Nothing saved, nothing to go back to.
  assert.equal(resumePosition(null, 9949), null)
  assert.equal(resumePosition(saved(0), 9949), null)
  assert.equal(resumePosition(saved(1669, { removedAt: 5 }), 9949), null)
  // A shorter cut than the one watched starts from the top.
  assert.equal(resumePosition(saved(6000), 5400), null)
  // An unknown length does not stop the resume.
  assert.equal(resumePosition(saved(1669), 0), 1669)
})

test('subtitle controls grey out what cannot change the selected track', () => {
  assert.equal(subtitleFormat('hdmv_pgs_subtitle'), 'bitmap')
  assert.equal(subtitleFormat('subrip'), 'text')
  assert.equal(subtitleFormat('ASS'), 'styled')
  assert.equal(subtitleFormat(undefined), 'unknown')

  assert.deepEqual(subtitleControls('bitmap', true), { trackStyling: false, looks: false })
  assert.deepEqual(subtitleControls('text', true), { trackStyling: false, looks: true })
  assert.deepEqual(subtitleControls('styled', true), { trackStyling: true, looks: false })
  assert.deepEqual(subtitleControls('styled', false), { trackStyling: true, looks: true })
  assert.deepEqual(subtitleControls('unknown', true), { trackStyling: true, looks: true })
})
