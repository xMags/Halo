import type { ManifestCatalog, MetaPreview } from '@halo/core'

/**
 * Paging a catalog past its first page. Addons serve a catalog a page at a
 * time (Cinemeta about 50 titles), and the next page is asked for with the
 * `skip` extra: how many titles the addon has handed over so far. Only a
 * catalog that declares `skip` takes it; any other is one page.
 */

/** Where a "See all" grid gets more titles, and how many the addon has sent. */
export interface CatalogPaging {
  addonId: string
  type: string
  id: string
  /** The extras the shelf asked with (a language genre), kept on every page. */
  extra: Record<string, string>
  /**
   * The first page as the addon sent it, before the shelf filtered anything:
   * the offset is the addon's count, not the grid's, and a repeat of this page
   * is how an addon that ignores `skip` is caught.
   */
  firstPage: MetaPreview[]
}

export function supportsSkip(catalog: Pick<ManifestCatalog, 'extra' | 'extraSupported'>): boolean {
  return (catalog.extra ?? []).some((extra) => extra.name === 'skip') || (catalog.extraSupported ?? []).includes('skip')
}

function metaKey(meta: Pick<MetaPreview, 'type' | 'id'>): string {
  return `${meta.type}:${meta.id}`
}

/**
 * The titles a new page adds to those shown: unseen ones the grid's filter
 * keeps, in the addon's order.
 */
export function newTitles(
  shown: readonly Pick<MetaPreview, 'type' | 'id'>[],
  page: readonly MetaPreview[],
  keep: (meta: MetaPreview) => boolean,
): MetaPreview[] {
  const seen = new Set(shown.map(metaKey))
  const added: MetaPreview[] = []
  for (const meta of page) {
    const key = metaKey(meta)
    if (seen.has(key) || !keep(meta)) continue
    seen.add(key)
    added.push(meta)
  }
  return added
}

/**
 * Whether to ask for another page after this one. An empty page is the end.
 * So is a page of nothing but titles already received: an addon that ignores
 * `skip` answers every page with the first, and that must stop the paging
 * rather than loop.
 */
export function hasMorePages(
  received: readonly Pick<MetaPreview, 'type' | 'id'>[],
  page: readonly Pick<MetaPreview, 'type' | 'id'>[],
): boolean {
  if (page.length === 0) return false
  const seen = new Set(received.map(metaKey))
  return page.some((meta) => !seen.has(metaKey(meta)))
}
