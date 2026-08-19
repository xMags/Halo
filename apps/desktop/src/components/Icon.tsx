import type { ReactNode } from 'react'

/**
 * Inline icon set (feather-style strokes) — a fixed handful of glyphs doesn't
 * justify an icon-font dependency, and inline SVG inherits `currentColor`, so
 * a row's hover colour carries its icon without a second rule.
 *
 * Glyphs are stroked on a 24-unit grid. `play` and `pause` are the exceptions:
 * they are filled shapes, so they set their own fill and clear the stroke.
 */

const GLYPHS = {
  home: <path d="M3 9.8 12 3.2l9 6.6V20a1.4 1.4 0 0 1-1.4 1.4H15V14.4H9v7H4.4A1.4 1.4 0 0 1 3 20Z" />,
  search: (
    <>
      <circle cx="11" cy="11" r="7.2" />
      <line x1="21" y1="21" x2="16.4" y2="16.4" />
    </>
  ),
  bookmark: <path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" />,
  sliders: (
    <>
      <line x1="4" y1="21" x2="4" y2="14" />
      <line x1="4" y1="10" x2="4" y2="3" />
      <line x1="12" y1="21" x2="12" y2="12" />
      <line x1="12" y1="8" x2="12" y2="3" />
      <line x1="20" y1="21" x2="20" y2="16" />
      <line x1="20" y1="12" x2="20" y2="3" />
      <line x1="1.5" y1="14" x2="6.5" y2="14" />
      <line x1="9.5" y1="8" x2="14.5" y2="8" />
      <line x1="17.5" y1="16" x2="22.5" y2="16" />
    </>
  ),
  chevronLeft: <polyline points="15 18 9 12 15 6" />,
  chevronRight: <polyline points="9 18 15 12 9 6" />,
  chevronDown: <polyline points="6 9 12 15 18 9" />,
  play: <polygon points="6 3 20 12 6 21" fill="currentColor" stroke="none" />,
  pause: (
    <>
      <rect x="6" y="4" width="4" height="16" rx="1" fill="currentColor" stroke="none" />
      <rect x="14" y="4" width="4" height="16" rx="1" fill="currentColor" stroke="none" />
    </>
  ),
  /** Seek back 10 s — the arrow wraps counter-clockwise. */
  replay: (
    <>
      <polyline points="11 5 5 11 11 17" />
      <path d="M5 11h9a5 5 0 0 1 0 10h-3" />
    </>
  ),
  /** Seek forward 10 s. */
  forward: (
    <>
      <polyline points="13 5 19 11 13 17" />
      <path d="M19 11h-9a5 5 0 0 0 0 10h3" />
    </>
  ),
  volume: (
    <>
      <polygon points="4 9 8 9 13 5 13 19 8 15 4 15" />
      <path d="M17 9.5a4 4 0 0 1 0 5" />
    </>
  ),
  volumeOff: (
    <>
      <polygon points="4 9 8 9 13 5 13 19 8 15 4 15" />
      <line x1="17" y1="9" x2="22" y2="15" />
      <line x1="22" y1="9" x2="17" y2="15" />
    </>
  ),
  enterFullscreen: (
    <>
      <polyline points="4 9 4 4 9 4" />
      <polyline points="20 9 20 4 15 4" />
      <polyline points="4 15 4 20 9 20" />
      <polyline points="20 15 20 20 15 20" />
    </>
  ),
  exitFullscreen: (
    <>
      <polyline points="9 4 4 4 4 9" />
      <polyline points="15 4 20 4 20 9" />
      <polyline points="9 20 4 20 4 15" />
      <polyline points="15 20 20 20 20 15" />
    </>
  ),
  x: (
    <>
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </>
  ),
  check: <polyline points="4 12.5 9.5 18 20 6.5" />,
  eye: (
    <>
      <path d="M1.8 12s3.8-7 10.2-7 10.2 7 10.2 7-3.8 7-10.2 7S1.8 12 1.8 12z" />
      <circle cx="12" cy="12" r="2.8" />
    </>
  ),
  eyeOff: (
    <>
      <path d="M1.8 12s3.8-7 10.2-7 10.2 7 10.2 7-3.8 7-10.2 7S1.8 12 1.8 12z" />
      <circle cx="12" cy="12" r="2.8" />
      <line x1="3.5" y1="20.5" x2="20.5" y2="3.5" />
    </>
  ),
  globe: (
    <>
      <circle cx="12" cy="12" r="9.2" />
      <line x1="2.8" y1="12" x2="21.2" y2="12" />
      <path d="M12 2.8a14 14 0 0 1 3.7 9.2 14 14 0 0 1-3.7 9.2 14 14 0 0 1-3.7-9.2A14 14 0 0 1 12 2.8z" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <polyline points="12 7 12 12 15.5 14" />
    </>
  ),
  lock: (
    <>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </>
  ),
  refresh: (
    <>
      <path d="M20.5 12a8.5 8.5 0 1 1-2.5-6" />
      <polyline points="19 3 19 8 14 8" />
    </>
  ),
} satisfies Record<string, ReactNode>

export type IconName = keyof typeof GLYPHS

export function Icon({ name, size = 17 }: { name: IconName; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      style={{ flex: '0 0 auto' }}
    >
      {GLYPHS[name]}
    </svg>
  )
}
