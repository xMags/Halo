import type { MetaPreview } from '@halo/core'

/** One searchable catalog: an installed addon's catalog that takes a `search` extra. */
export interface SearchTarget {
  key: string
  addonId: string
  addonName: string
  type: string
  id: string
  /** The catalog's own name, the row's title. */
  title: string
}

/** Where one catalog's answer stands. */
export type TargetState =
  | { status: 'pending' }
  | { status: 'failed' }
  | { status: 'done'; metas: MetaPreview[] }

/** One result row: a catalog that answered with titles. */
export interface SearchResultGroup {
  key: string
  title: string
  addonName: string
  type: string
  metas: MetaPreview[]
}

export interface SearchProgress {
  /** Rows so far, in addon order; a catalog's row appears as soon as it answers. */
  groups: SearchResultGroup[]
  /**
   * The best guess for the query: the first title of the first catalog, in
   * addon order, that answered with any. Undecided (null) while an earlier
   * catalog is still out, so it never swaps once shown.
   */
  topMatch: MetaPreview | null
  /** Catalogs still out. */
  pending: number
  /** Catalogs that failed, for naming the addons that did not answer. */
  failed: SearchTarget[]
  /** Catalogs asked. */
  total: number
}

/** A catalog's titles without repeats; addons sometimes list one title twice. */
export function uniqueMetas(metas: readonly MetaPreview[]): MetaPreview[] {
  const seen = new Set<string>()
  return metas.filter((meta) => {
    const key = `${meta.type}:${meta.id}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** What the search screen shows from each catalog's state, as answers arrive. */
export function searchProgress(targets: readonly SearchTarget[], states: readonly TargetState[]): SearchProgress {
  const groups: SearchResultGroup[] = []
  const failed: SearchTarget[] = []
  let pending = 0
  let topMatch: MetaPreview | null = null
  let topDecided = false
  targets.forEach((target, index) => {
    const state = states[index] ?? { status: 'pending' }
    if (state.status === 'pending') {
      pending += 1
      topDecided = true
      return
    }
    if (state.status === 'failed') {
      failed.push(target)
      return
    }
    if (state.metas.length === 0) return
    groups.push({ key: target.key, title: target.title, addonName: target.addonName, type: target.type, metas: state.metas })
    if (!topDecided) {
      topMatch = state.metas[0]!
      topDecided = true
    }
  })
  return { groups, topMatch, pending, failed, total: targets.length }
}
