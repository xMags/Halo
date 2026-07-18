import { useEffect, useRef, useState } from 'react'
import { Icon } from '../components/Icon'
import { PosterCard } from '../components/PosterCard'
import { PosterRow } from '../components/PosterRow'
import {
  addSearchTerm,
  clearSearchHistory,
  getSearchHistory,
  removeSearchTerm,
} from '../searchHistory'
import { useSearch } from '../queries'

const DEBOUNCE_MS = 350
const POSTER_WIDTH = 148

export function Search() {
  const [term, setTerm] = useState('')
  const [debounced, setDebounced] = useState('')
  const [history, setHistory] = useState<string[]>(() => getSearchHistory())
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [term])

  const { data: results, isFetching } = useSearch(debounced)
  const active = debounced.trim().length >= 2

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

  return (
    <div className="screen-scroll" style={{ paddingBottom: 48 }}>
      <div style={{ padding: '20px 32px 0', maxWidth: 560 }}>
        <div style={{ position: 'relative' }}>
          <span
            style={{
              position: 'absolute',
              left: 14,
              top: '50%',
              transform: 'translateY(-50%)',
              color: 'var(--text-dim)',
              display: 'grid',
            }}
          >
            <Icon name="search" size={16} />
          </span>
          <input
            ref={inputRef}
            className="field"
            style={{ paddingLeft: 40 }}
            placeholder="Search every installed addon…"
            value={term}
            autoFocus
            spellCheck={false}
            onChange={(e) => setTerm(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && term.trim().length >= 2) recordTerm(term)
              else if (e.key === 'Escape' && term) {
                e.stopPropagation()
                setTerm('')
              }
            }}
          />
        </div>
      </div>

      {!active ? (
        history.length === 0 ? (
          <div className="t-caption" style={{ padding: '24px 32px' }}>
            Search every installed addon — titles, series, anything.
          </div>
        ) : (
          <div style={{ padding: '20px 32px 0', maxWidth: 560 }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'baseline',
                justifyContent: 'space-between',
                marginBottom: 4,
              }}
            >
              <div className="t-callout">Recent</div>
              <button
                type="button"
                className="row-link"
                onClick={() => setHistory(clearSearchHistory())}
              >
                Clear
              </button>
            </div>
            {history.map((entry) => (
              <div key={entry} className="history-row">
                <button type="button" className="history-term" onClick={() => searchAgain(entry)}>
                  <Icon name="clock" size={16} />
                  <span
                    style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  >
                    {entry}
                  </span>
                </button>
                <button
                  type="button"
                  className="icon-btn"
                  title="Remove from history"
                  onClick={() => setHistory(removeSearchTerm(entry))}
                >
                  <Icon name="x" size={15} />
                </button>
              </div>
            ))}
          </div>
        )
      ) : isFetching ? (
        <div className="t-caption" style={{ padding: '24px 32px' }}>
          Searching…
        </div>
      ) : (results ?? []).length === 0 ? (
        <div className="t-caption" style={{ padding: '24px 32px' }}>
          No results for “{debounced.trim()}”.
        </div>
      ) : (
        (results ?? []).map((group) => (
          <PosterRow key={group.key} title={group.title}>
            {group.metas.map((meta) => (
              <PosterCard
                key={`${meta.type}:${meta.id}`}
                meta={meta}
                width={POSTER_WIDTH}
                onBeforePress={() => recordTerm(debounced)}
              />
            ))}
          </PosterRow>
        ))
      )}
    </div>
  )
}
