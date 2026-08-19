interface Option<T extends string> {
  value: T
  label: string
  /** Trailing count, rendered as part of the label ("Movies 18"). */
  count?: number
}

interface Props<T extends string> {
  options: ReadonlyArray<Option<T>>
  value: T
  onChange: (value: T) => void
}

/** The pill group used for browse filters, season switching and tab bands. */
export function Segmented<T extends string>({ options, value, onChange }: Props<T>) {
  return (
    <div className="segmented" role="tablist">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="tab"
          aria-selected={option.value === value}
          className={`seg-btn ${option.value === value ? 'seg-btn-active' : ''}`}
          onClick={() => onChange(option.value)}
        >
          {option.count === undefined ? option.label : `${option.label} ${option.count}`}
        </button>
      ))}
    </div>
  )
}
