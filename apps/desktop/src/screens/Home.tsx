import type { MetaDetail, MetaPreview, WatchState } from '@halo/core'
import { useEffect, useState } from 'react'
import { ArtImage } from '../components/ArtImage'
import { Icon } from '../components/Icon'
import { PosterCard } from '../components/PosterCard'
import { SectionHeader } from '../components/SectionHeader'
import { Segmented } from '../components/Segmented'
import { Shelf } from '../components/Shelf'
import { episodeTag, formatTimeLeft, runtimeMinutes } from '../format'
import { buildContinueWatching, type ContinueWatchingItem } from '../homeRows'
import { useNav } from '../nav'
import {
  browsableCatalogs,
  libraryItemFromMeta,
  useCatalog,
  useEffectiveAddons,
  useLibrary,
  useMeta,
  useUpsertLibrary,
  useWatchStates,
  type BrowsableCatalog,
} from '../queries'
import { setSearchQuery, useSearchQuery } from '../searchQuery'
import { HERO_DWELL_MS } from '../theme'

/** How many catalog shelves Home renders (each is one server round-trip). */
const MAX_SHELVES = 8
/** How many catalog entries a single shelf shows. */
const SHELF_LIMIT = 30
/**
 * Continue-watching cards resolve their episode still from full meta, one
 * request each. Cap the row so a long history can't turn Home into a burst of
 * addon round-trips; the cards past this point are a scroll away anyway.
 */
const CONTINUE_LIMIT = 8
/** Keep desktop's featured rotation aligned with the mobile client. */
const FEATURED_COUNT = 3

const FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
] as const
type Filter = (typeof FILTERS)[number]['value']

export function Home() {
  const [filter, setFilter] = useState<Filter>('all')
  const { setRoot } = useNav()
  const query = useSearchQuery()
  const { data: addons, isLoading, error } = useEffectiveAddons()
  const { data: watchStates } = useWatchStates()
  const { data: library } = useLibrary()

  const allShelves = addons ? browsableCatalogs(addons) : []
  const typeFilter = filter === 'all' ? null : filter
  const shelves = (
    typeFilter ? allShelves.filter((s) => s.catalog.type === typeFilter) : allShelves
  ).slice(0, MAX_SHELVES)

  // Continue watching stays unfiltered: an in-progress episode matters
  // regardless of which browse filter is showing.
  const continueItems = buildContinueWatching(watchStates, library).slice(0, CONTINUE_LIMIT)

  return (
    <div className="view">
      <div style={{ display: 'flex', flexDirection: 'column', gap: 28, paddingBottom: 48 }}>
        <SectionHeader title="Home">
          <div className="search-box">
            <input
              placeholder="Search movies and series"
              value={query}
              spellCheck={false}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') setRoot('search')
              }}
            />
            <span style={{ color: 'var(--t3)', display: 'flex' }}>
              <Icon name="search" size={14} />
            </span>
          </div>
          <Segmented options={FILTERS} value={filter} onChange={setFilter} />
        </SectionHeader>

        {error && (
          <div className="state-note error-text">
            Could not reach your Halo server: {String(error)}
          </div>
        )}
        {isLoading && (
          <div className="state-note">
            <span className="spinner" /> Loading addons…
          </div>
        )}
        {addons && allShelves.length === 0 && (
          <div className="state-note">
            No browsable catalogs. Add an addon that publishes them (Cinemeta) under Settings →
            Addons.
          </div>
        )}

        {shelves.length > 0 && <FeaturedHero lead={shelves[0]!} watchStates={watchStates} />}

        {continueItems.length > 0 && (
          <Shelf
            title="Continue watching"
            source={`${continueItems.length} IN PROGRESS`}
            action={
              <span className="btn-link soon" title="Coming soon">
                See all
              </span>
            }
          >
            {continueItems.map((item) => (
              <ContinueCard key={item.itemId} item={item} watchStates={watchStates} />
            ))}
          </Shelf>
        )}

        {shelves.map((shelf) => (
          <CatalogShelf
            key={`${shelf.addonId}/${shelf.catalog.type}/${shelf.catalog.id}`}
            shelf={shelf}
          />
        ))}
      </div>
    </div>
  )
}

