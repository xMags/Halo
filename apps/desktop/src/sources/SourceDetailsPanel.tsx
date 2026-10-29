import { FluentIcon } from '../components/FluentIcon'
import type { DetailChip, SourceDetails } from './sourcePresentation'
import type { SaveState } from './sourcesModel'
import { SaveButton } from './SaveButton'

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
 * The expanded read-out under a card: the full release name, then picture,
 * sound, bandwidth, subtitles and provenance as tiles (the native sheet's
 * `SourceDetailsTemplate`, tile for tile), then saving and the copy button.
 * A row keeps its Save here; the pick has its own on the card, so passes none.
 */
export function SourceDetailsPanel({
  details,
  file,
  warning,
  save,
  copied,
  onCopy,
}: {
  details: SourceDetails
  file: string
  /** `Needs 48 Mbps, more than your line`, or empty. */
  warning: string
  save?: { state: SaveState; onSave: () => void }
  copied: boolean
  onCopy: () => void
}) {
  return (
    <div className="sx-details">
      <div className="sx-details-file">{file}</div>
      {warning && (
        <div className="sx-warning">
          <FluentIcon glyph="warning" size={13} />
          <span>{warning}</span>
        </div>
      )}

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

      <div className="sx-actions">
        {save && <SaveButton state={save.state} onSave={save.onSave} />}
        <button type="button" className="btn sx-btn sx-h40 sx-copy" onClick={onCopy}>
          <FluentIcon glyph="copy" size={12} />
          <span>{copied ? 'File name copied' : 'Copy file name'}</span>
        </button>
      </div>
    </div>
  )
}
