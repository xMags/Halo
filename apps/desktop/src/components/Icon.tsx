/**
 * Icons come from **Segoe Fluent Icons**, the icon font Windows 11 itself
 * draws its shell and first-party apps with. Nothing is bundled: it is a
 * system font, so the app inherits whatever the OS ships and matches it
 * exactly, which no hand-drawn set can promise. Segoe MDL2 Assets is the
 * Windows 10 fallback and carries most of the same codepoints.
 *
 * Glyphs sit on the text baseline, so `Icon` boxes each one and centres it,
 * giving the same square footprint the old inline SVGs had.
 */
const GLYPHS = {
  // Navigation
  home: '',
  search: '',
  library: '',
  settings: '',
  sliders: '',
  /** GlobalNavButton: the hamburger every WinUI nav pane collapses with. */
  menu: '',

  // Chrome
  chevronLeft: '',
  chevronRight: '',
  chevronDown: '',
  x: '',
  check: '',
  refresh: '',

  // Library state
  bookmark: '',
  bookmarkFill: '',

  // Playback
  play: '',
  pause: '',
  /**
   * Symmetric rewind/fast-forward rather than the numbered Replay10 glyph:
   * the font has a matching Forward30 but no Forward10, and a 10-back paired
   * with a 30-forward would state a skip the player does not perform.
   */
  replay: '',
  forward: '',
  volume: '',
  volumeOff: '',
  enterFullscreen: '',
  exitFullscreen: '',

  // Status
  eye: '',
  eyeOff: '',
  globe: '',
  clock: '',
  lock: '',
  warning: '',
} satisfies Record<string, string>

export type IconName = keyof typeof GLYPHS

export function Icon({ name, size = 16 }: { name: IconName; size?: number }) {
  return (
    <span className="icon" style={{ fontSize: size, width: size, height: size }} aria-hidden>
      {GLYPHS[name]}
    </span>
  )
}
