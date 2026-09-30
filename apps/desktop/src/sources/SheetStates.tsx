import { FluentIcon } from '../components/FluentIcon'

/** Bar widths per skeleton card, left column then right, as the native sheet draws them. */
const SKELETONS = [
  { opacity: 1, right: [150, 238, 190] },
  { opacity: 0.78, right: [132, 252, 176] },
  { opacity: 0.56, right: [164, 220, 198] },
  { opacity: 0.34, right: [140, 230, 182] },
] as const

/** Four placeholder cards fading down the stack while the addons answer. */
export function Resolving() {
  return (
    <div className="sx-resolving">
      <div className="sx-resolving-note">Halo is asking every stream addon you have installed.</div>
      <div className="sx-skeletons">
        {SKELETONS.map((card, index) => (
          <div key={index} className="sx-skeleton" style={{ opacity: card.opacity }}>
            <div className="sx-skeleton-left">
              {/* One shared pulse, offset per card down the stack. */}
              <span className="sx-bar sx-bar-pulse" style={{ width: 52, height: 15, animationDelay: `${index * 0.1}s` }} />
              <span className="sx-bar" style={{ width: 66, height: 9, marginTop: 7 }} />
            </div>
            <div className="sx-skeleton-right">
              <span className="sx-bar" style={{ width: card.right[0], height: 11 }} />
              <span className="sx-bar" style={{ width: card.right[1], height: 10, marginTop: 9 }} />
              <span className="sx-bar" style={{ width: card.right[2], height: 8, marginTop: 9 }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

/** Nothing came back, or the filter matched nothing; both read the same way. */
export function EmptyState({
  title,
  body,
  onRetry,
  onManageAddons,
}: {
  title: string
  body: string
  onRetry: () => void
  onManageAddons: () => void
}) {
  return (
    <div className="sx-empty">
      <div className="sx-empty-inner">
        <div className="sx-empty-icon">
          <FluentIcon glyph="search" size={20} />
        </div>
        <div className="sx-empty-title">{title}</div>
        <div className="sx-empty-body">{body}</div>
        <div className="sx-empty-actions">
          <button type="button" className="btn-accent sx-btn sx-h34 sx-pad-18" onClick={onRetry}>
            Look again
          </button>
          <button type="button" className="btn sx-btn sx-h34 sx-pad-16" onClick={onManageAddons}>
            Manage source addons
          </button>
        </div>
      </div>
    </div>
  )
}

/** One frame in two palettes: caution when some providers failed, info when all did. */
export function Banner({
  tone,
  title,
  body,
  action,
  onAction,
}: {
  tone: 'caution' | 'info'
  title: string
  body: string
  action: string
  onAction: () => void
}) {
  return (
    <div className={`sx-banner sx-banner-${tone}`}>
      <span className="sx-banner-dot" />
      <div className="sx-banner-text">
        <div className="sx-banner-title">{title}</div>
        <div className="sx-banner-body">{body}</div>
      </div>
      <button type="button" className="sx-link" onClick={onAction}>
        {action}
      </button>
    </div>
  )
}
