import type { MetaPreview } from '@halo/core'
import { useNav } from '../nav'

interface Props {
  meta: MetaPreview
  /** 0..1 fills the thin bar at the poster's foot (Continue Watching). */
  progress?: number
  /** Fixed card width for shelf rows; omit inside grids (cell-sized). */
  width?: number
  /** Runs before navigation — e.g. recording the search term that led here. */
  onBeforePress?: () => void
}

export function PosterCard({ meta, progress, width, onBeforePress }: Props) {
  const { push } = useNav()

  return (
    <button
      type="button"
      className="poster-card"
      // Fixed width in shelves; unset in grids, where the cell sizes the card
      // and .poster-frame's aspect-ratio keeps 2:3.
      style={width !== undefined ? { width } : undefined}
      title={meta.name}
      onClick={() => {
        onBeforePress?.()
        push({ name: 'detail', type: meta.type, id: meta.id })
      }}
    >
      <div className="poster-frame">
        {meta.poster ? (
          <img src={meta.poster} alt="" loading="lazy" draggable={false} />
        ) : (
          <div className="poster-fallback">{meta.name}</div>
        )}
        {progress !== undefined && (
          <div className="poster-progress">
            <div style={{ width: `${Math.round(progress * 100)}%` }} />
          </div>
        )}
      </div>
      <div className="poster-name">{meta.name}</div>
    </button>
  )
}
