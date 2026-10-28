import type { MetaPreview } from '@halo/core'
import { useEffect, useMemo, useRef, useState } from 'react'
import { ArtImage } from '../components/ArtImage'
import { FluentIcon } from '../components/FluentIcon'
import { PosterCard } from '../components/PosterCard'
import { SearchBox } from '../components/SearchBox'
import { SectionHeader } from '../components/SectionHeader'
import { Shelf } from '../components/Shelf'
import { ShelfSkeleton, TopMatchSkeleton } from '../components/Skeleton'
import { formatRelative } from '../format'
import { useNav } from '../nav'
import { useLibrary, useSearch } from '../queries'
import { setSearchQuery, useSearchQuery } from './searchQuery'
import {
  addSearchTerm,
  clearSearchHistory,
  getSearchHistory,
  removeSearchTerm,
  type SearchHistoryEntry,
} from './searchHistory'

const DEBOUNCE_MS = 350
const MIN_QUERY = 2
/** Recent searches shown beside the top match; more would push the results down. */
const RECENT_SHOWN = 6

/** A row's kind, printed before its addon so two "Popular" rows read apart. */
function kindLabel(type: string): string {
  if (type === 'movie') return 'Movies'
  if (type === 'series') return 'Series'
  return type.charAt(0).toUpperCase() + type.slice(1)
}

const FILTERS = [
  { value: null, label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
] as const

export function Search() {
  const { push } = useNav()
  const term = useSearchQuery()
  const [debounced, setDebounced] = useState(term)
  const [history, setHistory] = useState<SearchHistoryEntry[]>(() => getSearchHistory())
  const [typeFilter, setTypeFilter] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [term])

  const { groups, topMatch, pending, failed, total } = useSearch(debounced)
  const query = debounced.trim()
  const active = query.length >= MIN_QUERY
  const waiting = active && pending > 0

  // History records only deliberate acts — submitting the query or opening a
  // result — never the debounced keystroke stream (mobile parity).
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

  const shown = useMemo(() => {
    return groups.flatMap((group) => {
      const metas = typeFilter ? group.metas.filter((m) => m.type === typeFilter) : group.metas
      if (metas.length === 0) return []
      return [{ ...group, metas }]
    })
  }, [groups, typeFilter])

  // The top match is the best guess for the query itself, picked before the
  // kind filter so the filter does not move it (the native page does too).
  const filterLabel = typeFilter === 'movie' ? 'movies' : typeFilter === 'series' ? 'series' : 'titles'
  const failedNames = [...new Set(failed.map((target) => target.addonName))]

  return (
    <div className="view">
      <SectionHeader title="Search" />
      <div style={{ display: 'flex', flexDirection: 'column', padding: '8px var(--gu) 0' }}>
        <SearchBox
          large
          autoFocus
          inputRef={inputRef}
          value={term}
          onChange={setSearchQuery}
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

        {(topMatch || recent.length > 0) && (
          <div className="search-cols">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
              {topMatch && (
                <>
                  <div className="kicker">TOP MATCH</div>
                  <TopMatchCard meta={topMatch} onOpen={() => recordTerm(query)} />
                </>
              )}
            </div>
            {recent.length > 0 && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10, width: 232, flexShrink: 0 }}>
                <div className="recent-head">
                  <div className="kicker">RECENT</div>
                  <button type="button" className="btn-link recent-clear" onClick={forgetAll}>
                    Clear
                  </button>
                </div>
                <div className="list-card">
                  {recent.map((entry) => (
                    <div key={entry.term} className="recent-row">
                      <button type="button" className="recent-open" onClick={() => searchAgain(entry.term)}>
                        <span style={{ color: 'var(--t3)', display: 'flex', flex: '0 0 15px' }}>
                          <FluentIcon glyph="clock" size={15} />
                        </span>
                        <span className="recent-term ellipsis">{entry.term}</span>
                        <span className="mono recent-when">
                          {entry.at ? formatRelative(entry.at).toUpperCase() : 'RECENT'}
                        </span>
                      </button>
                      <button
                        type="button"
                        className="recent-remove"
                        aria-label={`Remove ${entry.term} from recent searches`}
                        title="Remove"
                        onClick={() => forget(entry.term)}
                      >
                        <FluentIcon glyph="close" size={10} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {waiting && !topMatch && (
          <div className="search-cols" style={{ marginTop: 14 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
              <div className="kicker">TOP MATCH</div>
              <TopMatchSkeleton />
            </div>
          </div>
        )}
        {active && failedNames.length > 0 && (
          <div className="search-note" role="status">
            {failed.length === total
              ? 'Search could not reach your addons. Check your connection and try again.'
              : `${failedNames.join(', ')} did not answer, so ${failedNames.length === 1 ? 'its' : 'their'} results are missing.`}
          </div>
        )}
        {active && total > 0 && pending === 0 && groups.length === 0 && failed.length < total && (
          <div className="state-note" style={{ paddingLeft: 0, textAlign: 'center', marginTop: 30 }}>
            No matching titles.
          </div>
        )}
        {active && total === 0 && (
          <div className="state-note" style={{ paddingLeft: 0, textAlign: 'center', marginTop: 30 }}>
            None of your addons can search. Add one that can, such as Cinemeta, in Settings.
          </div>
        )}
        {active && groups.length > 0 && shown.length === 0 && pending === 0 && (
          <div className="state-note" style={{ paddingLeft: 0, textAlign: 'center', marginTop: 30 }}>
            No {filterLabel} match {'\u201c'}{query}{'\u201d'}.{' '}
            <button type="button" className="btn-link" onClick={() => setTypeFilter(null)}>
              Show all results
            </button>
          </div>
        )}

      </div>

      {waiting && shown.length === 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 26, padding: '26px 0 0' }}>
          <ShelfSkeleton titleWidth={160} count={6} />
          <ShelfSkeleton titleWidth={140} count={6} />
        </div>
      )}

      {/* The result shelves sit outside the padded block: a shelf carries its
          own gutter, and nesting it inside one would double it. */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 26,
          padding: `${shown.length > 0 ? 26 : 0}px 0 40px`,
        }}
      >
        {shown.map((group) => {
          return (
            <Shelf
              key={group.key}
              title={group.title}
              source={`${kindLabel(group.type)} · ${group.addonName}`}
              action={
                <button
                  type="button"
                  className="btn-link"
                  aria-label="See all shelf items"
                  onClick={() =>
                    push({
                      name: 'catalog',
                      title: group.title,
                      source: `${kindLabel(group.type)} · ${group.addonName}`,
                      items: group.metas.map((meta) => ({ meta })),
                    })
                  }
                >
                  See all
                </button>
              }
            >
              {group.metas.map((meta) => (
                <PosterCard
                  key={`${meta.type}:${meta.id}`}
                  meta={meta}
                  onBeforePress={() => recordTerm(query)}
                />
              ))}
            </Shelf>
          )
        })}
        {waiting && shown.length > 0 && (
          <div className="search-note search-note-pending" role="status">
            <span className="spinner" /> Still asking {pending} more {pending === 1 ? 'catalog' : 'catalogs'}…
          </div>
        )}
      </div>
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
