import { FluentIcon } from './FluentIcon'

/**
 * WinUI's InfoBar at error severity, as the native Downloads page raises one
 * when an action fails: the critical-tinted bar with the status glyph, the
 * title and message on one wrapping line, and a close button.
 */
export function InfoBar({
  title,
  message,
  onClose,
}: {
  title: string
  message: string
  /** Omitted for a condition the viewer cannot dismiss. */
  onClose?: () => void
}) {
  return (
    <div className="infobar infobar-error" role="alert">
      <span className="infobar-icon" aria-hidden>
        <span className="infobar-icon-back">{''}</span>
        <span className="infobar-icon-glyph">{''}</span>
      </span>
      <div className="infobar-text">
        <span className="infobar-title">{title}</span>
        <span className="infobar-message">{message}</span>
      </div>
      {onClose && (
        <button type="button" className="infobar-close" aria-label="Close" title="Close" onClick={onClose}>
          <FluentIcon glyph="close" size={16} />
        </button>
      )}
    </div>
  )
}
