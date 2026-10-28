import { useId } from 'react'

/** At or below this the ring would thin past a pixel, so it is drawn heavier. */
const SMALL_MARK_PX = 24

/** The ring, tilted like a halo and drawn on a 64-unit square. */
const RING_TILT = 'rotate(-16 32 34)'
/** The half of the ring behind the H. */
const RING_BACK = 'M5 34 A27 9.5 0 0 1 59 34'
/** The half in front of it. */
const RING_FRONT = 'M5 34 A27 9.5 0 0 0 59 34'
/**
 * How far the bars stand clear of the front ring, beyond its own width. Small
 * marks keep a narrower gap, or it cuts the H into four pieces.
 */
const RING_CLEARANCE = 5
const SMALL_RING_CLEARANCE = 3

/**
 * The Halo mark: an H whose crossbar is a tilted ring, passing behind the H
 * at the top and in front of it at the bottom. The H takes the theme's accent
 * fill and the ring the primary ink, so it follows the theme on its own.
 *
 * The gap where the front ring crosses the bars is cut from the bars with a
 * mask rather than painted in a background colour, so the mark sits on any
 * surface. `src-tauri/icons/icon.ico` is rendered from this same geometry.
 */
export function HaloMark({ size, className }: { size: number; className?: string }) {
  // useId's characters are not all valid in a url(#…) reference.
  const maskId = `halo-mark-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const small = size <= SMALL_MARK_PX
  const ringWidth = small ? 7 : 5
  const clearance = small ? SMALL_RING_CLEARANCE : RING_CLEARANCE

  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 64 64"
      aria-hidden
      focusable="false"
    >
      <defs>
        <mask id={maskId} maskUnits="userSpaceOnUse" x="0" y="0" width="64" height="64">
          <rect width="64" height="64" fill="#fff" />
          <path
            d={RING_FRONT}
            transform={RING_TILT}
            fill="none"
            stroke="#000"
            strokeWidth={ringWidth + clearance}
            strokeLinecap="round"
          />
        </mask>
      </defs>
      <path
        d={RING_BACK}
        transform={RING_TILT}
        fill="none"
        strokeWidth={ringWidth}
        strokeLinecap="round"
        style={{ stroke: 'var(--t1)' }}
      />
      <g mask={`url(#${maskId})`} style={{ fill: 'var(--aa)' }}>
        <rect x="13" y="7" width="12" height="50" rx="6" />
        <rect x="39" y="7" width="12" height="50" rx="6" />
      </g>
      <path
        d={RING_FRONT}
        transform={RING_TILT}
        fill="none"
        strokeWidth={ringWidth}
        strokeLinecap="round"
        style={{ stroke: 'var(--t1)' }}
      />
    </svg>
  )
}
