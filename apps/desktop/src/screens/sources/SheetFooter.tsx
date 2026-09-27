import { FluentIcon } from '../../components/FluentIcon'
import { tierLabel } from '../../sourcePresentation'
import type { ProviderRow } from '../../sourcesModel'

const KEYS = [
  { key: '↑↓', label: 'move' },
  { key: 'Enter', label: 'play' },
  { key: '→', label: 'details' },
  { key: 'Esc', label: 'close' },
] as const

/** The quality-mix bar colour steps down with the tier. */
const MIX_FILL = ['var(--ac)', 'var(--chd)', 'var(--t4)'] as const

/**
 * The key legend, and behind "How this list was built" the resolve laid
 * bare: who answered, what quality came back, and the rules that chose.
 */
export function SheetFooter({
  open,
  onToggle,
  providers,
  mix,
  rules,
  onEditPlayback,
}: {
  open: boolean
  onToggle: () => void
  providers: ProviderRow[]
  mix: Array<{ tier: 0 | 1 | 2; count: number; share: number }>
  rules: Array<{ name: string; value: string }>
  onEditPlayback: () => void
}) {
  return (
    <div className="sx-foot">
      <button type="button" className="sx-foot-toggle" aria-label="How this list was built" onClick={onToggle}>
        <span className="sx-keys">
          {KEYS.map((hint) => (
            <span key={hint.key} className="sx-key">
              <span className="sx-keycap">{hint.key}</span>
              <span className="sx-key-label">{hint.label}</span>
            </span>
          ))}
        </span>
        <span className="spacer" />
        <span className="sx-foot-link">How this list was built</span>
        <span className="sx-foot-chevron">
          <FluentIcon glyph={open ? 'chevronUp' : 'chevronDown'} size={12} />
        </span>
      </button>
      {open && (
        <div className="sx-info">
          <div>
            <div className="sx-info-head">Providers</div>
            {providers.map((row, index) => (
              <div key={`${row.name}:${index}`} className="sx-provider">
                <span className={`sx-provider-dot ${row.answered ? '' : 'sx-provider-dot-failed'}`} />
                <span className="sx-provider-name ellipsis">{row.name}</span>
                <span className="sx-provider-value">{row.value}</span>
              </div>
            ))}
          </div>
          <div>
            <div className="sx-info-head">Quality mix</div>
            {mix.map((row) => (
              <div key={row.tier} className="sx-mix">
                <span className="sx-mix-label">{tierLabel(row.tier)}</span>
                <span className="sx-mix-track">
                  <span style={{ width: `${row.share * 100}%`, background: MIX_FILL[row.tier] }} />
                </span>
                <span className="sx-mix-count">{row.count}</span>
              </div>
            ))}
          </div>
          <div>
            <div className="sx-info-head">How Halo chose</div>
            {rules.map((rule) => (
              <div key={rule.name} className="sx-rule">
                <span className="sx-rule-name">{rule.name}</span>
                <span className="sx-rule-value">{rule.value}</span>
              </div>
            ))}
            <button type="button" className="sx-link sx-rule-link" onClick={onEditPlayback}>
              Change these in Playback
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
