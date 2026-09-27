/**
 * Which Settings rail entry is lit, following the scroll position. (Native:
 * ActiveSettingsSection in SettingsRailPolicy.h.)
 */

/**
 * How far below the top of the form a section has to reach before it counts
 * as the one being read, as a fraction of the visible height. A band rather
 * than the edge itself, so a section boundary resting on the top of the view
 * does not flip the selection back and forth.
 */
export const RAIL_ANCHOR_FRACTION = 0.25

/**
 * `sectionTops` holds each section's top edge measured from the top of the
 * visible form, in document order, so entries go negative once a section has
 * scrolled past. The active entry is the last one to have reached `anchor`.
 * `atEnd` marks a form scrolled to the bottom, where the last section wins
 * even though it never reaches the anchor: the content ran out first.
 */
export function activeSettingsSection(
  sectionTops: readonly number[],
  anchor: number,
  atEnd: boolean,
): number {
  if (sectionTops.length === 0) return 0
  if (atEnd) return sectionTops.length - 1
  let active = 0
  sectionTops.forEach((top, index) => {
    if (top <= anchor) active = index
  })
  return active
}

/**
 * Only a form that could scroll counts as scrolled to its end. Otherwise a
 * form short enough to fit would sit permanently on its last section.
 */
export function isScrolledToEnd(scrollTop: number, scrollHeight: number, clientHeight: number): boolean {
  const scrollable = scrollHeight - clientHeight
  return scrollable > 0.5 && scrollTop >= scrollable - 1
}
