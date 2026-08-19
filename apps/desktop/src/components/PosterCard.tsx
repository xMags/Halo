import type { MetaPreview } from '@halo/core'
import { useNav } from '../nav'

interface Props {
  meta: MetaPreview
  /** `MOVIE` / `SERIES` chip in the poster's top-left (library grid). */
  showKind?: boolean
  /** Mono line under the title; defaults to the release year. */
  metaLine?: string
  /** Fill the grid cell instead of the fixed shelf width. */
  inGrid?: boolean
  /** Runs before navigation — e.g. recording the search term that led here. */
  onBeforePress?: () => void
}

/**
 * The 2:3 poster used by every shelf and the library grid. Art that is missing
 * or still loading falls back to the hatched placeholder with the title
 * printed on it, so a slow image never reads as a broken card.
 */
export function PosterCard({ meta, showKind, metaLine, inGrid, onBeforePress }: Props) {
  const { push } = useNav()
  const sub = metaLine ?? meta.releaseInfo ?? ''

  return (
    <button
      type="button"
      className={`poster ${inGrid ? 'poster-grid-cell' : ''}`}
      title={meta.name}
      onClick={() => {
        onBeforePress?.()
        push({ name: 'detail', type: meta.type, id: meta.id })
      }}
    >
      <div className="art poster-frame">
        {meta.poster ? (
          <img src={meta.poster} alt="" loading="lazy" draggable={false} />
        ) : (
          <div className="art-label">{meta.name}</div>
        )}
        {showKind && <div className="poster-badge">{meta.type.toUpperCase()}</div>}
      </div>
      <div className="poster-name ellipsis">{meta.name}</div>
      {sub && <div className="poster-meta ellipsis">{sub}</div>}
    </button>
  )
}
