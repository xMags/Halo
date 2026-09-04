import type { ReactNode } from 'react'

/**
 * The icon set, drawn as inline SVG on a 16 × 16 grid with a 1.1–1.2px stroke
 * in `currentColor` — the design's own icon contract for a non-WinUI target.
 *
 * Inline rather than an icon font: this stroke weight and corner language is
 * specific to the design, and a system font (Segoe Fluent) mixes stroked and
 * filled glyphs at weights that visibly disagree with it. Everything scales
 * from the same viewBox, so a 13px icon keeps the 16px proportions with a
 * proportionally lighter stroke, exactly as the prototype draws them.
 */

interface Spec {
  /** Defaults to the 16-grid. */
  viewBox?: string
  /** Filled glyphs (play, badges, dots) instead of stroked ones. */
  filled?: boolean
  stroke?: number
  body: ReactNode
}

const ICONS = {
  home: { body: <path d="M2.6 7.3 8 2.6l5.4 4.7v5.6a.6.6 0 0 1-.6.6H3.2a.6.6 0 0 1-.6-.6z" /> },
  search: {
    body: (
      <>
        <circle cx="7" cy="7" r="4.4" />
        <path d="M10.3 10.3 14 14" />
      </>
    ),
  },
  library: {
    body: (
      <>
        <rect x="2.3" y="2.8" width="3.4" height="10.4" rx="1" />
        <rect x="6.6" y="2.8" width="3.4" height="10.4" rx="1" />
        <path d="m11.3 3.6 2.1.6-2.1 8.9-2-.6z" />
      </>
    ),
  },
  downloads: { body: <path d="M8 2.4v7.6M5.1 7.4 8 10.3l2.9-2.9M2.6 13.2h10.8" /> },
  settings: {
    body: (
      <>
        <circle cx="8" cy="8" r="2.4" />
        <path d="M8 1.4v1.8M8 12.8v1.8M1.4 8h1.8M12.8 8h1.8M3.5 3.5l1.3 1.3M11.2 11.2l1.3 1.3M12.5 3.5l-1.3 1.3M4.8 11.2l-1.3 1.3" />
      </>
    ),
  },

  play: { viewBox: '0 0 12 12', filled: true, body: <path d="M2 1 11 6 2 11Z" /> },
  pause: {
    filled: true,
    body: (
      <>
        <rect x="4" y="3.4" width="2.8" height="9.2" rx="1" />
        <rect x="9.2" y="3.4" width="2.8" height="9.2" rx="1" />
      </>
    ),
  },
  replay10: { stroke: 1.2, body: <path d="M13.5 8A5.5 5.5 0 1 1 11.8 4M11.6 1.4V4.2H8.8" /> },
  forward10: { stroke: 1.2, body: <path d="M2.5 8A5.5 5.5 0 1 0 4.2 4M4.4 1.4V4.2H7.2" /> },
  volume: { stroke: 1.2, body: <path d="M3 6h2.4L9 3v10L5.4 10H3zM11.4 5.6a3.4 3.4 0 0 1 0 4.8" /> },
  volumeOff: { stroke: 1.2, body: <path d="M3 6h2.4L9 3v10L5.4 10H3zM11.4 6.2l3 3.6M14.4 6.2l-3 3.6" /> },
  enterFullscreen: { stroke: 1.2, body: <path d="M2 6V2h4M14 6V2h-4M2 10v4h4M14 10v4h-4" /> },
  exitFullscreen: { stroke: 1.2, body: <path d="M6 2v4H2M10 2v4h4M6 14v-4H2M10 14v-4h4" /> },
  /** Picture framing: the inner rectangle grows to the frame when filling. */
  fitContain: {
    stroke: 1.2,
    body: (
      <>
        <path d="M1.5 3h13v10h-13z" opacity=".5" />
        <path d="M4.5 5.5h7v5h-7z" />
      </>
    ),
  },
  fitFill: {
    stroke: 1.2,
    body: (
      <>
        <path d="M1.5 3h13v10h-13z" opacity=".5" />
        <path d="M4.5 1h7v14h-7z" />
      </>
    ),
  },

  chevronLeft: { stroke: 1.2, body: <path d="M10 3 5 8l5 5" /> },
  chevronRight: { stroke: 1.2, body: <path d="M6 3l5 5-5 5" /> },
  chevronDown: { stroke: 1.2, body: <path d="M3.5 6l4.5 4.5L12.5 6" /> },
  chevronUp: { stroke: 1.2, body: <path d="M3.5 10l4.5-4.5L12.5 10" /> },
  /** The Detail back button's longer, shallower chevron. */
  back: { stroke: 1.2, body: <path d="M9.6 3.2 4.8 8l4.8 4.8" /> },
  x: { stroke: 1.3, body: <path d="M4 4l8 8M12 4l-8 8" /> },
  check: { stroke: 1.4, body: <path d="M3.2 8.4l3 3 6.6-7" /> },
  retry: { stroke: 1.2, body: <path d="M13 8a5 5 0 1 1-1.7-3.8M13 2.6V5h-2.4" /> },
  more: {
    filled: true,
    body: (
      <>
        <circle cx="3" cy="8" r="1.2" />
        <circle cx="8" cy="8" r="1.2" />
        <circle cx="13" cy="8" r="1.2" />
      </>
    ),
  },
  reorder: {
    viewBox: '0 0 12 16',
    filled: true,
    body: (
      <>
        <circle cx="4" cy="4" r="1.1" />
        <circle cx="8" cy="4" r="1.1" />
        <circle cx="4" cy="8" r="1.1" />
        <circle cx="8" cy="8" r="1.1" />
        <circle cx="4" cy="12" r="1.1" />
        <circle cx="8" cy="12" r="1.1" />
      </>
    ),
  },
  list: { body: <path d="M2.6 4.4h10.8M2.6 8h10.8M2.6 11.6h6.4" /> },
  folder: { body: <path d="M2 4.4h4l1.2 1.4h6.8v7.4H2z" /> },
  copy: {
    stroke: 1.2,
    body: (
      <>
        <rect x="5.4" y="5.4" width="8" height="8" rx="1.4" />
        <path d="M10.6 3.4H3.4a1 1 0 0 0-1 1v7.2" />
      </>
    ),
  },
  trash: { stroke: 1.2, body: <path d="M3.4 5h9.2M6.4 5V3.6h3.2V5M4.6 5l.6 8h5.6l.6-8" /> },
  clock: {
    body: (
      <>
        <circle cx="8" cy="8" r="5.9" />
        <path d="M8 4.6V8l2.4 1.5" />
      </>
    ),
  },
  lock: {
    body: (
      <>
        <rect x="3.4" y="7" width="9.2" height="6.4" rx="1.2" />
        <path d="M5.6 7V5.2a2.4 2.4 0 0 1 4.8 0V7" />
      </>
    ),
  },
  warning: {
    body: (
      <>
        <path d="M8 1.8 15 14H1z" />
        <path d="M8 6v3.4M8 11.2v.8" />
      </>
    ),
  },
  starFilled: {
    filled: true,
    body: <path d="M8 1.6l1.9 4 4.4.5-3.3 3 .9 4.3L8 11.3 4.1 13.4l.9-4.3-3.3-3 4.4-.5z" />,
  },
  star: {
    body: <path d="M8 1.9l1.8 3.8 4.1.5-3.1 2.8.9 4L8 11l-3.7 2 .9-4L2.1 6.2l4.1-.5z" />,
  },
  sun: {
    body: (
      <>
        <circle cx="8" cy="8" r="3.1" />
        <path d="M8 1.4v1.7M8 12.9v1.7M1.4 8h1.7M12.9 8h1.7M3.6 3.6l1.2 1.2M11.2 11.2l1.2 1.2M12.4 3.6l-1.2 1.2M4.8 11.2l-1.2 1.2" />
      </>
    ),
  },
  moon: { body: <path d="M13.2 9.6A5.7 5.7 0 0 1 6.4 2.8a5.7 5.7 0 1 0 6.8 6.8z" /> },
} satisfies Record<string, Spec>

export type IconName = keyof typeof ICONS

export function Icon({ name, size = 16 }: { name: IconName; size?: number }) {
  const spec: Spec = ICONS[name]
  return (
    <svg
      className="icon"
      width={size}
      height={size}
      viewBox={spec.viewBox ?? '0 0 16 16'}
      fill={spec.filled ? 'currentColor' : 'none'}
      stroke={spec.filled ? undefined : 'currentColor'}
      strokeWidth={spec.filled ? undefined : (spec.stroke ?? 1.1)}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {spec.body}
    </svg>
  )
}
