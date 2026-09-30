/**
 * The switch used by every settings row, drawn as WinUI's ToggleSwitch. A real
 * `button` with `role="switch"` rather than a checkbox: the design draws a
 * track and a knob, and a styled checkbox would still expose a checkbox's
 * keyboard semantics for a control that reads as a switch.
 */
export function Toggle({
  on,
  onChange,
  label,
  disabled,
  soon,
}: {
  on: boolean
  onChange: (next: boolean) => void
  /** Accessible name — the visible label sits in the row beside it. */
  label: string
  /** Not changeable here (for example, the server admin owns the value). */
  disabled?: boolean
  /** A feature that is not built yet: drawn disabled, with a tooltip saying so. */
  soon?: boolean
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      className={`toggle ${on ? 'toggle-on' : ''}`}
      disabled={disabled || soon}
      onClick={() => onChange(!on)}
      title={soon ? 'Coming soon' : undefined}
    >
      <span className="toggle-knob" />
    </button>
  )
}
