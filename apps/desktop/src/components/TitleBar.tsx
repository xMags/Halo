import mark from '../assets/halo-mark.png'
import { showWindowMenu } from '../window'
import { WindowButtons } from './WindowButtons'

/**
 * App-drawn title bar, 32px.
 *
 * The bar is the window drag region: `data-tauri-drag-region` is matched
 * against the element under the pointer, so the caption buttons (which don't
 * carry the attribute) still receive their clicks, while the brand lockup is
 * `pointer-events: none` and lets the press fall through.
 *
 * The design reserves 138px at the end for the system caption buttons. This
 * window is undecorated, so the app's own three live inside that reservation
 * and the measurement still holds.
 */
export function TitleBar() {
  return (
    <div
      className="titlebar"
      data-tauri-drag-region
      // Anywhere on the bar, the caption buttons included, as on a system
      // title bar.
      onContextMenu={(event) => {
        event.preventDefault()
        showWindowMenu()
      }}
    >
      <div className="titlebar-brand">
        <img className="brand-mark" src={mark} alt="" />
        <span className="brand-word">Halo</span>
      </div>
      <div className="spacer" />
      <div className="caption-slot">
        <WindowButtons />
      </div>
    </div>
  )
}
