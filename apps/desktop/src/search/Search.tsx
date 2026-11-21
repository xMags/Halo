import {
  cappedQuery,
  classifySearchInput,
  isSearchableQuery,
  metaKey,
  personRowLine,
  knownForLabel,
  searchSuggestions,
  SEARCH_DEBOUNCE_MS,
  SEARCH_RECENT_SHOWN,
  type PersonSummary,
} from '@halo/core'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ArtImage } from '../components/ArtImage'
import { FluentIcon } from '../components/FluentIcon'
import { PosterCard } from '../components/PosterCard'
import { PosterCardSkeleton } from '../components/Skeleton'
import { useCapabilities } from '../capabilities'
import { useNav } from '../nav'
import { SEARCH_FIELD_TRANSITION } from './searchGlide'
import { addSearchTerm, clearSearchHistory, getSearchHistory, removeSearchTerm, type SearchHistoryEntry } from './searchHistory'
import { setSearchQuery, useSearchQuery } from './searchQuery'
import { currentSearchVisit } from './searchVisit'
import { ActionCard, SlotSkeleton, TopMatchCard } from './SearchSlots'
import { useLinkAction } from './useLinkAction'
import { useSearchSession, type SearchFilter } from './useSearchSession'

/*
 * Search: no page title, a centred pill field, quiet kind chips under it and
 * one status line. Before a query, the recent searches and a legend of what
 * the box accepts. During one: the people the words name, one slot (the top
 * match, or the action card for a pasted link), what the library already
 * holds, then the results, which once shown never move (useSearchSession).
 */

const FILTERS: Array<{ value: SearchFilter; label: string }> = [
  { value: null, label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'series', label: 'Series' },
]

/** The box holds the whole text (a long pasted link is read whole); words are cut to the search's length. */
const FIELD_MAX_LENGTH = 2048
const RESULTS_SKELETON_COUNT = 12

/** The kind chip, kept for the visit as the board is (see `useSearchSession`), so Back keeps it. */
let lastFilter: { visit: number; filter: SearchFilter } = { visit: -1, filter: null }

