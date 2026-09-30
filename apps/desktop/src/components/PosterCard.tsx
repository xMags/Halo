import type { MetaPreview } from '@halo/core'
import { ArtImage } from './ArtImage'
import { useTitleMenu } from '../browse/titleMenu'
import { useNav } from '../nav'

interface Props {
  meta: MetaPreview
  /** `MOVIE` / `SERIES` chip in the poster's top-left (library grid). */
  showKind?: boolean
  /** Mono line under the title; defaults to the year and rating. */
  metaLine?: string
  /** Runs before navigation — e.g. recording the search term that led here. */
  onBeforePress?: () => void
}

/**
 * The 2:3 poster used by every shelf and the library grid. Art that is missing
 * or still loading falls back to the placeholder fill with the title printed
 * on it, so a slow image never reads as a broken card.
 */
export function PosterCard({ meta, showKind, metaLine, onBeforePress }: Props) {
  const { push } = useNav()
  const titleMenu = useTitleMenu()
  const sub = metaLine ?? posterMeta(meta)

  return (
    <button
      type="button"
      className="poster"
      title={meta.name}
      onClick={() => {
        onBeforePress?.()
        push({ name: 'detail', type: meta.type, id: meta.id })
      }}
      onContextMenu={(event) => titleMenu.forPoster(event, meta, onBeforePress)}
    >
      <div className="art poster-art">
        <ArtImage src={meta.poster} label={meta.name} lazy />
        {showKind && <div className="poster-badge">{meta.type.toUpperCase()}</div>}
      </div>
      <div className="card-title ellipsis">{meta.name}</div>
      {/* Always drawn, like the native card's meta TextBlock, so a card with
          nothing to say there keeps the same height as its neighbours. */}
      <div className="card-meta ellipsis">{sub}</div>
    </button>
  )
}

/** `2026 · ★ 6.8`, the native card's meta line; either half may be missing. */
function posterMeta(meta: MetaPreview): string {
  return [meta.releaseInfo, meta.imdbRating ? `★ ${meta.imdbRating}` : null]
    .filter(Boolean)
    .join(' · ')
}
