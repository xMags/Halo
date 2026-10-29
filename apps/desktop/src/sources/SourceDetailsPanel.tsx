import { FluentIcon } from '../components/FluentIcon'
import type { SourceDetails } from './sourcePresentation'
import type { SaveState } from './sourcesModel'
import { SaveButton } from './SaveButton'

/**
 * The expanded read-out under a card, kept tight: the full release name, a
 * short list of what the release says about itself, where it comes from and
 * what it asks of the line, then saving and the copy button. A row keeps its
 * Save here; the pick has its own on the card, so passes none.
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
  const { bandwidth } = details
  return (
    <div className="sx-details">
      <div className="sx-details-file">{file}</div>
      {warning && (
        <div className="sx-warning">
          <FluentIcon glyph="warning" size={13} />
          <span>{warning}</span>
        </div>
      )}

      <dl className="sx-facts">
        {details.rows.map((row) => (
          <div key={row.label} className="sx-fact">
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
        <div className="sx-fact">
          <dt>From</dt>
          <dd className="sx-fact-from">
            <span className="ellipsis">{details.provider}</span>
            {details.cacheLabel && (
              <span className={`sx-cache ${details.cacheGood ? 'sx-cache-good' : ''}`}>
                <span className="sx-cache-dot" />
                <span>{details.cacheLabel}</span>
              </span>
            )}
          </dd>
        </div>
        {bandwidth && (
          <div className="sx-fact">
            <dt>Bandwidth</dt>
            <dd>
              <span>{bandwidth.label}</span>
              {bandwidth.note && <span className="sx-fact-note">{bandwidth.note}</span>}
              {bandwidth.fraction > 0 && (
                <span
                  className="sx-meter"
                  role="progressbar"
                  aria-valuemin={0}
                  aria-valuemax={1}
                  aria-valuenow={bandwidth.fraction}
                >
                  <span style={{ width: `${bandwidth.fraction * 100}%` }} />
                </span>
              )}
            </dd>
          </div>
        )}
      </dl>

      <div className="sx-actions">
        {save && <SaveButton state={save.state} onSave={save.onSave} />}
        <button type="button" className="btn sx-btn sx-h34 sx-copy" onClick={onCopy}>
          <FluentIcon glyph="copy" size={12} />
          <span>{copied ? 'File name copied' : 'Copy file name'}</span>
        </button>
      </div>
    </div>
  )
}
