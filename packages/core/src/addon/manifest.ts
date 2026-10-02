import type { Manifest } from './types'

/**
 * Validates a manifest fetched from a third-party addon. Loose on purpose:
 * only the fields Halo depends on are checked, and everything else passes
 * through untouched, so a manifest that works in Stremio is not refused for
 * fields Halo never reads. Returns null when a load-bearing field is missing
 * or has the wrong shape.
 *
 * Load-bearing: a non-empty `id` and `name`, a string `version`, `types` as
 * strings, `resources` as names or objects with a string `name`, and
 * `catalogs` as objects with string `type` and `id`.
 */
export function parseManifest(value: unknown): Manifest | null {
  if (!isRecord(value)) return null
  if (!nonEmptyString(value.id) || typeof value.version !== 'string' || !nonEmptyString(value.name)) return null
  if (!Array.isArray(value.types) || !value.types.every((type) => typeof type === 'string')) return null
  if (!Array.isArray(value.resources) || !value.resources.every(isResourceEntry)) return null
  if (!Array.isArray(value.catalogs) || !value.catalogs.every(isCatalogEntry)) return null
  return value as unknown as Manifest
}

function isResourceEntry(entry: unknown): boolean {
  return typeof entry === 'string' || (isRecord(entry) && typeof entry.name === 'string')
}

function isCatalogEntry(entry: unknown): boolean {
  return isRecord(entry) && typeof entry.type === 'string' && typeof entry.id === 'string'
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
