import type { MetaDetail, MetaPreview, WatchState } from '@halo/core'
import { useState } from 'react'
import { Icon } from '../components/Icon'
import { PosterCard } from '../components/PosterCard'
import { Segmented } from '../components/Segmented'
import { Shelf } from '../components/Shelf'
import { episodeTag, formatTimeLeft } from '../format'
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
import { CommandBarActions, usePublishScreenTitle } from '../screenTitle'

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

const FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
] as const
type Filter = (typeof FILTERS)[number]['value']

export function Home() {
  const [filter, setFilter] = useState<Filter>('all')
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

  usePublishScreenTitle('Home', '')

  return (
    <div className="view no-bar">
      <CommandBarActions>
        <Segmented options={FILTERS} value={filter} onChange={setFilter} />
      </CommandBarActions>

      {error && <div className="state-note error-text">Could not reach your Halo server: {String(error)}</div>}
      {isLoading && (
        <div className="state-note">
          <span className="spinner" /> Loading addons…
        </div>
      )}
      {addons && allShelves.length === 0 && (
        <div className="state-note">
          No browsable catalogs. Add an addon that publishes them (Cinemeta) under Settings → Addons.
        </div>
      )}

      {shelves.length > 0 && <FeaturedHero lead={shelves[0]!} watchStates={watchStates} />}

      {continueItems.length > 0 && (
        <Shelf title="Continue watching">
          {continueItems.map((item) => (
            <ContinueCard key={item.itemId} item={item} watchStates={watchStates} />
          ))}
        </Shelf>
      )}

      {shelves.map((shelf) => (
        <CatalogShelf key={`${shelf.addonId}/${shelf.catalog.type}/${shelf.catalog.id}`} shelf={shelf} />
      ))}
    </div>
  )
}

/**
 * A continue-watching card wants a 16:9 still, an episode tag and the episode
 * title — none of which live in the watch state, so the card resolves full
 * meta for its title. The query is shared with Detail (same key), so opening
 * the card afterwards costs nothing.
 */
function ContinueCard({
  item,
  watchStates,
}: {
  item: ContinueWatchingItem
  watchStates: WatchState[] | undefined
}) {
  const { push } = useNav()
  const { data: meta } = useMeta(item.meta.type, item.meta.id)

  const state = (watchStates ?? []).find((s) => s.itemId === item.itemId)
  const video = meta?.videos?.find((v) => v.id === state?.videoId)
  const tag = video ? episodeTag(video.season, video.episode) : null
  const still = video?.thumbnail ?? meta?.background ?? item.meta.poster

  const open = () =>
    push({
      name: 'streams',
      type: item.meta.type,
      videoId: state?.videoId ?? item.meta.id,
      itemId: item.itemId,
      metaId: item.meta.id,
      title: video?.title ?? video?.name ?? item.meta.name,
      showName: item.meta.name,
      ...(tag ? { episodeLabel: tag } : {}),
      ...(item.meta.poster ? { poster: item.meta.poster } : {}),
    })

  return (
    <button type="button" className="cw-card" onClick={open} title={item.meta.name}>
      <div className="art art-wide cw-still">
        {still ? <img src={still} alt="" loading="lazy" draggable={false} /> : <div className="art-label">EPISODE STILL</div>}
        {tag && <div className="cw-tag">{tag}</div>}
        {state && (
          <div className="cw-left">{formatTimeLeft(state.positionSec, state.durationSec)}</div>
        )}
        <div className="art-progress">
          <div style={{ width: `${Math.round(item.progress * 100)}%` }} />
        </div>
      </div>
      <div className="cw-body">
        <div className="cw-title ellipsis">{item.meta.name}</div>
        <div className="cw-sub ellipsis">
          {video?.title ?? video?.name ?? (item.meta.type === 'movie' ? 'Movie' : 'Episode')}
        </div>
      </div>
    </button>
  )
}

/**
 * Featured = the first title of the first visible catalog; its full meta
 * brings the wide background art, rating and synopsis the block needs. A prior
 * watch state turns the hero button into Resume.
 */
function FeaturedHero({
  lead,
  watchStates,
}: {
  lead: BrowsableCatalog
  watchStates: WatchState[] | undefined
}) {
  const { push } = useNav()
  const { data: library } = useLibrary()
  const upsertLibrary = useUpsertLibrary()
  const { data: metas } = useCatalog(lead.addonId, lead.catalog.type, lead.catalog.id)
  const preview = metas?.[0]
  const { data: fullMeta } = useMeta(preview?.type ?? '', preview?.id ?? '', { enabled: !!preview })
  const featured: MetaDetail | MetaPreview | undefined = fullMeta ?? preview
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
    push({
      name: 'streams',
      type: featured.type,
      videoId: state?.videoId ?? featured.id,
      itemId,
      metaId: featured.id,
      title: resumeVideo?.title ?? resumeVideo?.name ?? featured.name,
      showName: featured.name,
      ...(resumeTag ? { episodeLabel: resumeTag } : {}),
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

  const episodeCount = videos.length
  const kickerRight = state
    ? `${resumeTag ?? 'IN PROGRESS'} · ${formatTimeLeft(state.positionSec, state.durationSec).toUpperCase()}`
    : (featured.releaseInfo ?? '').toUpperCase()

  return (
    <div className="hero">
      {(fullMeta?.background ?? featured.poster) && (
        <div
          className="hero-art"
          style={{ backgroundImage: `url(${fullMeta?.background ?? featured.poster})` }}
        />
      )}
      <div className="hero-scrim" />
      <div className="hero-body">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
          <div className="kicker kicker-accent">FEATURED · {lead.addonName.toUpperCase()}</div>
          {kickerRight && (
            <>
              <span
                style={{ width: 3, height: 3, borderRadius: 999, background: 'var(--rule)' }}
              />
              <div style={{ font: "400 10px/1 var(--mono)", color: 'var(--text-dim)' }}>
                {kickerRight}
              </div>
            </>
          )}
        </div>
        <div className="hero-title">{featured.name}</div>
        <div className="hero-facts">
          {featured.imdbRating && <span className="rating">★ {featured.imdbRating}</span>}
          {featured.releaseInfo && <span>{featured.releaseInfo}</span>}
          {(featured.genres ?? []).length > 0 && (
            <>
              <span className="dot-sep">/</span>
              <span>{(featured.genres ?? []).slice(0, 2).join(' · ')}</span>
            </>
          )}
          {episodeCount > 0 && (
            <>
              <span className="dot-sep">/</span>
              <span>{episodeCount} episodes</span>
            </>
          )}
        </div>
        {featured.description && (
          <div className="body-copy hero-synopsis">{featured.description}</div>
        )}
        <div className="hero-actions">
          <button type="button" className="btn-primary" onClick={play}>
            <Icon name="play" size={13} />
            {state ? `Resume${resumeTag ? ` ${resumeTag}` : ''}` : 'Play'}
          </button>
          <button type="button" className="btn-glass" onClick={openDetail}>
            Details
          </button>
          <button
            type="button"
            className={`btn-square ${libraryEntry ? 'btn-square-active' : ''}`}
            title={libraryEntry ? 'Remove from library' : 'Add to library'}
            onClick={toggleLibrary}
          >
            <Icon name="bookmark" size={16} />
          </button>
        </div>
      </div>
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
