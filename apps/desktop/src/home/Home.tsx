import type { MetaPreview } from '@halo/core'
import { useQueries } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { ArtImage } from '../components/ArtImage'
import { FluentIcon } from '../components/FluentIcon'
import { PosterCard } from '../components/PosterCard'
import { SearchBox } from '../components/SearchBox'
import { SectionHeader } from '../components/SectionHeader'
import { Segmented } from '../components/Segmented'
import { Shelf } from '../components/Shelf'
import { HeroSkeleton, PosterCardSkeleton, ShelfSkeleton } from '../components/Skeleton'
import { useTitleMenu } from '../browse/titleMenu'
import { continueSheetParams, useContinueShelf } from './continueShelf'
import { formatClock, videoIdTag } from '../format'
import {
  buildLibraryRow,
  matchesHomeFilter,
  selectFeatured,
  type ContinueCard,
  type HomeFilter,
} from './homeRows'
import { useNav } from '../nav'
import {
  browsableCatalogs,
  catalogQuery,
  libraryItemFromMeta,
  useCatalog,
  useEffectiveAddons,
  useLibrary,
  useMeta,
  useUpsertLibrary,
  type BrowsableCatalog,
} from '../queries'
import { setSearchQuery, useSearchQuery } from '../search/searchQuery'
import { HERO_DWELL_MS } from '../theme'

/**
 * The native catalog cap: the first eight browsable catalogs across the
 * addons, in addon order, whatever the filter. The filter then narrows the
 * titles inside each shelf, and a shelf left with none disappears.
 */
const MAX_SHELVES = 8
/** How many catalog entries a single shelf shows. */
const SHELF_LIMIT = 30
/** The native carousel's length. */
const FEATURED_COUNT = 5

const FILTERS: ReadonlyArray<{ value: HomeFilter; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
]

/** The native shelf's kind label, printed beside the catalog's own name. */
function typeLabel(type: string): string {
  if (type === 'movie') return 'Movies'
  if (type === 'series') return 'Series'
  return type
}

export function Home() {
  const [filter, setFilter] = useState<HomeFilter>('all')
  const { setRoot, push } = useNav()
  const query = useSearchQuery()
  const { data: addons, isLoading, error } = useEffectiveAddons()
  const { data: library } = useLibrary()
  // Continue watching stays unfiltered, as natively: an in-progress title
  // matters whichever browse filter is showing.
  const { cards: continueCards } = useContinueShelf()

  const catalogs = addons ? browsableCatalogs(addons).slice(0, MAX_SHELVES) : []
  // The library shelf's label counts the whole library; the filter only
  // narrows the posters it shows.
  const libraryAll = buildLibraryRow(library, null)
  const libraryShown = libraryAll.filter((meta) => matchesHomeFilter(filter, meta.type))
  const librarySource = `SYNCED · ${libraryAll.length}`
  const continueSource = `${continueCards.length} IN PROGRESS`

  return (
    <div className="view">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 28, paddingBottom: 48 }}>
        <SectionHeader title="Home">
          <SearchBox
            value={query}
            onChange={setSearchQuery}
            onKeyDown={(e) => {
              if (e.key === 'Enter') setRoot('search')
            }}
          />
          <Segmented options={FILTERS} value={filter} onChange={setFilter} />
        </SectionHeader>

        {error && (
          <div className="state-note error-text">
            Could not reach your Halo server: {String(error)}
          </div>
        )}
        {isLoading && (
          <>
            <HeroSkeleton />
            <ShelfSkeleton titleWidth={150} count={6} />
            <ShelfSkeleton titleWidth={180} count={6} />
          </>
        )}
        {addons && catalogs.length === 0 && (
          <div className="state-note">
            No browsable catalogs. Add an addon that publishes them (Cinemeta) under Settings →
            Addons.
          </div>
        )}

        {catalogs.length > 0 && <FeaturedHero catalogs={catalogs} filter={filter} />}

        {continueCards.length > 0 && (
          <Shelf
            title="Continue watching"
            source={continueSource}
            action={
              <button
                type="button"
                className="btn-link"
                aria-label="See all in progress titles"
                onClick={() =>
                  push({
                    name: 'catalog',
                    title: 'Continue watching',
                    source: continueSource,
                    // The grid has nowhere for time left or progress; the
                    // poster and episode tag are what identify a title there.
                    items: continueCards.map((card) => ({
                      meta: { id: card.metaId, type: card.type, name: card.name, poster: card.poster },
                      metaLine: videoIdTag(card.videoId, card.metaId),
                    })),
                  })
                }
              >
                See all
              </button>
            }
          >
            {continueCards.map((card) => (
              <ContinueCardView key={card.itemId} card={card} />
            ))}
          </Shelf>
        )}

        {libraryShown.length > 0 && (
          <Shelf
            title="My library"
            source={librarySource}
            action={
              <button
                type="button"
                className="btn-link"
                aria-label="See all shelf items"
                onClick={() =>
                  push({
                    name: 'catalog',
                    title: 'My library',
                    source: librarySource,
                    items: libraryShown.map((meta) => ({ meta })),
                  })
                }
              >
                See all
              </button>
            }
          >
            {libraryShown.map((meta) => (
              <PosterCard key={`${meta.type}:${meta.id}`} meta={meta} />
            ))}
          </Shelf>
        )}

        {catalogs.map((shelf) => (
          <CatalogShelf
            key={`${shelf.addonId}/${shelf.catalog.type}/${shelf.catalog.id}`}
            shelf={shelf}
            filter={filter}
          />
        ))}
      </div>
    </div>
  )
}

