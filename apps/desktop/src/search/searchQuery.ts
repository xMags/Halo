import { useSyncExternalStore } from 'react'

/**
 * The query string, shared between Home's header field and the Search screen.
 *
 * The design puts a search field in Home's header whose Enter key lands on
 * Search with the term already typed, and Search's own field must show that
 * same term. One value both screens read is the only way those two fields
 * cannot disagree; it is session state, so it is deliberately not persisted.
 */

let term = ''
const listeners = new Set<() => void>()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function setSearchQuery(next: string): void {
  term = next
  for (const listener of listeners) listener()
}

export function useSearchQuery(): string {
  return useSyncExternalStore(
    subscribe,
    () => term,
    () => term,
  )
}
