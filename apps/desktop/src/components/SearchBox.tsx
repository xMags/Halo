import { useRef, type KeyboardEvent, type Ref } from 'react'
import { FluentIcon } from './FluentIcon'

/**
 * WinUI's AutoSuggestBox as the native Home and Search pages draw it: a text
 * box with a Find query glyph at its right edge and, whenever it holds text,
 * a Clear button beside it. Unlike the native box the button does not wait
 * for focus, so clicking a filter or a result never hides it. Clearing puts
 * the caret back in the box.
 */
export function SearchBox({
  value,
  onChange,
  onKeyDown,
  onFocus,
  onBlur,
  large = false,
  autoFocus = false,
  inputRef,
  placeholder = 'Search movies and series',
}: {
  value: string
  onChange: (value: string) => void
  onKeyDown?: (event: KeyboardEvent<HTMLInputElement>) => void
  /** Whether the box has the caret, for anything shown only while typing (Search's suggestions). */
  onFocus?: () => void
  onBlur?: () => void
  /** The Search page's 480 × 36 box at 16px, rather than Home's 300 × 32 at 15px. */
  large?: boolean
  autoFocus?: boolean
  inputRef?: Ref<HTMLInputElement>
  placeholder?: string
}) {
  const ownRef = useRef<HTMLInputElement | null>(null)
  const setRefs = (node: HTMLInputElement | null) => {
    ownRef.current = node
    if (typeof inputRef === 'function') inputRef(node)
    else if (inputRef) inputRef.current = node
  }

  return (
    <div className={`search-box ${large ? 'search-box-lg' : ''}`}>
      <input
        ref={setRefs}
        placeholder={placeholder}
        value={value}
        autoFocus={autoFocus}
        spellCheck={false}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        onFocus={onFocus}
        onBlur={onBlur}
      />
      {value && (
        <button
          type="button"
          className="search-box-btn"
          aria-label="Clear"
          // Pressing must not take focus from the input, so the box keeps its caret.
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => {
            onChange('')
            ownRef.current?.focus()
          }}
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
