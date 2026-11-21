/*
 * A change to an addon list, applied to the list as the server has it at the
 * moment of saving. A save replaces the whole list, so building it from a
 * cached copy would delete any addon another device added since that copy was
 * read; each edit names only what the user changed.
 */

/** Takes the current URLs, in priority order, and returns the list to save. */
export type AddonListEdit = (current: readonly string[]) => string[]

/** Adds `url` at the end, unless the list already has it. */
export function addAddon(url: string): AddonListEdit {
  return (current) => (current.includes(url) ? [...current] : [...current, url])
}

/** Removes `url`, wherever it now stands. */
export function removeAddon(url: string): AddonListEdit {
  return (current) => current.filter((candidate) => candidate !== url)
}

/**
 * Moves `url` to where `target` now stands, as dragging one row onto another
 * does. Either one gone since the list was shown leaves the order alone.
 */
export function moveAddon(url: string, target: string): AddonListEdit {
  return (current) => {
    const from = current.indexOf(url)
    const to = current.indexOf(target)
    if (from < 0 || to < 0 || from === to) return [...current]
    const next = [...current]
    next.splice(to, 0, ...next.splice(from, 1))
    return next
  }
}

export function sameList(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((url, index) => url === b[index])
}
