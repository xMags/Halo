/**
 * Segoe Fluent Icons glyphs, the font WinUI's `FontIcon` draws from. Only the
 * navigation pane uses these, so it matches the native NavigationView glyph
 * for glyph; the rest of the app keeps the stroked SVG set in `Icon.tsx`.
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
} as const

export type FluentGlyph = keyof typeof GLYPHS

export function FluentIcon({ glyph }: { glyph: FluentGlyph }) {
  return (
    <span className="fluent-icon" aria-hidden>
      {GLYPHS[glyph]}
    </span>
  )
}
