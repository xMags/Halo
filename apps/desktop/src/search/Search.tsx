import type { MetaPreview } from '@halo/core'
import { useEffect, useMemo, useRef, useState } from 'react'
import { ArtImage } from '../components/ArtImage'
import { FluentIcon } from '../components/FluentIcon'
import { PosterCard } from '../components/PosterCard'
import { SearchBox } from '../components/SearchBox'
import { SectionHeader } from '../components/SectionHeader'
import { ShelfSkeleton, TopMatchSkeleton } from '../components/Skeleton'
import { useNav } from '../nav'
import { browsableCatalogs, useCatalog, useEffectiveAddons, useLibrary, useSearch } from '../queries'
import { setSearchQuery, useSearchQuery } from './searchQuery'
import {
  addSearchTerm,
  clearSearchHistory,
  getSearchHistory,
  removeSearchTerm,
  type SearchHistoryEntry,
} from './searchHistory'
import { interleave, libraryMatches, metaKey, suggestedSearches } from './searchResults'

/** Short enough to feel live while typing, long enough not to ask every addon per keystroke. */
const DEBOUNCE_MS = 250
const MIN_QUERY = 2
/** At most this many recent searches, and only as many as fit on one line. */
const RECENT_SHOWN = 6
/** Enough popular titles to browse without a search, short of a whole catalog. */
const POPULAR_SHOWN = 24

