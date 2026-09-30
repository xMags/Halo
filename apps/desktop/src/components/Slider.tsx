import type { CSSProperties } from 'react'

/**
 * WinUI's horizontal Slider over a native range input, so keyboard, pointer
 * and accessibility behaviour stay the browser's. The value part of the track
 * is painted from `--fill`, the value as a fraction of the range.
 */
export function Slider({
  min,
  max,
  step,
  value,
  onChange,
  ariaLabel,
}: {
  min: number
  max: number
  step?: number
  value: number
  onChange: (value: number) => void
  ariaLabel: string
}) {
  const span = max - min
  const fraction = span > 0 ? Math.min(1, Math.max(0, (value - min) / span)) : 0
  const style = { '--fill': fraction } as CSSProperties
  return (
    <input
      type="range"
      className="slider"
      min={min}
      max={max}
      step={step}
      value={value}
      aria-label={ariaLabel}
      style={style}
      onChange={(event) => onChange(Number(event.target.value))}
    />
  )
}
