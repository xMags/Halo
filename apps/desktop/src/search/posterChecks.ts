import { useSyncExternalStore } from 'react'
import { cardArtworkUrl } from '../components/artworkUrl'

/*
 * Whether a search result's poster link leads to an image. An addon's answer
 * does not say so: Cinemeta gives every title a poster link, and for the
 * obscure ones (fan films, announced sequels) that link is simply dead. The
 * first titles a reveal would show are checked before it (see
 * `revealPosterLinks`), so a dead link is ranked as no artwork rather than
 * laid out among the results. Each check loads the same URL the card then
 * draws, so the card finds it in the browser's cache.
 *
 * One answer per URL for the life of the app; a link not answered yet reads
 * as unknown, which the rules treat as a poster that loads.
 */

export type PosterLinkState = 'unknown' | 'present' | 'missing'

const states = new Map<string, PosterLinkState>()
const listeners = new Set<() => void>()
let version = 0

function settle(url: string, state: PosterLinkState): void {
  states.set(url, state)
  version += 1
  for (const listener of listeners) listener()
}

/** The URL a card draws for an addon's poster link, which is what is checked. */
function checkedUrl(poster: string): string {
  return cardArtworkUrl(poster)
}

/** Starts checking the links not asked about yet. */
export function checkPosterLinks(posters: readonly string[]): void {
  for (const poster of posters) {
    const url = checkedUrl(poster)
    if (states.has(url)) continue
    states.set(url, 'unknown')
    const image = new Image()
    image.onload = () => settle(url, 'present')
    image.onerror = () => settle(url, 'missing')
    image.src = url
  }
}

export function posterLinkState(poster: string): PosterLinkState {
  return states.get(checkedUrl(poster)) ?? 'unknown'
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Changes whenever a check answers, so a screen waiting on them renders again. */
export function usePosterChecksVersion(): number {
  return useSyncExternalStore(subscribe, () => version, () => version)
}
