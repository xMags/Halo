import { WindowButtons } from './WindowButtons'

/**
 * App-drawn title bar, 36 px in both size tiers.
 *
 * The bar itself is the window drag region: `data-tauri-drag-region` is
 * matched against the element under the pointer, so the caption buttons (which
 * don't carry the attribute) still receive their clicks, while the brand
 * lockup is `pointer-events: none` and lets the press fall through to the bar.
 * The shell also gives double-click-to-maximise on the region for free.
 */
export function TitleBar() {
  return (
    <div className="titlebar" data-tauri-drag-region>
      <div className="titlebar-brand">
        <span className="brand-mark" />
        <span className="brand-word">HALO</span>
      </div>
      <div className="spacer" />
      <WindowButtons />
    </div>
  )
}
