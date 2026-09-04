import mark from '../assets/halo-mark.png'
import { getLocalPrefs, nextThemeChoice, setLocalPrefs, useLocalPrefs } from '../localPrefs'
import { Icon } from './Icon'
import { WindowButtons } from './WindowButtons'

/**
 * App-drawn title bar, 32px.
 *
 * The bar is the window drag region: `data-tauri-drag-region` is matched
 * against the element under the pointer, so the theme toggle and the caption
 * buttons (which don't carry the attribute) still receive their clicks, while
 * the brand lockup is `pointer-events: none` and lets the press fall through.
 *
 * The design reserves 138px at the end for the system caption buttons. This
 * window is undecorated, so the app's own three live inside that reservation
 * and the measurement still holds.
 */
export function TitleBar() {
  const prefs = useLocalPrefs()
  // Reflects what is on screen, not what was chosen: under `system` the
  // toggle must offer the opposite of the palette actually being drawn.
  const showing = document.documentElement.dataset.theme === 'light' ? 'light' : 'dark'

  return (
    <div className="titlebar" data-tauri-drag-region>
      <div className="titlebar-brand">
        <img className="brand-mark" src={mark} alt="" />
        <span className="brand-word">Halo</span>
      </div>
      <div className="spacer" />
      <button
        type="button"
        className="theme-toggle"
        title={`Switch to the ${nextThemeChoice(prefs.theme)} theme`}
        onClick={() => setLocalPrefs({ theme: nextThemeChoice(getLocalPrefs().theme) })}
      >
        <Icon name={showing === 'dark' ? 'sun' : 'moon'} size={14} />
      </button>
      <div className="caption-slot">
        <WindowButtons />
      </div>
    </div>
  )
}
