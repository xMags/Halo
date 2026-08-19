import { closeWindow, minimizeWindow, toggleMaximizeWindow, useIsMaximized } from '../window'

/**
 * The three caption buttons: 44 × 36 hit areas, close turning red on hover.
 * Used by the title bar, by the auth screens (which drop everything else from
 * the bar) and by the windowed player's top row.
 *
 * They must sit outside any `data-tauri-drag-region` element — a drag region
 * swallows the press before the button sees it.
 */
export function WindowButtons() {
  const maximized = useIsMaximized()

  return (
    <div className="win-btns">
      <button type="button" className="win-btn" title="Minimise" onClick={minimizeWindow}>
        <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden>
          <line x1="1" y1="6" x2="11" y2="6" stroke="currentColor" strokeWidth="1.2" />
        </svg>
      </button>
      <button
        type="button"
        className="win-btn"
        title={maximized ? 'Restore' : 'Maximise'}
        onClick={toggleMaximizeWindow}
      >
        {maximized ? (
          <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden>
            <rect x="1.5" y="3.5" width="7" height="7" fill="none" stroke="currentColor" strokeWidth="1.2" />
            <polyline points="3.5,1.5 10.5,1.5 10.5,8.5" fill="none" stroke="currentColor" strokeWidth="1.2" />
          </svg>
        ) : (
          <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden>
            <rect x="1.5" y="1.5" width="9" height="9" fill="none" stroke="currentColor" strokeWidth="1.2" />
          </svg>
        )}
      </button>
      <button type="button" className="win-btn win-btn-close" title="Close" onClick={closeWindow}>
        <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden>
          <line x1="2" y1="2" x2="10" y2="10" stroke="currentColor" strokeWidth="1.2" />
          <line x1="10" y1="2" x2="2" y2="10" stroke="currentColor" strokeWidth="1.2" />
        </svg>
      </button>
    </div>
  )
}
