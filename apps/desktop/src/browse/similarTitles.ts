import type { AddonEntry, ManifestCatalog } from '@halo/core'

/** Where a title page's "More like this" shelf comes from. */
export interface GenreCatalog {
  addonId: string
  addonName: string
  catalogId: string
  genre: string
}

/** Whether a catalog can be asked for this one genre with nothing else required. */
function acceptsGenreAlone(catalog: ManifestCatalog, genre: string): boolean {
  const extras = catalog.extra ?? []
  const declared = extras.find((extra) => extra.name === 'genre')
  const legacy = catalog.extraSupported?.includes('genre') ?? false
  if (!declared && !legacy) return false
  // A catalog that lists its genres only answers for those.
  const options = declared?.options ?? []
  if (options.length > 0 && !options.includes(genre)) return false
  const required = [
    ...extras.filter((extra) => extra.isRequired).map((extra) => extra.name),
    ...(catalog.extraRequired ?? []),
  ]
  return required.every((name) => name === 'genre')
}

/**
 * The first catalog, in resolution order, that lists titles of this type by
 * the title's own genre: its leading genre if any addon can filter by it,
 * else the next one. Addons whose catalogs are hidden from Home come back
 * with no catalogs, so they are not used here either.
 */
export function pickGenreCatalog(
  addons: readonly AddonEntry[],
  type: string,
  genres: readonly string[],
): GenreCatalog | null {
  for (const genre of genres) {
    for (const addon of addons) {
      const catalog = addon.manifest.catalogs.find(
        (candidate) => candidate.type === type && acceptsGenreAlone(candidate, genre),
      )
      if (catalog) {
        return { addonId: addon.id, addonName: addon.manifest.name, catalogId: catalog.id, genre }
      }
    }
  }
  return null
}
