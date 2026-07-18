import { useState } from 'react'
import { PosterCard } from '../components/PosterCard'
import { Segmented } from '../components/Segmented'
import { buildLibraryRow } from '../homeRows'
import { useLibrary } from '../queries'

const FILTERS = ['All', 'Movies', 'Series'] as const
type Filter = (typeof FILTERS)[number]
const FILTER_TYPE: Record<Filter, string | null> = { All: null, Movies: 'movie', Series: 'series' }

export function Library() {
  const { data: items, isLoading, error } = useLibrary()
  const [filter, setFilter] = useState<Filter>('All')

  const shown = buildLibraryRow(items, FILTER_TYPE[filter])
  const hasAny = (items ?? []).some((item) => !item.removedAt)

  return (
    <div className="screen-scroll">
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '20px 32px 16px',
        }}
      >
        <div className="t-large-title">Library</div>
        {hasAny && <Segmented options={FILTERS} value={filter} onChange={setFilter} />}
      </header>

      {error && (
        <div className="error-text" style={{ padding: '0 32px' }}>
          {String(error)}
        </div>
      )}
      {isLoading && (
        <div className="t-caption" style={{ padding: '0 32px' }}>
          Loading…
        </div>
      )}
      {!isLoading && !hasAny && (
        <div className="t-caption" style={{ padding: '0 32px' }}>
          Nothing saved yet. Open a title and click “Add to Library”.
        </div>
      )}

      {shown.length > 0 && (
        <div className="library-grid">
          {shown.map((meta) => (
            <PosterCard key={`${meta.type}:${meta.id}`} meta={meta} />
          ))}
        </div>
      )}
    </div>
  )
}
