export type PlayerShortcut =
  | 'toggle-pause'
  | 'seek-back'
  | 'seek-forward'
  | 'toggle-fullscreen'
  /** Crop the picture to fill the window (mpv panscan). */
  | 'toggle-fill'
  /** Five points of volume either way (native parity). */
  | 'volume-up'
  | 'volume-down'
  | 'escape'

export interface PlayerShortcutInput {
  code: string
  ctrlKey: boolean
  altKey: boolean
  shiftKey: boolean
  metaKey: boolean
  repeat: boolean
  interactiveTarget: boolean
}

/** Held-down keys that keep acting: seeking and volume, never toggles. */
const REPEATABLE_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'])

/** Resolves only unmodified player-level shortcuts that the focused control does not own. */
export function resolvePlayerShortcut(input: PlayerShortcutInput): PlayerShortcut | null {
  if (input.ctrlKey || input.altKey || input.shiftKey || input.metaKey) {
    return null
  }

  // Escape peels player layers even when a button or slider currently owns
  // focus. Other shortcuts leave focused controls to their native keyboard
  // behavior, preventing Space or arrows from acting twice.
  if (input.code === 'Escape') return input.repeat ? null : 'escape'
  if (input.interactiveTarget) return null
  if (input.repeat && !REPEATABLE_KEYS.has(input.code)) return null

  switch (input.code) {
    case 'Space':
      return 'toggle-pause'
    case 'ArrowLeft':
      return 'seek-back'
    case 'ArrowRight':
      return 'seek-forward'
    case 'ArrowUp':
      return 'volume-up'
    case 'ArrowDown':
      return 'volume-down'
    case 'KeyF':
      return 'toggle-fullscreen'
    case 'KeyZ':
      return 'toggle-fill'
    default:
      return null
  }
}

/** mpv renders into the whole HWND, so windowed playback reserves the app title bar explicitly. */
export function videoTopMarginRatio(fullscreen: boolean, windowHeight: number, titleBarHeight: number): number {
  if (fullscreen || windowHeight <= 0 || titleBarHeight <= 0) return 0
  return Math.min(1, titleBarHeight / windowHeight)
}

export function shouldRunUpNextCountdown(visible: boolean, endReached: boolean): boolean {
  return visible && endReached
}

export function upNextCancelAction(endReached: boolean): 'dismiss' | 'exit' {
  return endReached ? 'exit' : 'dismiss'
}

/** The player badge: a resolution token and the qualifier after it. */
export interface VideoQualityBadge {
  tier: string
  detail: string
}

const QUALITY_TIERS: ReadonlyArray<{ minimumPixels: number; tier: string; detail: string }> = [
  { minimumPixels: 5_500_000, tier: '4K', detail: 'ULTRA HD' },
  { minimumPixels: 3_000_000, tier: '1440P', detail: 'QHD' },
  { minimumPixels: 1_400_000, tier: '1080P', detail: 'FULL HD' },
  { minimumPixels: 600_000, tier: '720P', detail: 'HD' },
]

/**
 * Classifies on pixel count, so scope framing keeps its tier: a 2.39:1 film
 * mastered at 4K is 3840×1600, which a height rule would call 1080p. Dynamic
 * range replaces the resolution qualifier rather than joining it. Null when
 * there is no picture to describe yet. (Native: ClassifyVideoQuality.)
 */
export function classifyVideoQuality(video: {
  width: number
  height: number
  gamma: string | null
  dolbyVision: boolean
}): VideoQualityBadge | null {
  if (!(video.width > 0) || !(video.height > 0)) return null
  const pixels = video.width * video.height
  const match = QUALITY_TIERS.find((tier) => pixels >= tier.minimumPixels)
  const badge = match ? { tier: match.tier, detail: match.detail } : { tier: 'SD', detail: '' }
  // Dolby Vision outranks the transfer: its base layer is usually PQ.
  if (video.dolbyVision) return { ...badge, detail: 'DOLBY VISION' }
  if (video.gamma === 'pq') return { ...badge, detail: 'HDR' }
  if (video.gamma === 'hlg') return { ...badge, detail: 'HLG' }
  return badge
}

/**
 * How long a stall must last before the loading indicator shows. A cached
 * seek resolves in tens of milliseconds and a marginal connection makes the
 * cache flag flap, so showing either at once reads as a glitch. Opening a file
 * is exempt: the surface is still black.
 */
export function bufferingIndicatorDelayMs(firstFrameReady: boolean): number {
  return firstFrameReady ? 350 : 0
}

/** How much longer a shown indicator must stay up, so it never blinks. */
export function bufferingIndicatorHoldMs(shownForMs: number): number {
  return Math.max(0, 500 - shownForMs)
}

/**
 * The subtitle preview drawn at a 15px base scaled like mpv's sub-scale, with
 * the outline and shadow growing with it the way libass scales them.
 */
export function subtitlePreviewMetrics(
  scalePercent: number,
  outline: 'none' | 'thin' | 'normal' | 'thick',
): { fontSize: number; outlineWidth: number; shadowOffset: number } {
  const widths = { none: 0, thin: 0.8, normal: 1.4, thick: 2.2 }
  const scale = scalePercent / 100
  return { fontSize: 15 * scale, outlineWidth: widths[outline] * scale, shadowOffset: 2 * scale }
}

/** Preview decodes closer together than this land on the same keyframe. */
export const SCRUB_PREVIEW_MIN_DELTA_SEC = 0.25

export function shouldRequestScrubPreview(seconds: number, lastRequested: number | null): boolean {
  if (!Number.isFinite(seconds) || seconds < 0) return false
  return lastRequested === null || Math.abs(seconds - lastRequested) >= SCRUB_PREVIEW_MIN_DELTA_SEC
}

/** Centres the preview card on the pointer, held inside the track. */
export function scrubPreviewOffset(pointerX: number, cardWidth: number, hostWidth: number): number {
  const rightmost = hostWidth - cardWidth
  if (!Number.isFinite(pointerX) || rightmost <= 0) return 0
  return Math.min(rightmost, Math.max(0, pointerX - cardWidth / 2))
}