const FILTERS = [
  { value: null, label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
] as const

/**
 * What is popular, for the screen before a search: the first films catalog and
 * the first series catalog the addons offer (Home's own rows, so usually
 * cached already), taking turns, narrowed by the kind filter.
 */
function usePopular(typeFilter: string | null): { metas: MetaPreview[]; loading: boolean } {
  const { data: addons } = useEffectiveAddons()
  const catalogs = addons ? browsableCatalogs(addons) : []
  const movies = catalogs.find((c) => c.catalog.type === 'movie')
  const series = catalogs.find((c) => c.catalog.type === 'series')
  const movieQuery = useCatalog(movies?.addonId ?? '', 'movie', movies?.catalog.id ?? '', {
    enabled: !!movies && typeFilter !== 'series',
  })
  const seriesQuery = useCatalog(series?.addonId ?? '', 'series', series?.catalog.id ?? '', {
    enabled: !!series && typeFilter !== 'movie',
  })
  const lists = [
    typeFilter === 'series' ? [] : (movieQuery.data ?? []),
    typeFilter === 'movie' ? [] : (seriesQuery.data ?? []),
  ]
  return {
    metas: interleave(lists).slice(0, POPULAR_SHOWN),
    loading: movieQuery.isLoading || seriesQuery.isLoading,
  }
}

export function Search() {
  const term = useSearchQuery()
  const [debounced, setDebounced] = useState(term)
  const [history, setHistory] = useState<SearchHistoryEntry[]>(() => getSearchHistory())
  const [typeFilter, setTypeFilter] = useState<string | null>(null)
  const [boxFocused, setBoxFocused] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [term])

  const { groups, topMatch, pending, failed, total } = useSearch(debounced)
  const { data: library } = useLibrary()
  const popular = usePopular(typeFilter)
  const query = debounced.trim()
  const active = query.length >= MIN_QUERY
  const waiting = active && pending > 0

  // History records only deliberate acts (submitting the query or opening a
  // result), never the debounced keystroke stream (mobile parity).
  const recordTerm = (value: string) => setHistory(addSearchTerm(value))

  /** Clicked history entry: search immediately, no debounce wait. */
  const searchAgain = (value: string) => {
    setSearchQuery(value)
    setDebounced(value)
    recordTerm(value)
    inputRef.current?.focus()
  }

  const forget = (value: string) => setHistory(removeSearchTerm(value))
  const forgetAll = () => setHistory(clearSearchHistory())
  const recent = history.slice(0, RECENT_SHOWN)
  const suggestions = boxFocused ? suggestedSearches(history, term) : []

  // The top match is the best guess for the query itself, picked before the
  // kind filter so the filter does not move it (the native page does too). It
  // is shown once, above everything, so the lists below leave it out; so do
  // the results for whatever the library already shows.
  const fromLibrary = useMemo(
    () => (active ? libraryMatches(library, query).filter((meta) => !typeFilter || meta.type === typeFilter) : []),
    [active, library, query, typeFilter],
  )
  const results = useMemo(() => {
    const shownAbove = topMatch ? [...fromLibrary, topMatch] : fromLibrary
    const lists = groups.map((group) => group.metas.filter((meta) => !typeFilter || meta.type === typeFilter))
    return interleave(lists, shownAbove)
  }, [groups, fromLibrary, topMatch, typeFilter])

  const filterLabel = typeFilter === 'movie' ? 'movies' : typeFilter === 'series' ? 'series' : 'titles'
  const failedNames = [...new Set(failed.map((target) => target.addonName))]
  const nothingFound = active && pending === 0 && !topMatch && fromLibrary.length === 0 && results.length === 0
  const filteredAway = active && pending === 0 && fromLibrary.length === 0 && results.length === 0 && groups.length > 0

  return (
    <div className="view">
      <SectionHeader title="Search" />
      <div className="search-head">
        <div className="search-box-wrap">
          <SearchBox
            large
            autoFocus
            inputRef={inputRef}
            value={term}
            onChange={setSearchQuery}
            onFocus={() => setBoxFocused(true)}
            onBlur={() => setBoxFocused(false)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && term.trim().length >= MIN_QUERY) {
                setDebounced(term)
                recordTerm(term)
              } else if (e.key === 'Escape' && term) {
                e.stopPropagation()
                setSearchQuery('')
              }
            }}
          />
          {suggestions.length > 0 && (
            <div className="search-suggest list-card" role="listbox" aria-label="Past searches">
              {suggestions.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  role="option"
                  aria-selected={false}
                  className="search-suggest-row"
                  // Taken on press, before the box's blur would remove the list.
                  onMouseDown={(e) => {
                    e.preventDefault()
                    searchAgain(suggestion)
                  }}
                >
                  <span style={{ color: 'var(--t3)', display: 'flex' }}>
                    <FluentIcon glyph="clock" size={14} />
                  </span>
                  <span className="ellipsis">{suggestion}</span>
                </button>
              ))}
            </div>
          )}
        </div>

        <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
          {FILTERS.map((f) => (
            <button
              key={f.label}
              type="button"
              className={`chip ${typeFilter === f.value ? 'chip-active' : ''}`}
              onClick={() => setTypeFilter(f.value)}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {!active && recent.length > 0 && (
        <div className="search-section">
          <div className="recent-head">
            <div className="kicker">RECENT</div>
            <button type="button" className="btn-link recent-clear" onClick={forgetAll}>
              Clear
            </button>
          </div>
          <div className="recent-pills">
            {recent.map((entry) => (
              <div key={entry.term} className="recent-pill">
                <button type="button" className="recent-pill-open" onClick={() => searchAgain(entry.term)}>
                  <span style={{ color: 'var(--t3)', display: 'flex' }}>
                    <FluentIcon glyph="clock" size={13} />
                  </span>
                  <span className="ellipsis">{entry.term}</span>
                </button>
                <button
                  type="button"
                  className="recent-pill-remove"
                  aria-label={`Remove ${entry.term} from recent searches`}
                  title="Remove"
                  onClick={() => forget(entry.term)}
                >
                  <FluentIcon glyph="delete" size={11} />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
      {!active && (popular.loading || popular.metas.length > 0) && (
        <div className="search-section">
          <div className="kicker">POPULAR RIGHT NOW</div>
          {popular.metas.length > 0 ? (
            <div className="search-grid">
              {popular.metas.map((meta) => (
                <PosterCard key={metaKey(meta)} meta={meta} showKind={!typeFilter} />
              ))}
            </div>
          ) : (
            <ShelfSkeleton titleWidth={0} count={6} />
          )}
        </div>
      )}

      {active && failedNames.length > 0 && (
        <div className="search-note search-section" role="status">
          {failed.length === total
            ? 'Search could not reach your addons. Check your connection and try again.'
            : `${failedNames.join(', ')} did not answer, so ${failedNames.length === 1 ? 'its' : 'their'} results are missing.`}
        </div>
      )}
      {active && total === 0 && (
        <div className="state-note search-empty">
          None of your addons can search. Add one that can, such as Cinemeta, in Settings.
        </div>
      )}

      {active && topMatch && (
        <div className="search-section">
          <div className="kicker">TOP MATCH</div>
          <TopMatchCard meta={topMatch} onOpen={() => recordTerm(query)} />
        </div>
      )}
      {waiting && !topMatch && (
        <div className="search-section">
          <div className="kicker">TOP MATCH</div>
          <TopMatchSkeleton />
        </div>
      )}

      {fromLibrary.length > 0 && (
        <div className="search-section">
          <div className="kicker">IN YOUR LIBRARY</div>
          <div className="search-grid">
            {fromLibrary.map((meta) => (
              <PosterCard
                key={metaKey(meta)}
                meta={meta}
                showKind={!typeFilter}
                onBeforePress={() => recordTerm(query)}
              />
            ))}
          </div>
        </div>
      )}

      {active && results.length > 0 && (
        <div className="search-section">
          <div className="kicker">
            RESULTS <span className="search-count">{results.length}</span>
          </div>
          <div className="search-grid">
            {results.map((meta) => (
              <PosterCard
                key={metaKey(meta)}
                meta={meta}
                showKind={!typeFilter}
                onBeforePress={() => recordTerm(query)}
              />
            ))}
          </div>
        </div>
      )}
      {waiting && results.length === 0 && (
        <div className="search-section">
          <ShelfSkeleton titleWidth={0} count={6} />
        </div>
      )}
      {waiting && results.length > 0 && (
        <div className="search-note search-note-pending search-section" role="status">
          <span className="spinner" /> Still asking {pending} more {pending === 1 ? 'catalog' : 'catalogs'}…
        </div>
      )}

      {filteredAway && typeFilter && (
        <div className="state-note search-empty">
          No {filterLabel} match {'“'}
          {query}
          {'”'}.{' '}
          <button type="button" className="btn-link" onClick={() => setTypeFilter(null)}>
            Show all results
          </button>
        </div>
      )}
      {nothingFound && !typeFilter && total > 0 && failed.length < total && (
        <div className="state-note search-empty">No matching titles.</div>
      )}
      <div className="search-foot" />
    </div>
  )
}

/**
 * The single best guess for the query, given the room to justify itself: art,
 * a mono fact line and a blurb.
 */
function TopMatchCard({ meta, onOpen }: { meta: MetaPreview; onOpen: () => void }) {
  const { push } = useNav()
  const { data: library } = useLibrary()
  const inLibrary = (library ?? []).some((i) => i.id === `${meta.type}:${meta.id}` && !i.removedAt)

  const facts = [
    meta.type === 'series' ? 'SERIES' : 'MOVIE',
    meta.releaseInfo,
    meta.imdbRating ? `★ ${meta.imdbRating}` : null,
    inLibrary ? 'IN LIBRARY' : null,
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <button
      type="button"
      className="top-match"
      onClick={() => {
        onOpen()
        push({ name: 'detail', type: meta.type, id: meta.id })
      }}
    >
      <div className="art top-match-art">
        <ArtImage src={meta.poster} label={meta.name} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 7, paddingTop: 2, minWidth: 0 }}>
        <div className="top-match-title ellipsis">{meta.name}</div>
        <div className="mono">{facts}</div>
        {meta.description && <div className="top-match-blurb">{meta.description}</div>}
      </div>
    </button>
  )
}
