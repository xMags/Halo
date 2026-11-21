/*
 * Which visit to Search this is. A visit starts when Search is opened afresh
 * (the menu, Ctrl+K, Home's search box) and lasts while screens are pushed
 * over it and popped back off. What Search keeps beyond the page (its board
 * and kind chip) belongs to one visit: Back returns to it as it was, and a
 * new visit searches anew, so old answers are never shown as current.
 */

let visit = 0

/** Search is being opened afresh, not returned to. */
export function startSearchVisit(): void {
  visit += 1
}

export function currentSearchVisit(): number {
  return visit
}
