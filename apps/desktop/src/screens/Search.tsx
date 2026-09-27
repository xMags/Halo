import type { MetaPreview } from '@halo/core'
import { useEffect, useMemo, useRef, useState } from 'react'
import { ArtImage } from '../components/ArtImage'
import { Icon } from '../components/Icon'
import { PosterCard } from '../components/PosterCard'
import { SectionHeader } from '../components/SectionHeader'
import { Shelf } from '../components/Shelf'
import { ShelfSkeleton, TopMatchSkeleton } from '../components/Skeleton'
import { formatRelative } from '../format'
import { useNav } from '../nav'
import { useLibrary, useSearch } from '../queries'
import { setSearchQuery, useSearchQuery } from '../searchQuery'
import {
  addSearchTerm,
  clearSearchHistory,
  getSearchHistory,
  removeSearchTerm,
  type SearchHistoryEntry,
} from '../searchHistory'

const DEBOUNCE_MS = 350
const MIN_QUERY = 2

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

  const { data, isFetching } = useSearch(debounced)
  const query = debounced.trim()
  const active = query.length >= MIN_QUERY
  const groups = data?.groups ?? []

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

  const counts = useMemo(() => {
    const byType = new Map<string, number>()
    let total = 0
    for (const group of groups) {
      for (const meta of group.metas) {
        byType.set(meta.type, (byType.get(meta.type) ?? 0) + 1)
        total += 1
      }
    }
    return { total, byType: [...byType.entries()].sort((a, b) => b[1] - a[1]) }
  }, [groups])

  const shown = typeFilter ? groups.filter((g) => g.type === typeFilter) : groups
  const topMatch = groups[0]?.metas[0]

  return (
    <div className="view">
      <SectionHeader title="Search" />
      <div style={{ display: 'flex', flexDirection: 'column', padding: '8px var(--gu) 40px' }}>
        <div className="search-box search-box-lg">
          <input
            ref={inputRef}
            placeholder="Search movies and series"
            value={term}
            autoFocus
            spellCheck={false}
            onChange={(e) => setSearchQuery(e.target.value)}
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
          {active && (isFetching || !data) ? (
            <span className="spinner" />
          ) : (
            <span style={{ color: 'var(--t3)', display: 'flex' }}>
              <Icon name="search" size={15} />
            </span>
          )}
        </div>

        {active && counts.total > 0 && (
          <div style={{ display: 'flex', gap: 8, marginTop: 14, flexWrap: 'wrap' }}>
            <button
              type="button"
              className={`chip ${typeFilter === null ? 'chip-active' : ''}`}
              onClick={() => setTypeFilter(null)}
            >
              All {counts.total}
            </button>
            {counts.byType.map(([type, count]) => (
              <button
                key={type}
                type="button"
                className={`chip ${typeFilter === type ? 'chip-active' : ''}`}
                onClick={() => setTypeFilter(type)}
              >
                {type.charAt(0).toUpperCase() + type.slice(1)} {count}
              </button>
            ))}
          </div>
        )}

        {(topMatch || history.length > 0) && (
          <div className="search-cols">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
              {topMatch && (
                <>
                  <div className="kicker">TOP MATCH · {groups[0]!.addonName.toUpperCase()}</div>
                  <TopMatchCard meta={topMatch} onOpen={() => recordTerm(query)} />
                </>
              )}
            </div>
            {history.length > 0 && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
                  <div className="kicker">RECENT</div>
                  <div className="spacer" />
                  <button
                    type="button"
                    className="btn-link"
                    onClick={() => setHistory(clearSearchHistory())}
                  >
                    Clear
                  </button>
                </div>
                <div className="list-card">
                  {history.map((entry) => (
                    <div key={entry.term} style={{ display: 'flex', alignItems: 'center' }}>
                      <button
                        type="button"
                        className="recent-row"
                        onClick={() => searchAgain(entry.term)}
                      >
                        <span style={{ color: 'var(--t3)', display: 'flex', flex: '0 0 15px' }}>
                          <Icon name="clock" size={15} />
                        </span>
                        <span className="recent-term ellipsis">{entry.term}</span>
                        <span className="mono" style={{ color: 'var(--t4)' }}>
                          {entry.at ? formatRelative(entry.at).toUpperCase() : ''}
                        </span>
                      </button>
                      <button
                        type="button"
                        className="icon-btn icon-btn-bare"
                        style={{ width: 24, height: 24, marginRight: 8 }}
                        title="Remove from history"
                        onClick={() => setHistory(removeSearchTerm(entry.term))}
                      >
                        <Icon name="x" size={11} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {!active && history.length === 0 && (
          <div className="state-note" style={{ paddingLeft: 0 }}>
            Search every installed addon — titles, series, anything.
          </div>
        )}
        {active && isFetching && groups.length === 0 && (
          <div className="search-cols" style={{ marginTop: 14 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
              <div className="kicker">TOP MATCH</div>
              <TopMatchSkeleton />
            </div>
          </div>
        )}
        {active && !isFetching && groups.length === 0 && (
          <div className="state-note" style={{ paddingLeft: 0 }}>
            No results for “{query}”.
          </div>
        )}

      </div>

      {active && isFetching && groups.length === 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 26, padding: '0 0 40px' }}>
          <ShelfSkeleton titleWidth={160} count={6} />
          <ShelfSkeleton titleWidth={140} count={6} />
        </div>
      )}

      {/* The result shelves sit outside the padded block: a shelf carries its
          own gutter, and nesting it inside one would double it. */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 26, padding: '0 0 40px' }}>
        {shown.map((group) => {
          const source = `${group.addonName.toUpperCase()} · ${group.metas.length} RESULTS`
          return (
            <Shelf
              key={group.key}
              title={group.title}
              source={source}
              action={
                <button
                  type="button"
                  className="btn-link"
                  aria-label="See all shelf items"
                  onClick={() =>
                    push({
                      name: 'catalog',
                      title: group.title,
                      source,
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
    meta.releaseInfo,
    meta.type.toUpperCase(),
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
