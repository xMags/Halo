/**
 * WinUI's SelectorBar: text tabs, the chosen one marked by a short accent pill
 * under it. Downloads and History put their filters in one.
 */
export function SelectorBar<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: ReadonlyArray<{ value: T; label: string }>
  value: T
  onChange: (value: T) => void
  /** The accessible name of the tab list. */
  label: string
}) {
  return (
    <div className="selector-bar" role="tablist" aria-label={label}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="tab"
          aria-selected={option.value === value}
          className={`selector-bar-item ${option.value === value ? 'selector-bar-item-active' : ''}`}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
