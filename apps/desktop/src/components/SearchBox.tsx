import { useState, type KeyboardEvent, type Ref } from 'react'
import { FluentIcon } from './FluentIcon'

/**
 * WinUI's AutoSuggestBox as the native Home and Search pages draw it: a text
 * box with a Find query glyph at its right edge and, while it has focus and
 * text, the Clear button beside it. Clearing keeps focus in the box, as the
 * native delete button does.
 */
export function SearchBox({
  value,
  onChange,
  onKeyDown,
  large = false,
  autoFocus = false,
  inputRef,
}: {
  value: string
  onChange: (value: string) => void
  onKeyDown?: (event: KeyboardEvent<HTMLInputElement>) => void
  /** The Search page's 480 × 36 box at 16px, rather than Home's 300 × 32 at 15px. */
  large?: boolean
  autoFocus?: boolean
  inputRef?: Ref<HTMLInputElement>
}) {
  const [focused, setFocused] = useState(false)

  return (
    <div className={`search-box ${large ? 'search-box-lg' : ''}`}>
      <input
        ref={inputRef}
        placeholder="Search movies and series"
        value={value}
        autoFocus={autoFocus}
        spellCheck={false}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
      />
      {focused && value && (
        <button
          type="button"
          className="search-box-btn"
          aria-label="Clear"
          // Pressing must not take focus from the input: the box keeps its
          // caret, and the button would otherwise hide itself mid-click.
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => onChange('')}
        >
          <FluentIcon glyph="clear" size={12} />
        </button>
      )}
      <span className="search-box-btn search-box-query" aria-hidden>
        <FluentIcon glyph="search" size={12} />
      </span>
    </div>
  )
}
