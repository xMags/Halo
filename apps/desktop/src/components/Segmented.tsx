interface Option<T extends string> {
  value: T
  label: string
  /** Trailing mono count, as the library and downloads filters carry. */
  count?: number
}

interface Props<T extends string> {
  options: ReadonlyArray<Option<T>>
  value: T
  onChange: (value: T) => void
  /** Equal-width cells — the font, outline and tab trays. */
  even?: boolean
}

/** The segmented tray: browse filters, download filters, font/outline pickers. */
export function Segmented<T extends string>({ options, value, onChange, even }: Props<T>) {
  return (
    <div className={`tray ${even ? 'tray-even' : ''}`} role="tablist">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="tab"
          aria-selected={option.value === value}
          className={`tray-btn ${option.value === value ? 'tray-btn-active' : ''}`}
          onClick={() => onChange(option.value)}
        >
          <span>{option.label}</span>
          {option.count !== undefined && <span className="tray-count">{option.count}</span>}
        </button>
      ))}
    </div>
  )
}
