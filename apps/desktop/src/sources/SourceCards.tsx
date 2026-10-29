import type { Ref } from 'react'
import { FluentIcon } from '../components/FluentIcon'
import { ProgressRing } from '../components/ProgressRing'
import { QualityMedal } from '../components/QualityMedal'
import { medalFor, type SourceEntry } from './sourcePresentation'
import type { OfflineCopy, Pick, SaveState, SourceRow } from './sourcesModel'
import { SaveButton } from './SaveButton'
import { SourceDetailsPanel } from './SourceDetailsPanel'

/**
 * The sheet's cards, laid out as the phone's sources page: the saved copy,
 * the pick arguing for one source, and compact rows that play on a click and
 * open everything else behind their chevron.
 */

/** Instant is green, caching amber; anything that waits is grey. */
function toneOf(entry: SourceEntry): string {
  switch (entry.status) {
    case 'instant':
      return 'sx-tone-instant'
    case 'caching':
      return 'sx-tone-caching'
    default:
      return 'sx-tone-cold'
  }
}

function StatusLine({ entry, label }: { entry: SourceEntry; label: string }) {
  return (
    <>
      <span className={`sx-status-dot ${toneOf(entry)}`} />
      <span className={`sx-status-label ${toneOf(entry)} ellipsis`}>{label}</span>
    </>
  )
}

/**
 * Only the row a download came from wears its state; nothing shows for a
 * row that could merely be saved, which its details offer.
 */
function RowSaveMark({ state }: { state: SaveState }) {
  if (state.kind === 'saved') {
    return (
      <span className="sx-row-mark sx-row-mark-saved" role="img" aria-label="Saved on this device" title="Saved on this device">
        <FluentIcon glyph="check" size={14} />
      </span>
    )
  }
  if (state.kind === 'saving') {
    return (
      <span className="sx-row-mark" title="Saving">
        <ProgressRing size={16} />
      </span>
    )
  }
  return null
}

function Chevron({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      className="sx-chevron"
      aria-label={expanded ? 'Hide source details' : 'Show source details'}
      aria-expanded={expanded}
      title={expanded ? 'Hide details' : 'Details'}
      onClick={onToggle}
    >
      <FluentIcon glyph={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
    </button>
  )
}

/**
 * This video's finished download, above everything addons offer: it starts
 * at once and needs no connection. One action, since there is nothing left
 * to save.
 */
export function OfflineCard({ copy, onPlay }: { copy: OfflineCopy; onPlay: () => void }) {
  return (
    <div className="sx-offline">
      <div className="sx-card-line">
        <span className="sx-status-dot sx-tone-instant" />
        <span className="sx-status-label sx-tone-instant">Saved on this device</span>
        <span className="sx-sep">·</span>
        <span className="sx-by">Plays offline</span>
      </div>
      <div className="sx-medal-line">
        {copy.medal && <QualityMedal medal={copy.medal} size="lg" />}
        <span className="sx-meta-line">{copy.meta}</span>
      </div>
      <button type="button" className="sx-btn sx-h44 sx-play-offline" onClick={onPlay}>
        <FluentIcon glyph="playSolid" size={15} />
        <span>Play offline</span>
      </button>
    </div>
  )
}

/** The recommended source, on its own card, with the two things a viewer does with it. */
export function PickCard({
  pick,
  save,
  expanded,
  selected,
  copied,
  onPlay,
  onSave,
  onToggle,
  onCopy,
}: {
  pick: Pick
  save: SaveState
  expanded: boolean
  selected: boolean
  copied: boolean
  onPlay: () => void
  onSave: () => void
  onToggle: () => void
  onCopy: () => void
}) {
  const { entry } = pick
  return (
    <div className={`sx-pick ${selected ? 'sx-selected' : ''}`}>
      <div className="sx-pick-body">
        <div className="sx-card-line">
          <StatusLine entry={entry} label={pick.status} />
          <span className="sx-sep">·</span>
          <span className="sx-by">Best pick</span>
          <span className="spacer" />
          <Chevron expanded={expanded} onToggle={onToggle} />
        </div>
        <div className="sx-medal-line">
          <QualityMedal medal={medalFor(entry)} size="lg" />
          {pick.meta && <span className="sx-meta-line">{pick.meta}</span>}
        </div>
        <div className="sx-pick-file ellipsis" title={entry.file}>
          {entry.file}
        </div>
        <div className="sx-actions sx-pick-actions">
          <button type="button" className="btn-accent sx-btn sx-h44 sx-play" onClick={onPlay}>
            <FluentIcon glyph="playSolid" size={15} />
            <span>Play</span>
          </button>
          <SaveButton state={save} onSave={onSave} prominent />
        </div>
        {expanded && (
          <div className="sx-pick-details">
            <SourceDetailsPanel details={pick.details} file={entry.file} warning={pick.warning} copied={copied} onCopy={onCopy} />
          </div>
        )}
      </div>
    </div>
  )
}

export function GroupHeader({ name, count }: { name: string; count: number }) {
  return (
    <div className="sx-group">
      <span className="sx-group-name ellipsis">{name}</span>
      <span className="spacer" />
      <span className="sx-group-count">{count}</span>
    </div>
  )
}

/** The collapsed "needs downloading first" group, drawn as a dashed outline. */
export function RevealRow({ label, onReveal }: { label: string; onReveal: () => void }) {
  return (
    <button type="button" className="sx-reveal" aria-label={label} onClick={onReveal}>
      <svg className="sx-reveal-outline" aria-hidden>
        <rect x="0.5" y="0.5" rx="10" ry="10" />
      </svg>
      <span>{label}</span>
    </button>
  )
}

/**
 * One source: its medal, whether it starts now, and a quiet line of facts.
 * Clicking plays it; the chevron opens the details, where saving lives.
 */
export function SourceCard({
  row,
  save,
  expanded,
  selected,
  copied,
  cardRef,
  onPlay,
  onSave,
  onToggle,
  onCopy,
  onFocus,
}: {
  row: SourceRow
  save: SaveState
  expanded: boolean
  selected: boolean
  copied: boolean
  cardRef: Ref<HTMLDivElement>
  onPlay: () => void
  onSave: () => void
  onToggle: () => void
  onCopy: () => void
  onFocus: () => void
}) {
  const { entry } = row
  return (
    <div ref={cardRef} className={`sx-card ${selected ? 'sx-selected' : ''}`}>
      <div className="sx-card-head">
        <button type="button" className="sx-card-main" title="Play" onClick={onPlay} onFocus={onFocus}>
          <QualityMedal medal={medalFor(entry)} />
          <span className="sx-card-text">
            <span className="sx-card-line">
              <StatusLine entry={entry} label={row.status} />
            </span>
            {row.meta && <span className="sx-meta-line ellipsis">{row.meta}</span>}
          </span>
          <RowSaveMark state={save} />
        </button>
        <Chevron expanded={expanded} onToggle={onToggle} />
      </div>
      {expanded && (
        <div className="sx-card-details">
          <SourceDetailsPanel
            details={row.details}
            file={entry.file}
            warning={row.warning}
            save={{ state: save, onSave }}
            copied={copied}
            onCopy={onCopy}
          />
        </div>
      )}
    </div>
  )
}
