import { flushSync } from 'react-dom'

/*
 * Home's search box opening Search: the small box glides to where Search's
 * own field sits and grows into it, so the field reads as the same box getting
 * bigger rather than a new page arriving. Going back Home from a Search page
 * the box glided into, the field shrinks back into the box, quicker and
 * without the ease-in. Any other arrival (the rail, Ctrl+K) has no glide.
 *
 * Both are view transitions over one shared `view-transition-name`
 * (`search-field`). Only that element animates: the page itself is swapped
 * at once, never cross-faded, because a half-faded page would let the
 * transparent window (mpv paints behind the webview) show through. Reduced
 * motion, or a webview without view transitions, just swaps.
 */

export const SEARCH_FIELD_TRANSITION = 'search-field'

type Direction = 'in' | 'back'

/** Set by a glide into Search, so the way back Home glides too; any other move clears it. */
let returnPending = false

function glide(direction: Direction, update: () => void): void {
  const doc = document as Document & { startViewTransition?: (callback: () => void) => { finished: Promise<void> } }
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (!doc.startViewTransition || reduced) {
    update()
    return
  }
  const root = document.documentElement
  root.dataset.glide = direction
  try {
    const transition = doc.startViewTransition(() => flushSync(update))
    void transition.finished.finally(() => {
      if (root.dataset.glide === direction) delete root.dataset.glide
    })
  } catch {
    delete root.dataset.glide
    update()
  }
}

/** Home's box was pressed: open Search with the glide. */
export function glideIntoSearch(open: () => void): void {
  returnPending = true
  glide('in', open)
}

/**
 * A move away from the Search page itself. Back Home after a glide in glides
 * back; anything else is a plain move, and no later one glides.
 */
export function leaveSearch(toHome: boolean, move: () => void): void {
  const glideBack = returnPending && toHome
  returnPending = false
  if (glideBack) glide('back', move)
  else move()
}

/** Search was left another way (a title page opened from it): the box no longer waits Home. */
export function forgetSearchGlide(): void {
  returnPending = false
}
