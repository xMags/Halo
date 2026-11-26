/**
 * Physical pixels of the shorter side of the screen this window is on: what
 * the source ranking compares a picture's height with. The screen's size is
 * in CSS pixels, so it is scaled by the device pixel ratio. 0 (unknown, so no
 * cap) wherever there is no screen to ask, such as tests.
 */
export function screenShortSidePixels(): number {
  if (typeof window === 'undefined' || typeof window.screen === 'undefined') return 0
  const { width, height } = window.screen
  if (!(width > 0) || !(height > 0)) return 0
  const ratio = window.devicePixelRatio
  const scale = Number.isFinite(ratio) && ratio > 0 ? ratio : 1
  return Math.round(Math.min(width, height) * scale)
}
