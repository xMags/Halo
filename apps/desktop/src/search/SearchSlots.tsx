import { hasNoPoster, type SearchTitle } from '@halo/core'
import { ArtImage } from '../components/ArtImage'
import { metahubPosterUrl } from '../components/artworkUrl'
import { FluentIcon } from '../components/FluentIcon'
import { useTitleMenu } from '../browse/titleMenu'
import { ratingLabel } from '../format'
import { useLibrary } from '../queries'
import type { ActionCardModel } from './useLinkAction'

/*
 * The one slot above the results: the top match, or the action card while the
 * box holds a pasted link, or the slot's placeholder on a visit's first
 * search. Both cards are the same frame with one primary action and the Enter
 * hint beside it.
 */

function EnterHint() {
  return (
    <span className="srch-key" aria-hidden>
      Enter
    </span>
  )
}

/** The best guess for the query: its poster, what it is, three lines of its synopsis, and Open title. */
export function TopMatchCard({ title, onOpen }: { title: SearchTitle; onOpen: () => void }) {
  const titleMenu = useTitleMenu()
  const { data: library } = useLibrary()
  const inLibrary = (library ?? []).some((item) => item.id === `${title.type}:${title.id}` && !item.removedAt)
  const rating = ratingLabel(title.imdbRating)
  return (
    <div
      className="srch-slot srch-slot-click"
      role="button"
      tabIndex={-1}
      onClick={onOpen}
      onContextMenu={(event) => titleMenu.forPoster(event, title)}
    >
      <div className="art srch-slot-poster">
        <ArtImage
          src={hasNoPoster(title) ? undefined : title.poster}
          fallback={metahubPosterUrl(title.id)}
          placeholder={title.type === 'series' ? 'series' : 'movie'}
        />
      </div>
      <div className="srch-slot-text">
        <div className="srch-slot-kicker">TOP MATCH</div>
        <div className="srch-slot-title">{title.name}</div>
        <div className="srch-slot-meta">
          {rating && <span className="srch-slot-rating">{rating}</span>}
          <span className="srch-slot-mono">{title.type === 'series' ? 'SERIES' : 'MOVIE'}</span>
          {title.releaseInfo && <span>{title.releaseInfo}</span>}
          {inLibrary && <span>In library</span>}
        </div>
        {title.description && <div className="srch-slot-blurb">{title.description}</div>}
        <div className="srch-slot-actions">
          <button
            type="button"
            className="btn-accent srch-slot-btn"
            onClick={(event) => {
              event.stopPropagation()
              onOpen()
            }}
          >
            Open title
          </button>
          <EnterHint />
        </div>
      </div>
    </div>
  )
}

const MAGNET_PATH = 'M6,4 H10 V12 A2,2 0 0 0 14,12 V4 H18 V12 A6,6 0 0 1 6,12 Z M6,8 H10 M14,8 H18'

function ActionGlyphTile({ model }: { model: ActionCardModel }) {
  return (
    <div className={`srch-tile srch-tone-${model.tone}`} aria-hidden>
      {model.glyph === 'magnet' ? (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <path d={MAGNET_PATH} />
        </svg>
      ) : (
        <FluentIcon glyph={model.glyph === 'link' ? 'link' : 'idCard'} size={24} />
      )}
    </div>
  )
}

/** What a pasted link or id is, and the one thing it does. */
export function ActionCard({ model }: { model: ActionCardModel }) {
  const primary = model.primary
  const hasMeta = !!(model.rating || model.mono || model.year || model.tail)
  return (
    <div
      className={`srch-slot ${primary ? 'srch-slot-click' : ''}`}
      onClick={primary ? () => primary.run() : undefined}
    >
      {model.glyph === 'poster' ? (
        <div className="art srch-slot-poster">
          <ArtImage
            src={model.poster ?? undefined}
            fallback={model.person ? undefined : metahubPosterUrl(model.metaId)}
            placeholder={model.person ? 'person' : model.mono === 'SERIES' ? 'series' : 'movie'}
          />
        </div>
      ) : (
        <ActionGlyphTile model={model} />
      )}
      <div className="srch-slot-text">
        <div className="srch-slot-kicker">{model.kicker}</div>
        <div className="srch-slot-title">{model.title}</div>
        {hasMeta && (
          <div className="srch-slot-meta">
            {model.rating && <span className="srch-slot-rating">{model.rating}</span>}
            {model.mono && <span className="srch-slot-mono ellipsis">{model.mono}</span>}
            {model.year && <span>{model.year}</span>}
            {model.tail && <span className="srch-slot-mono">{model.tail}</span>}
          </div>
        )}
        {model.progress !== null && (
          <div className="srch-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(model.progress * 100)}>
            <div style={{ width: `${model.progress * 100}%` }} />
          </div>
        )}
        {model.secondary && <div className="srch-slot-secondary">{model.secondary}</div>}
        {primary && (
          <div className="srch-slot-actions">
            <button
              type="button"
              className="btn-accent srch-slot-btn"
              onClick={(event) => {
                event.stopPropagation()
                primary.run()
              }}
            >
              {primary.playGlyph && <FluentIcon glyph="play" size={14} />}
              <span>{primary.label}</span>
            </button>
            <EnterHint />
          </div>
        )}
        {model.plain && (
          <div className="srch-slot-actions">
            <button
              type="button"
              className={`${model.plain.destructive ? 'btn-danger' : 'btn'} srch-slot-btn`}
              onClick={(event) => {
                event.stopPropagation()
                model.plain?.run()
              }}
            >
              {model.plain.playGlyph && <FluentIcon glyph="play" size={14} />}
              <span>{model.plain.label}</span>
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

/** The slot's placeholder on a visit's first search: its size, nothing inside. */
export function SlotSkeleton() {
  return <div className="srch-slot-skeleton" aria-hidden />
}
