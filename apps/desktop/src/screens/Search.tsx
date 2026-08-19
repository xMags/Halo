import type { MetaPreview } from '@halo/core'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Icon } from '../components/Icon'
import { PosterCard } from '../components/PosterCard'
import { Shelf } from '../components/Shelf'
import { formatRelative } from '../format'
import { useNav } from '../nav'
import { useLibrary, useSearch } from '../queries'
import {
  addSearchTerm,
  clearSearchHistory,
  getSearchHistory,
  removeSearchTerm,
  type SearchHistoryEntry,
} from '../searchHistory'
import { usePublishScreenTitle } from '../screenTitle'

const DEBOUNCE_MS = 350
const MIN_QUERY = 2

export function Search() {
  const [term, setTerm] = useState('')
  const [debounced, setDebounced] = useState('')
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
    setTerm(value)
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

  usePublishScreenTitle('Search', query.toUpperCase())

  return (
    <div className="view no-bar">
      <div style={{ padding: '26px var(--g) 0', maxWidth: 720 }}>
        <div className="search-field">
          <span style={{ color: 'var(--accent-text)', display: 'grid' }}>
            <Icon name="search" size={17} />
          </span>
          <input
            ref={inputRef}
            className="search-input"
            placeholder="Search every installed addon…"
            value={term}
            autoFocus
            spellCheck={false}
            onChange={(e) => setTerm(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && term.trim().length >= MIN_QUERY) recordTerm(term)
              else if (e.key === 'Escape' && term) {
                e.stopPropagation()
                setTerm('')
              }
            }}
          />
          {active && (isFetching || !data) && <div className="search-timing">SEARCHING…</div>}
          {term && (
            <button type="button" className="search-clear" title="Clear" onClick={() => setTerm('')}>
              <Icon name="x" size={12} />
            </button>
          )}
        </div>

        {active && counts.total > 0 && (
          <div style={{ display: 'flex', gap: 7, marginTop: 13, flexWrap: 'wrap' }}>
            <button
              type="button"
              className={`filter-pill ${typeFilter === null ? 'filter-pill-active' : ''}`}
              onClick={() => setTypeFilter(null)}
            >
              All results · {counts.total}
            </button>
            {counts.byType.map(([type, count]) => (
              <button
                key={type}
                type="button"
                className={`filter-pill ${typeFilter === type ? 'filter-pill-active' : ''}`}
                onClick={() => setTypeFilter(type)}
              >
                {type.charAt(0).toUpperCase() + type.slice(1)} · {count}
              </button>
            ))}
          </div>
        )}
      </div>

      {(topMatch || history.length > 0) && (
        <div className="search-split">
          <div style={{ flex: 1, minWidth: 0 }}>
            {topMatch && (
              <>
                <div className="kicker" style={{ paddingBottom: 12 }}>
                  TOP MATCH · {groups[0]!.addonName.toUpperCase()}
                </div>
                <TopMatchCard meta={topMatch} onOpen={() => recordTerm(query)} />
              </>
            )}
          </div>
          {history.length > 0 && (
            <div className="recent-col">
              <div
                style={{
                  display: 'flex',
                  alignItems: 'baseline',
                  justifyContent: 'space-between',
                  paddingBottom: 12,
                }}
              >
                <div className="kicker">RECENT</div>
                <button
                  type="button"
                  className="btn-link"
                  onClick={() => setHistory(clearSearchHistory())}
                >
                  Clear
                </button>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                {history.map((entry) => (
                  <div key={entry.term} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <button
                      type="button"
                      className="recent-row"
                      onClick={() => searchAgain(entry.term)}
                    >
                      <span style={{ color: 'var(--text-dimmer)', display: 'grid' }}>
                        <Icon name="clock" size={14} />
                      </span>
                      <span className="spacer ellipsis">{entry.term}</span>
                      <span className="recent-when">{entry.at ? formatRelative(entry.at) : ''}</span>
                    </button>
                    <button
                      type="button"
                      className="icon-btn"
                      style={{ width: 22, height: 22, background: 'transparent' }}
                      title="Remove from history"
                      onClick={() => setHistory(removeSearchTerm(entry.term))}
                    >
                      <Icon name="x" size={12} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {!active && history.length === 0 && (
        <div className="state-note">Search every installed addon — titles, series, anything.</div>
      )}
      {active && isFetching && groups.length === 0 && (
        <div className="state-note">
          <span className="spinner" /> Searching…
        </div>
      )}
      {active && !isFetching && groups.length === 0 && (
        <div className="state-note">No results for “{query}”.</div>
      )}

      {shown.map((group) => (
        <Shelf key={group.key} title={group.title}>
          {group.metas.map((meta) => (
            <PosterCard
              key={`${meta.type}:${meta.id}`}
              meta={meta}
              onBeforePress={() => recordTerm(query)}
            />
          ))}
        </Shelf>
      ))}
    </div>
  )
}

/**
 * The single best guess for the query, given the room to justify itself: art,
 * rating, a blurb, and whether it is already in the library.
 */
function TopMatchCard({
  meta,
  onOpen,
}: {
  meta: MetaPreview
  onOpen: () => void
}) {
  const { push } = useNav()
  const { data: library } = useLibrary()
  const inLibrary = (library ?? []).some((i) => i.id === `${meta.type}:${meta.id}` && !i.removedAt)

  return (
    <button
      type="button"
      className="top-match"
      onClick={() => {
        onOpen()
        push({ name: 'detail', type: meta.type, id: meta.id })
      }}
    >
      <div className="art" style={{ width: 92, flex: '0 0 auto', aspectRatio: '2 / 3', borderRadius: 8, border: '1px solid var(--border)' }}>
        {meta.poster && <img src={meta.poster} alt="" draggable={false} />}
      </div>
      <div style={{ minWidth: 0 }}>
        <div className="top-match-title ellipsis">{meta.name}</div>
        <div style={{ display: 'flex', gap: 10, marginTop: 5, fontSize: 12, color: 'var(--text-muted)' }}>
          {meta.imdbRating && <span className="rating">★ {meta.imdbRating}</span>}
          {meta.releaseInfo && <span>{meta.releaseInfo}</span>}
          <span className="dot-sep">/</span>
          <span>{meta.type.charAt(0).toUpperCase() + meta.type.slice(1)}</span>
        </div>
        {meta.description && (
          <div
            className="body-copy"
            style={{
              marginTop: 9,
              fontSize: 12,
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
            }}
          >
            {meta.description}
          </div>
        )}
        {inLibrary && (
          <div style={{ display: 'flex', gap: 6, marginTop: 11 }}>
            <div className="badge badge-outline">IN LIBRARY</div>
          </div>
        )}
      </div>
    </button>
  )
}