/**
 * A continue-watching card wants a 16:9 still, an episode tag and the episode
 * title — none of which live in the watch state, so the card resolves full
 * meta for its title. The query is shared with Detail (same key), so opening
 * the card afterwards costs nothing.
 *
 * Clicking it opens the sources sheet for the episode in progress rather than
 * the title page: the card exists to resume, and its progress bar promises
 * exactly that.
 */
function ContinueCard({
  item,
  watchStates,
}: {
  item: ContinueWatchingItem
  watchStates: WatchState[] | undefined
}) {
  const { openSheet } = useNav()
  const { data: meta } = useMeta(item.meta.type, item.meta.id)

  const state = (watchStates ?? []).find((s) => s.itemId === item.itemId)
  const video = meta?.videos?.find((v) => v.id === state?.videoId)
  const tag = video ? episodeTag(video.season, video.episode) : null
  const still = video?.thumbnail ?? meta?.background ?? item.meta.poster
  const episodeName = video?.title ?? video?.name ?? null
  const left = state ? formatTimeLeft(state.positionSec, state.durationSec).toUpperCase() : ''

  const open = () =>
    openSheet({
      type: item.meta.type,
      videoId: state?.videoId ?? item.meta.id,
      itemId: item.itemId,
      metaId: item.meta.id,
      title: episodeName ?? item.meta.name,
      showName: item.meta.name,
      ...(tag ? { episodeLabel: tag } : {}),
      ...(item.meta.poster ? { poster: item.meta.poster } : {}),
      ...(runtimeMinutes(meta?.runtime) != null
        ? { runtimeMinutes: runtimeMinutes(meta?.runtime)! }
        : {}),
    })

  return (
    <button type="button" className="cw-card" onClick={open} title={item.meta.name}>
      <div className="art cw-art">
        <ArtImage src={still} label="EPISODE STILL" lazy />
        {tag && <div className="tag-chip cw-tag">{tag}</div>}
        {left && <div className="tag-chip cw-left">{left.replace(' LEFT', '')}</div>}
        <div className="art-progress">
          <div style={{ width: `${Math.round(item.progress * 100)}%` }} />
        </div>
      </div>
      <div className="card-title ellipsis">{item.meta.name}</div>
      <div className="card-meta ellipsis">
        {[episodeName?.toUpperCase() ?? (item.meta.type === 'movie' ? 'MOVIE' : 'EPISODE'), left]
          .filter(Boolean)
          .join(' · ')}
      </div>
    </button>
  )
}

/**
 * Featured = the first titles of the first visible catalog, rotating every 6s
 * and pausing while the pointer is over it. Full meta is resolved only for the
 * title on screen, so rotating the hero does not turn Home into a burst of
 * eager addon requests. A prior watch state turns Play into Resume.
 */
