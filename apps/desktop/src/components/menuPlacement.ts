export interface Point {
  x: number
  y: number
}

export interface Size {
  width: number
  height: number
}

/** How close a menu may come to the window's edge. */
const EDGE_MARGIN = 8

/**
 * Where a context menu's top-left corner goes, the way Windows places one:
 * down and to the right of the pointer, flipped to the pointer's other side
 * on whichever axis would run past the window, and pushed back inside when it
 * does not fit on either side (a very short window).
 */
export function placeContextMenu(anchor: Point, menu: Size, viewport: Size): Point {
  return {
    x: placeOnAxis(anchor.x, menu.width, viewport.width),
    y: placeOnAxis(anchor.y, menu.height, viewport.height),
  }
}

function placeOnAxis(anchor: number, extent: number, available: number): number {
  const limit = available - EDGE_MARGIN
  if (anchor + extent <= limit) return anchor
  const flipped = anchor - extent
  if (flipped >= EDGE_MARGIN) return flipped
  // Fits neither way: keep it on screen, favouring the start edge so the
  // first items stay visible if the menu is taller than the window itself.
  return Math.max(EDGE_MARGIN, limit - extent)
}
