import { useState } from 'react'
import { Icon } from '../components/Icon'
import { PosterCard } from '../components/PosterCard'
import { PosterRow } from '../components/PosterRow'
import { Segmented } from '../components/Segmented'
import { buildContinueWatching, buildLibraryRow, buildRecentlyWatched } from '../homeRows'
import { useNav } from '../nav'
import {
  browsableCatalogs,
  useCatalog,
  useEffectiveAddons,
  useLibrary,
  useMeta,
  useWatchStates,
  type BrowsableCatalog,
} from '../queries'

/** How many catalog rows Home renders (each is one server round-trip). */
const MAX_ROWS = 8
const POSTER_WIDTH = 148

const FILTERS = ['All', 'Movies', 'Series'] as const
type Filter = (typeof FILTERS)[number]
const FILTER_TYPE: Record<Filter, string | null> = { All: null, Movies: 'movie', Series: 'series' }

export function Home() {
  const [filter, setFilter] = useState<Filter>('All')
  const { data: addons, isLoading, error } = useEffectiveAddons()
  const { data: watchStates } = useWatchStates()
  const { data: library } = useLibrary()

  const allRows = addons ? browsableCatalogs(addons) : []
  const typeFilter = FILTER_TYPE[filter]
  const rows = (typeFilter ? allRows.filter((r) => r.catalog.type === typeFilter) : allRows).slice(
    0,
    MAX_ROWS,
  )

  // Continue Watching stays unfiltered (an in-progress episode matters
  // regardless of the browse filter); history and library follow the filter.
  const continueItems = buildContinueWatching(watchStates, library)
  const recentItems = buildRecentlyWatched(watchStates, library, continueItems, typeFilter)
  const libraryItems = buildLibraryRow(library, typeFilter)

  return (
    <div className="screen-scroll" style={{ paddingBottom: 48 }}>
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '20px 32px 0',
        }}
      >
        <Segmented options={FILTERS} value={filter} onChange={setFilter} />
      </header>

      {error && (
        <div className="error-text" style={{ padding: '16px 32px' }}>
          Could not reach your Halo server: {String(error)}
        </div>
      )}
      {isLoading && (
        <div className="t-caption" style={{ padding: '16px 32px' }}>
          Loading addons…
        </div>
      )}
      {addons && allRows.length === 0 && (
        <div className="t-caption" style={{ padding: '16px 32px' }}>
          No browsable catalogs — add an addon with catalogs (e.g. Cinemeta) in Settings.
        </div>
      )}

      {rows.length > 0 && <FeaturedHero lead={rows[0]!} />}

      {continueItems.length > 0 && (
        <PosterRow title="Continue Watching">
          {continueItems.map((item) => (
            <PosterCard
              key={item.meta.id}
              meta={item.meta}
              width={POSTER_WIDTH}
              progress={item.progress}
            />
          ))}
        </PosterRow>
      )}

      {recentItems.length > 0 && (
        <PosterRow title="Recently Watched">
          {recentItems.map((meta) => (
            <PosterCard key={meta.id} meta={meta} width={POSTER_WIDTH} />
          ))}
        </PosterRow>
      )}

      {libraryItems.length > 0 && (
        <PosterRow title="My Library" action={<SeeAllLibrary />}>
          {libraryItems.map((meta) => (
            <PosterCard key={meta.id} meta={meta} width={POSTER_WIDTH} />
          ))}
        </PosterRow>
      )}

      {rows.map((row) => (
        <CatalogShelf key={`${row.addonId}/${row.catalog.type}/${row.catalog.id}`} row={row} />
      ))}
    </div>
  )
}

function SeeAllLibrary() {
  const { setRoot } = useNav()
  return (
    <button type="button" className="row-link" onClick={() => setRoot('library')}>
      See all →
    </button>
  )
}

/**
 * Featured = first title of the first visible catalog; its full meta brings
 * wide background art and the rating (mobile parity).
 */
function FeaturedHero({ lead }: { lead: BrowsableCatalog }) {
  const { push } = useNav()
  const { data: metas } = useCatalog(lead.addonId, lead.catalog.type, lead.catalog.id)
  const preview = metas?.[0]
  const { data: fullMeta } = useMeta(preview?.type ?? '', preview?.id ?? '', {
    enabled: !!preview,
  })
  const featured = fullMeta ?? preview
  if (!featured) return null

  const openDetail = () => push({ name: 'detail', type: featured.type, id: featured.id })
  const play = () => {
    if (featured.type !== 'movie') {
      // Series need an episode choice first — Detail is the picker.
      openDetail()
      return
    }
    push({
      name: 'streams',
      type: featured.type,
      videoId: featured.id,
      itemId: `${featured.type}:${featured.id}`,
      metaId: featured.id,
      title: featured.name,
      ...(featured.poster ? { poster: featured.poster } : {}),
    })
  }

  const metaLine = [
    featured.releaseInfo,
    (featured.genres ?? [])[0],
    featured.imdbRating ? `★ ${featured.imdbRating}` : undefined,
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <div className="hero">
      <div
        className="hero-bg"
        style={{
          backgroundImage: `url(${featured.background ?? featured.poster})`,
        }}
      />
      <div className="hero-scrim" />
      <div className="hero-body">
        <div className="hero-title">{featured.name}</div>
        {metaLine && <div className="hero-meta">{metaLine}</div>}
        <div className="hero-actions">
          <button className="btn btn-primary btn-row" type="button" onClick={play}>
            <Icon name="play" size={15} />
            Play
          </button>
          <button className="btn btn-glass" type="button" onClick={openDetail}>
            Details
          </button>
        </div>
      </div>
    </div>
  )
}

function CatalogShelf({ row }: { row: BrowsableCatalog }) {
  const { data: metas, isLoading } = useCatalog(row.addonId, row.catalog.type, row.catalog.id)

  // A catalog that errored or came back empty doesn't earn a row.
  if (!isLoading && (!metas || metas.length === 0)) return null

  return (
    <PosterRow title={row.title}>
      {(metas ?? []).slice(0, 30).map((meta) => (
        <PosterCard key={`${meta.type}:${meta.id}`} meta={meta} width={POSTER_WIDTH} />
      ))}
      {isLoading && <div className="t-caption">Loading…</div>}
    </PosterRow>
  )
}
