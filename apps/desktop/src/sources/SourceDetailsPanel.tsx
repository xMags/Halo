import { FluentIcon } from '../components/FluentIcon'
import type { DetailChip, SourceDetails } from './sourcePresentation'

function Chips({ chips }: { chips: DetailChip[] }) {
  return (
    <div className="sx-chips">
      {chips.map((chip, index) => (
        <span key={`${chip.label}:${index}`} className={`sx-chip ${chip.muted ? 'sx-chip-muted' : ''}`}>
          {chip.label}
        </span>
      ))}
    </div>
  )
}

/**
 * The expanded read-out under a card: picture, sound, bandwidth, subtitles
 * and provenance as tiles, then the copy button. The native sheet's
 * `SourceDetailsTemplate`, tile for tile.
 */
export function SourceDetailsPanel({
  details,
  copied,
  onCopy,
}: {
  details: SourceDetails
  copied: boolean
  onCopy: () => void
}) {
  return (
    <div className="sx-details">
      <div className="sx-tile-pair">
        <div className="sx-tile">
          <div className="sx-tile-caption">PICTURE</div>
          <div className="sx-wrap sx-wrap-9">
            <span className="sx-tile-mono">{details.resolution}</span>
            <span className="sx-tile-data">{details.codec}</span>
          </div>
          <span className="sx-chip sx-chip-start">{details.picture}</span>
        </div>
        <div className="sx-tile">
          <div className="sx-tile-caption">SOUND</div>
          <div className="sx-wrap sx-wrap-9">
            <span className="sx-tile-word">{details.sound}</span>
            <span className="sx-tile-data">{details.channels}</span>
          </div>
          <Chips chips={details.audioLanguages} />
        </div>
      </div>

      <div className="sx-tile">
        <div className="sx-tile-caption-row">
          <span className="sx-tile-caption">BANDWIDTH NEEDED</span>
          <span className="sx-tile-caption">{details.lineLabel}</span>
        </div>
        <div className="sx-wrap sx-wrap-10">
          <span className="sx-tile-mono">{details.mbpsLabel}</span>
          <span className="sx-tile-note">{details.headroom}</span>
        </div>
        <div className="sx-progress" role="progressbar" aria-valuemin={0} aria-valuemax={1} aria-valuenow={details.meterFraction}>
          <span className="sx-progress-track" />
          <span className="sx-progress-fill" style={{ width: `${details.meterFraction * 100}%` }} />
        </div>
      </div>

      <div className="sx-tile-pair">
        <div className="sx-tile sx-tile-9">
          <div className="sx-tile-caption">SUBTITLES</div>
          <Chips chips={details.subtitles} />
        </div>
        <div className="sx-tile">
          <div className="sx-tile-caption">COMES FROM</div>
          <div className="sx-wrap sx-wrap-comes">
            <span className="sx-tile-word">{details.provider}</span>
            <span className={`sx-cache ${details.cacheGood ? 'sx-cache-good' : ''}`}>
              <span className="sx-cache-dot" />
              <span>{details.cacheLabel}</span>
            </span>
          </div>
        </div>
      </div>

      <button type="button" className="btn sx-btn sx-h34 sx-copy" onClick={onCopy}>
        <FluentIcon glyph="copy" size={12} />
        <span>{copied ? 'File name copied' : 'Copy file name'}</span>
      </button>
    </div>
  )
}
