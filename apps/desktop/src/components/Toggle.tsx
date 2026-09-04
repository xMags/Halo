/**
 * The 40 × 20 switch used by every settings row. A real `button` with
 * `role="switch"` rather than a checkbox: the design draws a track and a knob,
 * and a styled checkbox would still expose a checkbox's keyboard semantics for
 * a control that reads as a switch.
 */
export function Toggle({
  on,
  onChange,
  label,
  disabled,
}: {
  on: boolean
  onChange: (next: boolean) => void
  /** Accessible name — the visible label sits in the row beside it. */
  label: string
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      className={`toggle ${on ? 'toggle-on' : ''} ${disabled ? 'soon' : ''}`}
      onClick={disabled ? undefined : () => onChange(!on)}
      title={disabled ? 'Coming soon' : undefined}
    >
      <span className="toggle-knob" />
    </button>
  )
}
