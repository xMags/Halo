import type { MetaPreview } from '@halo/core'
import { useMemo, useState } from 'react'
import { FluentIcon } from '../components/FluentIcon'
import { Menu, MenuAnchor, MenuItem } from '../components/Menu'
import { PosterCard } from '../components/PosterCard'
import { SectionHeader } from '../components/SectionHeader'
import { Segmented } from '../components/Segmented'
import { PosterCardSkeleton } from '../components/Skeleton'
import { buildLibraryRow } from '../home/homeRows'
import { useLibrary, useWatchStates } from '../queries'

const SORTS = [
  { value: 'added', label: 'Recently added' },
  { value: 'name', label: 'Title A–Z' },
  { value: 'watched', label: 'Recently watched' },
] as const
type Sort = (typeof SORTS)[number]['value']

type Filter = 'all' | 'movie' | 'series'

export function Library() {
  const { data: items, isLoading, error } = useLibrary()
  const { data: watchStates } = useWatchStates()
  const [filter, setFilter] = useState<Filter>('all')
  const [sort, setSort] = useState<Sort>('added')
  const [sortOpen, setSortOpen] = useState(false)

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

  return (
    <div className="view view-col">
      <SectionHeader title="Library" />
      <div className="lib-bar">
        <Segmented
          options={[
            { value: 'all', label: `All ${counts.all}` },
            { value: 'movie', label: `Movies ${counts.movie}` },
            { value: 'series', label: `Series ${counts.series}` },
          ]}
          value={filter}
          onChange={setFilter}
        />
        <div className="spacer" />
        <div style={{ fontSize: 14, color: 'var(--t3)' }}>Sort by</div>
        <MenuAnchor>
          <button type="button" className="btn btn-body" onClick={() => setSortOpen((open) => !open)}>
            <span>{SORTS.find((option) => option.value === sort)!.label}</span>
            <span style={{ color: 'var(--t2)', display: 'flex' }}>
              <FluentIcon glyph="chevronDown" size={13} />
            </span>
          </button>
          <Menu open={sortOpen} onClose={() => setSortOpen(false)}>
            {SORTS.map((option) => (
              <MenuItem
                key={option.value}
                label={option.label}
                radio
                checked={sort === option.value}
                onClick={() => {
                  setSort(option.value)
                  setSortOpen(false)
                }}
              />
            ))}
          </Menu>
        </MenuAnchor>
      </div>

      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', overflowX: 'hidden' }}>
        {error && <div className="state-note error-text">{String(error)}</div>}
        {isLoading && (
          <div className="lib-grid">
            {Array.from({ length: 12 }).map((_, index) => (
              <PosterCardSkeleton key={`skeleton-${index}`} showKind />
            ))}
          </div>
        )}
        {!isLoading && active.length === 0 && (
          <div className="state-note">
            Nothing saved yet. Open a title and use its star button to keep it here.
          </div>
        )}
        {!isLoading && active.length > 0 && shown.length === 0 && (
          <div className="state-note">
            No {filter === 'movie' ? 'movies' : 'series'} in your library.
          </div>
        )}

        {shown.length > 0 && (
          <div className="lib-grid">
            {shown.map((meta) => (
              <PosterCard key={`${meta.type}:${meta.id}`} meta={meta} showKind />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