/**
 * The native continue card: the tag in the top-left chip and again as the
 * meta line, the clock time left (or UP NEXT for a promoted episode) in the
 * bottom-right chip, and the episode still, which needs the title's full meta.
 * That query is shared with Detail (same key), so opening the title afterwards
 * costs nothing.
 *
 * Clicking it opens the sources sheet for the episode the card names rather
 * than the title page: the card exists to resume, or to start what is next.
 */
function ContinueCardView({ card }: { card: ContinueCard }) {
  const { openSheet } = useNav()
  const titleMenu = useTitleMenu()
  const { data: meta } = useMeta(card.type, card.metaId)

  const video = meta?.videos?.find((v) => v.id === card.videoId)
  const tag = videoIdTag(card.videoId, card.metaId)
  const still = video?.thumbnail ?? meta?.background ?? card.poster
  const promoted = card.kind === 'next'
  const left = promoted ? 'UP NEXT' : `${formatClock(card.durationSec - card.positionSec)} LEFT`
  const progress = promoted || card.durationSec <= 0 ? 0 : card.positionSec / card.durationSec

  return (
    <button
      type="button"
      className="cw-card"
      onClick={() => openSheet(continueSheetParams(card, meta))}
      onContextMenu={(event) => titleMenu.forContinue(event, card)}
      title={card.name}
    >
      <div className="art cw-art">
        <ArtImage src={still} label="EPISODE STILL" lazy />
        <div className="tag-chip cw-tag">{tag}</div>
        <div className="tag-chip cw-left">{left}</div>
        <div className="art-progress">
          <div style={{ width: `${Math.round(progress * 100)}%` }} />
        </div>
      </div>
      <div className="card-title ellipsis">{card.name}</div>
      <div className="card-meta ellipsis">{tag}</div>
    </button>
  )
}

/**
 * The carousel: up to five titles picked across every shelf's catalog, the
 * native rule (backdrops first, catalog order, no repeats, the filter
 * applied). It rotates every 6s and pauses under the pointer.
 *
 * The pick is only shown once it can no longer change, so the strip does not
 * swap titles as slower catalogs arrive: either every catalog has answered,
 * or the catalogs that have, in order, already yield five titles with
 * backdrops, which no later catalog can displace.
 */
