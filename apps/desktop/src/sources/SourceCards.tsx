import type { Ref } from 'react'
import { FluentIcon } from '../components/FluentIcon'
import { QualityBadge } from '../components/QualityBadge'
import { badgeTierLabel, isPremiumTier, type MetaLine, type SourceEntry } from './sourcePresentation'
import type { Pick, SourceRow } from './sourcesModel'
import { SourceDetailsPanel } from './SourceDetailsPanel'

/** Instant is green, on-disk accent, caching amber; anything that waits is grey. */
function toneOf(entry: SourceEntry): string {
  switch (entry.status) {
    case 'instant':
      return 'sx-tone-instant'
    case 'ondisk':
      return 'sx-tone-ondisk'
    case 'caching':
      return 'sx-tone-caching'
    default:
      return 'sx-tone-cold'
  }
}

function Badge({ entry, labelSize }: { entry: SourceEntry; labelSize?: number }) {
  // Only high dynamic range earns the qualifier; standard range is the norm.
  return (
    <QualityBadge
      tier={badgeTierLabel(entry.tier)}
      detail={entry.hdr ? 'HDR' : undefined}
      gold={isPremiumTier(entry.tier)}
      {...(labelSize ? { labelSize } : {})}
    />
  )
}

function MetaStrip({ meta }: { meta: MetaLine }) {
  return (
    <div className="sx-wrap sx-meta">
      <span className="sx-meta-line">{meta.line}</span>
      <span className={`sx-subs ${meta.hasSubtitles ? '' : 'sx-subs-off'}`}>{meta.subtitles}</span>
    </div>
  )
}

interface ActionProps {
  expanded: boolean
  saving: boolean
  onPlay: () => void
  onSave: () => void
  onToggle: () => void
}

function Actions({ entry, expanded, saving, onPlay, onSave, onToggle, pick }: ActionProps & { entry: SourceEntry; pick: boolean }) {
  return (
    <>
      <button type="button" className={`btn-accent sx-btn sx-h36 sx-play ${pick ? 'sx-gap-9' : ''}`} onClick={onPlay}>
        <FluentIcon glyph="play" size={14} />
        <span>Play</span>
      </button>
      {entry.status !== 'ondisk' && (
        <button type="button" className="btn sx-btn sx-h36 sx-save" disabled={saving} onClick={onSave}>
          <FluentIcon glyph="downloads" size={pick ? 15 : 14} />
          <span>Save for offline</span>
        </button>
      )}
      <span className="spacer" />
      <button type="button" className="btn sx-btn sx-h36 sx-details-btn" onClick={onToggle}>
        <span>{expanded ? 'Hide details' : 'Details'}</span>
        <FluentIcon glyph="chevronDown" size={13} />
      </button>
    </>
  )
}

/** The recommended source, arguing for itself above the list. */
export function PickCard({
  pick,
  watchNote,
  expanded,
  selected,
  copied,
  saving,
  onPlay,
  onSave,
  onToggle,
  onCopy,
}: ActionProps & {
  pick: Pick
  watchNote: string
  selected: boolean
  copied: boolean
  onCopy: () => void
}) {
  const { entry } = pick
  return (
    <div className={`sx-pick ${selected ? 'sx-selected' : ''}`}>
      <svg className="sx-pick-wash" preserveAspectRatio="none" viewBox="0 0 1 1" aria-hidden>
        <defs>
          <linearGradient id="sx-pick-wash" x1="0" y1="0" x2="1" y2="0.62">
            <stop offset="0" className="sx-pick-wash-from" />
            <stop offset="0.62" className="sx-pick-wash-to" />
          </linearGradient>
        </defs>
        <rect width="1" height="1" fill="url(#sx-pick-wash)" />
      </svg>
      <div className="sx-pick-body">
        <div className="sx-pick-status">
          <span className={`sx-status-dot ${toneOf(entry)}`} />
          <span className={`sx-status-label ${toneOf(entry)}`}>{pick.status}</span>
          <span className="sx-pick-sep">·</span>
          <span className="sx-pick-by">Halo picked this for you</span>
        </div>
        <div className="sx-pick-grid">
          <span className="sx-pick-badge">
            <Badge entry={entry} labelSize={13} />
          </span>
          <div className="sx-pick-text">
            <div className="sx-pick-why">{pick.headline}</div>
            <MetaStrip meta={pick.meta} />
            {watchNote && <div className="sx-pick-watch">{watchNote}</div>}
            <div className="sx-pick-file ellipsis" title={entry.file}>
              {entry.file}
            </div>
          </div>
        </div>
        <div className="sx-actions sx-pick-actions">
          <Actions entry={entry} expanded={expanded} saving={saving} onPlay={onPlay} onSave={onSave} onToggle={onToggle} pick />
        </div>
        {expanded && (
          <div className="sx-pick-details">
            <SourceDetailsPanel details={pick.details} copied={copied} onCopy={onCopy} />
          </div>
        )}
      </div>
    </div>
  )
}

export function GroupHeader({ name, note, count }: { name: string; note: string; count: string }) {
  return (
    <div className="sx-group">
      <span className="sx-group-name">{name}</span>
      <span className="sx-group-note ellipsis">{note}</span>
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
        <rect x="0.5" y="0.5" rx="8" ry="8" />
      </svg>
      <span>{label}</span>
    </button>
  )
}

export function SourceCard({
  row,
  expanded,
  selected,
  copied,
  saving,
  cardRef,
  onPlay,
  onSave,
  onToggle,
  onCopy,
}: ActionProps & {
  row: SourceRow
  selected: boolean
  copied: boolean
  cardRef: Ref<HTMLDivElement>
  onCopy: () => void
}) {
  const { entry } = row
  return (
    <div ref={cardRef} className={`sx-card ${selected ? 'sx-selected' : ''}`}>
      <button type="button" className="sx-card-head" aria-label="Show source details" onClick={onToggle}>
        <span className="sx-card-badge">
          <Badge entry={entry} />
        </span>
        <span className="sx-card-text">
          <span className="sx-card-status">
            <span className={`sx-status-dot ${toneOf(entry)}`} />
            <span className={`sx-status-label sx-status-label-15 ${toneOf(entry)}`}>{row.status}</span>
          </span>
          <MetaStrip meta={row.meta} />
          {row.warning && (
            <span className="sx-warning">
              <FluentIcon glyph="warning" size={13} />
              <span>{row.warning}</span>
            </span>
          )}
          <span className="sx-card-file ellipsis" title={entry.file}>
            {entry.file}
          </span>
        </span>
        <span className="sx-card-reason ellipsis">{row.reason}</span>
      </button>
      <div className="sx-actions sx-card-actions">
        <Actions entry={entry} expanded={expanded} saving={saving} onPlay={onPlay} onSave={onSave} onToggle={onToggle} pick={false} />
      </div>
      {expanded && (
        <div className="sx-card-details">
          <SourceDetailsPanel details={row.details} copied={copied} onCopy={onCopy} />
        </div>
      )}
    </div>
  )
}
