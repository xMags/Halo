import type { MetaPreview } from '@halo/core'
import { useInfiniteQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { getClient } from '../api'
import { PosterCard } from '../components/PosterCard'
import { ProgressRing } from '../components/ProgressRing'
import { SectionHeader } from '../components/SectionHeader'
import { matchesHomeFilter } from '../home/homeRows'
import { useNav, type CatalogItem, type CatalogParams } from '../nav'
import { hasMorePages, newTitles } from './catalogPaging'

/** How far below the view the next page starts loading, so scrolling rarely waits. */
const PREFETCH_MARGIN = '800px'

/**
 * A shelf's "See all": every title the shelf held, as a poster grid under
 * the shelf's own title, source label and count. The native Halo Desktop's
 * CatalogPage, reached the same way from Home's shelves, Continue watching
 * and Search's result shelves. Posters open Detail; Back returns to the page
 * the shelf was on.
 *
 * A catalog that pages (`more`) keeps going past the shelf's titles: nearing
 * the end asks the addon for the next page, until it has no more.
 */
export function Catalog({ title, source, items, more }: CatalogParams) {
  const { pop } = useNav()
  const paged = useMorePages(more)
  const shown = useMemo<CatalogItem[]>(
    () => [...items, ...paged.titles.map((meta) => ({ meta }))],
    [items, paged.titles],
  )

  return (
    <div className="view view-col catalog-view">
      <SectionHeader title={title || 'Catalog'} onBack={pop}>
        <span className="catalog-labels">
          {source && <span className="mono">{source}</span>}
          <span className="mono">
            {shown.length} {shown.length === 1 ? 'ITEM' : 'ITEMS'}
          </span>
        </span>
      </SectionHeader>
      {shown.length > 0 ? (
        <div className="catalog-scroll" ref={paged.scrollRef}>
          <div className="catalog-grid">
            {shown.map((item) => (
              <PosterCard
                key={`${item.meta.type}:${item.meta.id}`}
                meta={item.meta}
                showKind
                {...(item.metaLine ? { metaLine: item.metaLine } : {})}
              />
            ))}
          </div>
          {more && (
            <div className="catalog-more" ref={paged.endRef}>
              {paged.loading && <ProgressRing size={20} />}
              {paged.failed && (
                <>
                  <span>Couldn't load more titles.</span>
                  <button type="button" className="btn" onClick={paged.retry}>
                    Retry
                  </button>
                </>
              )}
            </div>
          )}
        </div>
      ) : (
        <div className="catalog-empty">
          <div className="catalog-empty-title">Nothing to show</div>
          <div className="catalog-empty-body">No titles are available in this catalog.</div>
        </div>
      )}
    </div>
  )
}

/**
 * The pages after the shelf's first: the second loads with the grid, the
 * rest as its end nears. Inert (no titles, no fetching) for a catalog that
 * does not page.
 */
function useMorePages(more: CatalogParams['more']) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const endRef = useRef<HTMLDivElement>(null)
  const [nearEnd, setNearEnd] = useState(false)

  const query = useInfiniteQuery({
    queryKey: ['catalog-pages', more?.addonId, more?.type, more?.id, more?.extra, more?.firstPage.length],
    enabled: Boolean(more),
    initialPageParam: more?.firstPage.length ?? 0,
    queryFn: async ({ pageParam, signal }): Promise<MetaPreview[]> => {
      if (!more) return []
      const extra = { ...more.extra, skip: String(pageParam) }
      return (await getClient().getCatalog(more.addonId, more.type, more.id, extra, { signal })).metas ?? []
    },
    getNextPageParam: (last, pages, lastParam) =>
      more && hasMorePages([...more.firstPage, ...pages.slice(0, -1).flat()], last) ? lastParam + last.length : undefined,
    staleTime: 10 * 60_000,
  })

  // The second page loads with the grid; each after it once the end nears.
  const started = (query.data?.pages.length ?? 0) > 0
  const { fetchNextPage, refetch } = query
  const canFetch = started && query.hasNextPage && !query.isFetching && !query.isError

  useEffect(() => {
    const root = scrollRef.current
    const end = endRef.current
    if (!root || !end) return
    const observer = new IntersectionObserver(([entry]) => setNearEnd(Boolean(entry?.isIntersecting)), {
      root,
      rootMargin: `0px 0px ${PREFETCH_MARGIN} 0px`,
    })
    observer.observe(end)
    return () => observer.disconnect()
  }, [more])

  // Re-checked after every page too: one the filter empties leaves the end in
  // view, and the observer would not fire again by itself.
  useEffect(() => {
    if (nearEnd && canFetch) void fetchNextPage()
  }, [nearEnd, canFetch, fetchNextPage])

  const titles = useMemo(() => {
    if (!more || !query.data) return []
    const keep = (meta: MetaPreview) => matchesHomeFilter(more.filter, meta.type)
    const added: MetaPreview[] = []
    const shown = more.firstPage.filter(keep)
    for (const page of query.data.pages) {
      const fresh = newTitles([...shown, ...added], page, keep)
      added.push(...fresh)
    }
    return added
  }, [more, query.data])

  return {
    scrollRef,
    endRef,
    titles,
    loading: query.isFetching,
    failed: query.isError,
    retry: () => void (started ? fetchNextPage() : refetch()),
  }
}
