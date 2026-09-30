/**
 * Segoe Fluent Icons glyphs, the font WinUI's `FontIcon` draws from, so every
 * surface reproduces the native WinUI Halo Desktop glyph for glyph. Only the
 * player still draws the stroked SVG set in `Icon.tsx`.
 *
 * Windows 11 ships Segoe Fluent Icons; Windows 10 has the same code points in
 * Segoe MDL2 Assets, which `.fluent-icon` falls back to.
 */
const GLYPHS = {
  menu: '',
  home: '',
  search: '',
  library: '',
  downloads: '',
  settings: '',
  play: '',
  close: '',
  chevronDown: '',
  chevronDownSmall: '',
  chevronUp: '',
  copy: '',
  warning: '',
  radioBullet: '',
  back: '',
  pause: '',
  retry: '',
  folder: '',
  delete: '',
  more: '',
  switchSource: '',
  clear: '',
  chevronLeft: '',
  chevronRight: '',
  star: '',
  starFilled: '',
  check: '',
  clock: '',
  grip: '',
  lock: '',
  info: '',
  unfavorite: '',
  undo: '',
} as const

export type FluentGlyph = keyof typeof GLYPHS

/** `size` is the FontIcon's FontSize; the glyph box is square at that size. */
export function FluentIcon({ glyph, size = 16 }: { glyph: FluentGlyph; size?: number }) {
  return (
    <span
      className="fluent-icon"
      style={size === 16 ? undefined : { fontSize: size, width: size, height: size, flexBasis: size }}
      aria-hidden
    >
      {GLYPHS[glyph]}
    </span>
  )
}