export function Search() {
  const term = useSearchQuery()
  const { push, setRoot } = useNav()
  const { capabilities, needsTmdbKey, refreshIfFailed } = useCapabilities()
  const [activeQuery, setActiveQuery] = useState(() => cappedQuery(term))
  const [filter, setFilterState] = useState<SearchFilter>(() =>
    lastFilter.visit === currentSearchVisit() ? lastFilter.filter : null,
  )
  const setFilter = (next: SearchFilter) => {
    lastFilter = { visit: currentSearchVisit(), filter: next }
    setFilterState(next)
  }
  const [focused, setFocused] = useState(false)
  const [suggestionsDismissed, setSuggestionsDismissed] = useState(false)
  const [history, setHistory] = useState<SearchHistoryEntry[]>(() => getSearchHistory())
  const inputRef = useRef<HTMLInputElement>(null)

  // What the server offers is asked again on arrival when it could not be had.
  useEffect(refreshIfFailed, []) // eslint-disable-line react-hooks/exhaustive-deps

  const input = useMemo(() => classifySearchInput(term.trim()), [term])
  const textMode = input.kind === 'text'
  // No catalog is asked about a link; what an earlier query asked is dropped.
  const query = textMode ? activeQuery.trim() : ''
  const active = textMode && isSearchableQuery(query)
  const view = useSearchSession(query, active, filter)
  const action = useLinkAction(input)

  // Words wait for a pause in typing; too short to search goes back to the empty page at once.
  useEffect(() => {
    if (!textMode) return
    const capped = cappedQuery(term)
    if (!isSearchableQuery(capped)) {
      setActiveQuery(capped)
      return
    }
    const timer = window.setTimeout(() => setActiveQuery(capped), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [term, textMode])

  /** Only words are remembered, never a pasted link. */
  const record = (value: string) => {
    if (classifySearchInput(value.trim()).kind !== 'text') return
    setHistory(addSearchTerm(value))
  }
  const recordQuery = () => record(query)

  const change = (value: string) => {
    setSuggestionsDismissed(false)
    setSearchQuery(value.slice(0, FIELD_MAX_LENGTH))
  }

  /** A past search or a suggestion: searched at once, no pause. */
  const searchAgain = (value: string) => {
    const capped = cappedQuery(value)
    setSuggestionsDismissed(true)
    setSearchQuery(capped)
    setActiveQuery(capped)
    record(capped)
    inputRef.current?.focus()
  }

  const openTopMatch = () => {
    const top = view.topMatch
    if (!top) return
    recordQuery()
    push({ name: 'detail', type: top.type, id: top.id })
  }

  const submit = () => {
    if (!textMode) {
      action?.primary?.run()
      return
    }
    if (!isSearchableQuery(term)) return
    setSuggestionsDismissed(true)
    const capped = cappedQuery(term)
    // The top match of exactly what the box holds: Enter opens it, as the
    // hint beside its button says. Text not searched yet is searched instead.
    if (view.topMatch && !view.stale && capped === activeQuery) {
      openTopMatch()
      return
    }
    setActiveQuery(capped)
    record(capped)
  }

  const openPerson = (person: PersonSummary) => {
    if (!capabilities.people || person.tmdbId <= 0) return
    recordQuery()
    push({ name: 'person', tmdbId: person.tmdbId, personName: person.name, ...(person.photo ? { photo: person.photo } : {}) })
  }

  const historyTerms = history.map((entry) => entry.term)
  const suggestions =
    focused && !suggestionsDismissed && textMode ? searchSuggestions(historyTerms, cappedQuery(term), view.foundNames(cappedQuery(term))) : []
  const recent = historyTerms.slice(0, SEARCH_RECENT_SHOWN)
  const idle = !active && textMode
  const placeholder = capabilities.people
    ? 'Search films, series and people, or paste a link'
    : 'Search films and series, or paste a link'

  return (
    <div className="view">
      <div className="srch">
        <div className="srch-head">
          <div className="srch-field-col">
            <div className="srch-field" style={{ viewTransitionName: SEARCH_FIELD_TRANSITION }}>
              <input
                ref={inputRef}
                autoFocus
                spellCheck={false}
                maxLength={FIELD_MAX_LENGTH}
                placeholder={placeholder}
                aria-label="Search"
                value={term}
                onChange={(event) => change(event.target.value)}
                onFocus={() => setFocused(true)}
                onBlur={() => setFocused(false)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') submit()
                  else if (event.key === 'Escape' && term) {
                    event.stopPropagation()
                    change('')
                  }
                }}
              />
              {term && (
                <button
                  type="button"
                  className="srch-clear"
                  aria-label="Clear"
                  title="Clear"
                  // Pressing keeps the caret in the box.
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => {
                    change('')
                    inputRef.current?.focus()
                  }}
                >
                  <FluentIcon glyph="clear" size={10} />
                </button>
              )}
              <span className="srch-glyph" aria-hidden>
                <FluentIcon glyph="search" size={18} />
              </span>
            </div>
            {suggestions.length > 0 && (
              <div className="srch-suggest" role="listbox" aria-label="Search suggestions">
                {suggestions.map((suggestion) => (
                  <button
                    key={suggestion}
                    type="button"
                    role="option"
                    aria-selected={false}
                    className="srch-suggest-row"
                    // Taken on press, before the box's blur would remove the list.
                    onMouseDown={(event) => {
                      event.preventDefault()
                      searchAgain(suggestion)
                    }}
                  >
                    <FluentIcon glyph="search" size={14} />
                    <span className="ellipsis">{suggestion}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
          <div className="srch-chips" role="radiogroup" aria-label="Kind">
            {FILTERS.map((option) => (
              <button
                key={option.label}
                type="button"
                role="radio"
                aria-checked={filter === option.value}
                className={`srch-chip ${filter === option.value ? 'srch-chip-on' : ''}`}
                onClick={() => setFilter(option.value)}
              >
                {option.label}
              </button>
            ))}
          </div>
        </div>

        {active && view.status && (
          <div className={`srch-status ${view.status.failed ? 'srch-status-failed' : ''}`} role="status">
            {view.status.spinner && <span className="spinner" />}
            <span className="ellipsis">{view.status.text}</span>
          </div>
        )}

        <div className="srch-body">
          {idle && recent.length > 0 && (
            <div className="srch-recent">
              <span className="srch-recent-label">Recent</span>
              <div className="srch-recent-pills">
                {recent.map((entry) => (
                  <div key={entry} className="srch-pill">
                    <button type="button" className="srch-pill-open" onClick={() => searchAgain(entry)}>
                      <span className="ellipsis">{entry}</span>
                    </button>
                    <button
                      type="button"
                      className="srch-pill-remove"
                      aria-label={`Remove ${entry} from recent searches`}
                      title="Remove"
                      onClick={() => setHistory(removeSearchTerm(entry))}
                    >
                      <FluentIcon glyph="close" size={9} />
                    </button>
                  </div>
                ))}
              </div>
              <button type="button" className="srch-link srch-link-danger" onClick={() => setHistory(clearSearchHistory())}>
                Clear
              </button>
            </div>
          )}

          {idle && <SearchHints people={capabilities.people} lookup={capabilities.lookup} magnets={capabilities.magnets} needsTmdbKey={needsTmdbKey} onOpenSettings={() => setRoot('settings')} />}

          {!textMode && action && (
            <div className="srch-results">
              <ActionCard model={action} />
            </div>
          )}

          {active && (
            <div className={`srch-results ${view.stale ? 'srch-stale' : ''}`}>
              {view.people.length > 0 && (
                <section className="srch-section">
                  <h2 className="srch-section-title">People</h2>
                  <div className="srch-people">
                    {view.people.map((person) => (
                      <PersonCard key={person.tmdbId} person={person} onOpen={() => openPerson(person)} />
                    ))}
                  </div>
                </section>
              )}

              {view.topMatch && <TopMatchCard title={view.topMatch} onOpen={openTopMatch} />}
              {view.skeleton && <SlotSkeleton />}

              {view.library.length > 0 && (
                <section className="srch-section">
                  <h2 className="srch-section-title">In your library</h2>
                  <div className="search-grid">
                    {view.library.map((meta) => (
                      <PosterCard key={metaKey(meta)} meta={meta} showKind={filter === null} onBeforePress={recordQuery} softPlaceholder />
                    ))}
                  </div>
                </section>
              )}

              {view.results.length > 0 && (
                <section className="srch-section">
                  <h2 className="srch-section-title">
                    Results <span className="srch-count">{view.results.length}</span>
                  </h2>
                  <div className="search-grid">
                    {view.results.map((meta) => (
                      <PosterCard key={metaKey(meta)} meta={meta} showKind={filter === null} onBeforePress={recordQuery} softPlaceholder />
                    ))}
                  </div>
                </section>
              )}

              {view.skeleton && (
                <section className="srch-section" aria-hidden>
                  <h2 className="srch-section-title">Results</h2>
                  <div className="search-grid">
                    {Array.from({ length: RESULTS_SKELETON_COUNT }, (_, index) => (
                      <PosterCardSkeleton key={index} />
                    ))}
                  </div>
                </section>
              )}
            </div>
          )}

          {active && view.filteredEmpty && (
            <EmptyState glyph="filter" text={`No ${filter === 'movie' ? 'movies' : 'series'} match “${view.revealedQuery}”.`}>
              <button type="button" className="srch-link" onClick={() => setFilter(null)}>
                Show all results
              </button>
            </EmptyState>
          )}
          {active && view.nothingFound && (
            <EmptyState glyph="search" title="No matching titles" text="Check the spelling, or try fewer words." />
          )}
          {active && view.addonsUnreachable && (
            <EmptyState glyph="networkOff" title="Search could not reach your addons" text="Check your connection and try again.">
              <button type="button" className="btn srch-slot-btn" onClick={view.retry}>
                Try again
              </button>
            </EmptyState>
          )}
          {active && view.noAddons && (
            <EmptyState glyph="plugin" title="None of your addons can search" text="Add one that can, such as Cinemeta, in Settings.">
              <button type="button" className="btn srch-slot-btn" onClick={() => setRoot('settings')}>
                Open Settings
              </button>
            </EmptyState>
          )}
        </div>
      </div>
    </div>
  )
}

/** One person of the people row: their portrait in a poster card, the 2:3 shape TMDB's photos come in. */
function PersonCard({ person, onOpen }: { person: PersonSummary; onOpen: () => void }) {
  return (
    <button type="button" className="poster srch-person" title={personRowLine(person) || person.name} onClick={onOpen}>
      <div className="art poster-art">
        <ArtImage src={person.photo ?? undefined} placeholder="person" lazy />
        <div className="poster-badge">PERSON</div>
      </div>
      <div className="card-title ellipsis">{person.name}</div>
      <div className="card-meta ellipsis">{knownForLabel(person.knownFor)}</div>
    </button>
  )
}

/** What the box accepts, as a legend; the items do nothing when clicked. */
function SearchHints({
  people,
  lookup,
  magnets,
  needsTmdbKey,
  onOpenSettings,
}: {
  people: boolean
  lookup: boolean
  magnets: boolean
  needsTmdbKey: boolean
  onOpenSettings: () => void
}) {
  return (
    <div className="srch-hints">
      <div className="srch-hints-title">You can search anything…</div>
      <div className="srch-hints-grid">
        <div className="srch-hint">
          <FluentIcon glyph="film" size={24} />
          <span>Movies &amp; Series</span>
        </div>
        {people && (
          <div className="srch-hint">
            <FluentIcon glyph="person" size={24} />
            <span>Actors, Directors &amp; Writers</span>
          </div>
        )}
        <div className="srch-hint">
          <FluentIcon glyph="link" size={24} />
          <span>{magnets ? 'HTTP & Magnet Links' : 'HTTP Links'}</span>
        </div>
        <div className="srch-hint">
          <span className="srch-imdb-mark" aria-hidden>
            <span>IMDb</span>
          </span>
          {/* TVDB links need the lookup; without it only IMDb title links open. */}
          <span>{lookup ? 'IMDb / TVDB Links' : 'IMDb Links'}</span>
        </div>
      </div>
      {needsTmdbKey && (
        <div className="srch-hints-note">
          Add a free TMDB key in{' '}
          <button type="button" className="srch-link" onClick={onOpenSettings}>
            Settings
          </button>{' '}
          to also find actors, directors and writers, and open TVDB links.
        </div>
      )}
    </div>
  )
}

function EmptyState({
  glyph,
  title,
  text,
  children,
}: {
  glyph: 'filter' | 'search' | 'networkOff' | 'plugin'
  title?: string
  text: string
  children?: ReactNode
}) {
  return (
    <div className="srch-empty">
      <FluentIcon glyph={glyph} size={36} />
      {title && <div className="srch-empty-title">{title}</div>}
      <div className="srch-empty-text">{text}</div>
      {children}
    </div>
  )
}