function FeaturedHero({ catalogs, filter }: { catalogs: BrowsableCatalog[]; filter: HomeFilter }) {
  const { push, openSheet } = useNav()
  const { data: library } = useLibrary()
  const upsertLibrary = useUpsertLibrary()
  const titleMenu = useTitleMenu()
  const results = useQueries({
    queries: catalogs.map((c) => catalogQuery(c.addonId, c.catalog.type, c.catalog.id)),
  })

  const pool: MetaPreview[] = []
  let allAnswered = true
  for (const result of results) {
    if (result.isPending) {
      allAnswered = false
      break
    }
    // A catalog that failed contributes nothing, as its shelf does not appear.
    pool.push(...(result.data ?? []))
  }
  const picks = selectFeatured(filter, pool, FEATURED_COUNT).map((index) => pool[index]!)
  const settled =
    allAnswered || (picks.length === FEATURED_COUNT && picks.every((meta) => Boolean(meta.background)))
  const previews = settled ? picks : []
  const previewsKey = previews.map((meta) => `${meta.type}:${meta.id}`).join('|')

  const [featuredIndex, setFeaturedIndex] = useState(0)
  const [autoAdvance, setAutoAdvance] = useState(true)
  const [hovered, setHovered] = useState(false)
  const safeIndex = featuredIndex < previews.length ? featuredIndex : 0
  const featured = previews[safeIndex]

  // A filter or catalog refresh can replace the carousel underneath its
  // current index. Start the new list from its first title.
  useEffect(() => {
    setFeaturedIndex(0)
    setAutoAdvance(true)
  }, [previewsKey])

  useEffect(() => {
    if (!autoAdvance || hovered || previews.length <= 1) return
    const timer = window.setTimeout(() => {
      setFeaturedIndex((current) => (current + 1) % previews.length)
    }, HERO_DWELL_MS)
    return () => window.clearTimeout(timer)
  }, [autoAdvance, hovered, previews.length, previewsKey, safeIndex])

  if (!settled) return <HeroSkeleton />
  if (!featured) return null

  const itemId = `${featured.type}:${featured.id}`
  const libraryEntry = (library ?? []).find((i) => i.id === itemId && !i.removedAt)
  const isSeries = featured.type === 'series'

  const openDetail = () => push({ name: 'detail', type: featured.type, id: featured.id })

  // The native primary action: a series needs an episode chosen, and Detail is
  // the picker; a film goes straight to its sources.
  const play = () => {
    if (isSeries) return openDetail()
    openSheet({
      type: featured.type,
      videoId: featured.id,
      itemId,
      metaId: featured.id,
      title: featured.name,
      showName: featured.name,
      ...(featured.poster ? { poster: featured.poster } : {}),
    })
  }

  const toggleLibrary = () => {
    const now = Date.now()
    if (libraryEntry) {
      // Tombstone, not delete — removals must sync and survive stale re-adds.
      void upsertLibrary.mutateAsync([{ ...libraryEntry, removedAt: now, updatedAt: now }])
    } else {
      void upsertLibrary.mutateAsync([libraryItemFromMeta(featured)])
    }
  }

  const rating = featured.imdbRating ? `★ ${featured.imdbRating}` : null

  return (
    <div
      className="hero"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onContextMenu={(event) => titleMenu.forPoster(event, featured)}
    >
      <div className="art" style={{ position: 'absolute', inset: 0 }} aria-hidden>
        <div className="art-label">BACKDROP</div>
      </div>
      {previews.map((item, index) => {
        const artwork = item.background ?? item.poster
        if (!artwork) return null
        return (
          <div
            key={`${item.type}:${item.id}:${index}`}
            className={`hero-art ${index === safeIndex ? 'hero-art-active' : ''}`}
            style={{ backgroundImage: `url(${artwork})` }}
            aria-hidden
          />
        )
      })}
      <div className="hero-scrim" />
      <div className="hero-body">
        <div className="kicker kicker-accent">FEATURED</div>
        <div className="hero-title">{featured.name}</div>
        <div className="hero-facts">
          {rating && <span className="hero-rating">{rating}</span>}
          {rating && featured.releaseInfo && <span className="dot-sep">·</span>}
          {featured.releaseInfo && <span className="ellipsis">{featured.releaseInfo}</span>}
        </div>
        {featured.description && <div className="hero-synopsis">{featured.description}</div>}
        <div className="hero-actions">
          <button type="button" className="btn-accent h36" onClick={play}>
            <FluentIcon glyph="play" size={14} />
            <span>{isSeries ? 'Choose episode' : 'Play'}</span>
          </button>
          <button type="button" className="btn h36" onClick={openDetail}>
            Details
          </button>
          <button
            type="button"
            className="btn h36"
            style={{ padding: '0 14px', gap: 8 }}
            onClick={toggleLibrary}
          >
            <span style={{ display: 'flex', color: libraryEntry ? 'var(--ca)' : undefined }}>
              <FluentIcon glyph={libraryEntry ? 'starFilled' : 'star'} size={16} />
            </span>
            <span>{libraryEntry ? 'In library' : 'Add to library'}</span>
          </button>
        </div>
      </div>
      {previews.length > 1 && (
        <div className="hero-pips" role="tablist" aria-label="Featured titles">
          {previews.map((item, index) => {
            const active = index === safeIndex
            return (
              <button
                key={`${item.type}:${item.id}:${index}`}
                type="button"
                className={`pip ${active ? 'pip-active' : ''}`}
                role="tab"
                aria-selected={active}
                aria-label={`Show ${item.name}`}
                title={item.name}
                onClick={() => {
                  setFeaturedIndex(index)
                  setAutoAdvance(false)
                }}
              />
            )
          })}
        </div>
      )}
    </div>
  )
}

/**
 * One catalog as a shelf: the catalog's own name with the kind label beside
 * it, as the native shelf titles it, and only the titles the filter keeps.
 */
function CatalogShelf({ shelf, filter }: { shelf: BrowsableCatalog; filter: HomeFilter }) {
  const { push } = useNav()
  const { data: metas, isLoading } = useCatalog(shelf.addonId, shelf.catalog.type, shelf.catalog.id)
  const items = (metas ?? []).filter((meta) => matchesHomeFilter(filter, meta.type))

  // A catalog that errored, came back empty, or holds nothing the filter keeps
  // doesn't earn a shelf.
  if (!isLoading && items.length === 0) return null

  const title = shelf.catalog.name ?? shelf.addonName
  const source = typeLabel(shelf.catalog.type)
  return (
    <Shelf
      title={title}
      source={source}
      action={
        <button
          type="button"
          className="btn-link"
          aria-label="See all shelf items"
          onClick={() => push({ name: 'catalog', title, source, items: items.map((meta) => ({ meta })) })}
        >
          See all
        </button>
      }
    >
      {items.slice(0, SHELF_LIMIT).map((meta) => (
        <PosterCard key={`${meta.type}:${meta.id}`} meta={meta} />
      ))}
      {isLoading &&
        Array.from({ length: 6 }).map((_, index) => <PosterCardSkeleton key={`skeleton-${index}`} />)}
    </Shelf>
  )
}
