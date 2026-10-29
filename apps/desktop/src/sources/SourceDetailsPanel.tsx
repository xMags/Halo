import type { ReactNode } from 'react'
import { FluentIcon } from '../components/FluentIcon'
import type { SourceDetails } from './sourcePresentation'
import type { SaveState } from './sourcesModel'
import { SaveButton } from './SaveButton'

function Fact({ label, wide, children }: { label: string; wide: boolean; children: ReactNode }) {
  return (
    <div className={`sx-fact ${wide ? 'sx-fact-wide' : ''}`}>
      <div className="sx-fact-label">{label}</div>
      <div className="sx-fact-value">{children}</div>
    </div>
  )
}

/**
 * The expanded read-out under a card: the full release name, then what the
 * release says about itself as a grid of small cells, where it comes from,
 * what it asks of the line, and the actions. A row keeps its Save here; the
 * pick has its own on the card, so passes none.
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
  // The cells pair up two to a line; an odd one out takes the whole line.
  const cells = details.rows.length + 1
  return (
    <div className="sx-details">
      <div className="sx-details-file" title={file}>
        {file}
      </div>
      {warning && (
        <div className="sx-warning">
          <FluentIcon glyph="warning" size={13} />
          <span>{warning}</span>
        </div>
      )}

      <div className="sx-facts">
        {details.rows.map((row) => (
          <Fact key={row.label} label={row.label} wide={false}>
            <span className="ellipsis" title={row.value}>
              {row.value}
            </span>
          </Fact>
        ))}
        <Fact label="From" wide={cells % 2 === 1}>
          <span className="ellipsis">{details.provider}</span>
          {details.cacheLabel && (
            <span className={`sx-cache ${details.cacheGood ? 'sx-cache-good' : ''}`}>
              <span className="sx-cache-dot" />
              {details.cacheLabel}
            </span>
          )}
        </Fact>
        {bandwidth && (
          <Fact label="Bandwidth" wide>
            <span className="sx-bw-value">{bandwidth.label}</span>
            {bandwidth.note && <span className="sx-bw-note">{bandwidth.note}</span>}
            {bandwidth.fraction > 0 && (
              <span
                className={`sx-meter ${warning ? 'sx-meter-over' : ''}`}
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={1}
                aria-valuenow={bandwidth.fraction}
              >
                <span style={{ width: `${bandwidth.fraction * 100}%` }} />
              </span>
            )}
          </Fact>
        )}
      </div>

      <div className="sx-actions">
        {save && <SaveButton state={save.state} onSave={save.onSave} />}
        <button type="button" className="sx-btn sx-h34 sx-copy" onClick={onCopy}>
          <FluentIcon glyph={copied ? 'check' : 'copy'} size={12} />
          <span>{copied ? 'Copied' : 'Copy file name'}</span>
        </button>
      </div>
    </div>
  )
}
