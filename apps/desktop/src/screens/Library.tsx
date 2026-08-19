import type { MetaPreview } from '@halo/core'
import { useMemo, useState } from 'react'
import { PosterCard } from '../components/PosterCard'
import { Segmented } from '../components/Segmented'
import { buildLibraryRow } from '../homeRows'
import { useLibrary, useWatchStates } from '../queries'
import { usePublishScreenTitle } from '../screenTitle'

const SORTS = [
  { value: 'added', label: 'Recently added' },
  { value: 'watched', label: 'Recently watched' },
  { value: 'name', label: 'Name A–Z' },
] as const
type Sort = (typeof SORTS)[number]['value']

type Filter = 'all' | 'movie' | 'series'

export function Library() {
  const { data: items, isLoading, error } = useLibrary()
  const { data: watchStates } = useWatchStates()
  const [filter, setFilter] = useState<Filter>('all')
  const [sort, setSort] = useState<Sort>('added')

  const active = useMemo(() => (items ?? []).filter((item) => !item.removedAt), [items])
  const counts = {
    all: active.length,
    movie: active.filter((i) => i.type === 'movie').length,
    series: active.filter((i) => i.type === 'series').length,
  }

  // Most recent watch activity per library item — the "recently watched" sort
  // key. Items never played sort last, keeping their relative order.
  const lastWatched = useMemo(() => {
    const map = new Map<string, number>()
    for (const state of watchStates ?? []) {
      map.set(state.itemId, Math.max(map.get(state.itemId) ?? 0, state.updatedAt))
    }
    return map
  }, [watchStates])

  const shown = useMemo(() => {
    const rows = buildLibraryRow(items, filter === 'all' ? null : filter)
    if (sort === 'name') return [...rows].sort((a, b) => a.name.localeCompare(b.name))
    if (sort === 'watched') {
      const key = (meta: MetaPreview) => lastWatched.get(`${meta.type}:${meta.id}`) ?? 0
      return [...rows].sort((a, b) => key(b) - key(a))
    }
    return rows // buildLibraryRow already orders by newest addition
  }, [items, filter, sort, lastWatched])

  usePublishScreenTitle('Library', `${counts.all} TITLES`)

  return (
    <div className="view no-bar">
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '24px var(--g) 0' }}>
        <Segmented
          options={[
            { value: 'all', label: 'All', count: counts.all },
            { value: 'movie', label: 'Movies', count: counts.movie },
            { value: 'series', label: 'Series', count: counts.series },
          ]}
          value={filter}
          onChange={setFilter}
        />
        <div className="spacer" />
        <div className="meta-mono">SORT</div>
        <select
          className="select-chip"
          style={{ fontSize: 12, padding: '6px 9px' }}
          value={sort}
          onChange={(e) => setSort(e.target.value as Sort)}
        >
          {SORTS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      {error && <div className="state-note error-text">{String(error)}</div>}
      {isLoading && (
        <div className="state-note">
          <span className="spinner" /> Loading…
        </div>
      )}
      {!isLoading && active.length === 0 && (
        <div className="state-note">
          Nothing saved yet. Open a title and use the bookmark button to keep it here.
        </div>
      )}
      {!isLoading && active.length > 0 && shown.length === 0 && (
        <div className="state-note">No {filter === 'movie' ? 'movies' : 'series'} in your library.</div>
      )}

      {shown.length > 0 && (
        <div className="library-grid">
          {shown.map((meta) => (
            <PosterCard key={`${meta.type}:${meta.id}`} meta={meta} inGrid showKind />
          ))}
        </div>
      )}
    </div>
  )
}