function FeaturedHero({
  lead,
  watchStates,
}: {
  lead: BrowsableCatalog
  watchStates: WatchState[] | undefined
}) {
  const { push, openSheet } = useNav()
  const { data: library } = useLibrary()
  const upsertLibrary = useUpsertLibrary()
  const { data: metas } = useCatalog(lead.addonId, lead.catalog.type, lead.catalog.id)
  const previews = (metas ?? []).slice(0, FEATURED_COUNT)
  const previewsKey = previews.map((meta) => `${meta.type}:${meta.id}`).join('|')
  const [featuredIndex, setFeaturedIndex] = useState(0)
  const [autoAdvance, setAutoAdvance] = useState(true)
  const [hovered, setHovered] = useState(false)
  const safeIndex = featuredIndex < previews.length ? featuredIndex : 0
  const preview = previews[safeIndex]

  // A filter or catalog refresh can replace the carousel underneath its
  // current index. Start the new list from its first title, just as mobile does.
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

  const { data: fullMeta } = useMeta(preview?.type ?? '', preview?.id ?? '', { enabled: !!preview })
  let featured: MetaDetail | MetaPreview | undefined = preview
  if (fullMeta && preview && fullMeta.type === preview.type && fullMeta.id === preview.id) {
    featured = fullMeta
  }
  if (!featured) return null

  const itemId = `${featured.type}:${featured.id}`
  const libraryEntry = (library ?? []).find((i) => i.id === itemId && !i.removedAt)
  const state = (watchStates ?? []).find((s) => s.itemId === itemId && !s.watched)
  const videos = fullMeta?.videos ?? []
  const resumeVideo = state ? videos.find((v) => v.id === state.videoId) : undefined
  const resumeTag = resumeVideo ? episodeTag(resumeVideo.season, resumeVideo.episode) : null

  const openDetail = () => push({ name: 'detail', type: featured.type, id: featured.id })

  const play = () => {
    // A series with no resume point needs an episode choice first, and Detail
    // is the picker; anything else goes straight to its sources.
    if (featured.type === 'series' && !state) return openDetail()
    openSheet({
      type: featured.type,
      videoId: state?.videoId ?? featured.id,
      itemId,
      metaId: featured.id,
      title: resumeVideo?.title ?? resumeVideo?.name ?? featured.name,
      showName: featured.name,
      ...(resumeTag ? { episodeLabel: resumeTag } : {}),
      ...(featured.poster ? { poster: featured.poster } : {}),
      ...(runtimeMinutes(fullMeta?.runtime) != null
        ? { runtimeMinutes: runtimeMinutes(fullMeta?.runtime)! }
        : {}),
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

  const facts = [
    featured.releaseInfo,
    featured.type.toUpperCase(),
    videos.length > 0 ? `${new Set(videos.map((v) => v.season ?? 0)).size} SEASONS` : null,
    (featured.genres ?? []).slice(0, 2).join(', ').toUpperCase() || null,
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <div
      className="hero"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div className="art" style={{ position: 'absolute', inset: 0 }} aria-hidden>
        <div className="art-label">BACKDROP</div>
      </div>
      {previews.map((item, index) => {
        const resolved = index === safeIndex ? featured : item
        const artwork = resolved.background ?? resolved.poster
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
          {featured.imdbRating && <span className="hero-rating">★ {featured.imdbRating}</span>}
          {featured.imdbRating && facts && <span className="dot-sep">·</span>}
          {facts && <span className="ellipsis">{facts}</span>}
        </div>
        {featured.description && <div className="hero-synopsis">{featured.description}</div>}
        <div className="hero-actions">
          <button type="button" className="btn-accent h36" onClick={play}>
            <Icon name="play" size={14} />
            <span>{state ? `Resume${resumeTag ? ` ${resumeTag}` : ''}` : 'Play'}</span>
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
              <Icon name={libraryEntry ? 'starFilled' : 'star'} size={16} />
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
 * Addons publish the same catalog name for both types — Cinemeta has a
 * "Popular" and a "Featured" for each — so the type joins the title, or two
 * shelves read identically. Names that already say it are left alone.
 */
function shelfTitle(name: string, type: string): string {
  const label = type === 'movie' ? 'Movies' : type === 'series' ? 'Series' : null
  if (!label || name.toLowerCase().includes(type)) return name
  return `${name} ${label}`
}

function CatalogShelf({ shelf }: { shelf: BrowsableCatalog }) {
  const { setRoot } = useNav()
  const { data: metas, isLoading } = useCatalog(shelf.addonId, shelf.catalog.type, shelf.catalog.id)

  // A catalog that errored or came back empty doesn't earn a shelf.
  if (!isLoading && (!metas || metas.length === 0)) return null

  return (
    <Shelf
      title={shelfTitle(shelf.catalog.name ?? shelf.addonName, shelf.catalog.type)}
      source={shelf.addonName.toUpperCase()}
      action={
        <button type="button" className="btn-link" onClick={() => setRoot('library')}>
          See all
        </button>
      }
    >
      {(metas ?? []).slice(0, SHELF_LIMIT).map((meta) => (
        <PosterCard key={`${meta.type}:${meta.id}`} meta={meta} />
      ))}
      {isLoading && (
        <div className="state-note" style={{ padding: 0 }}>
          <span className="spinner" />
        </div>
      )}
    </Shelf>
  )
}
