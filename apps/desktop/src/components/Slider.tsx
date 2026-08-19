import { useCallback, useRef, type PointerEvent as ReactPointerEvent } from 'react'

interface Props {
  value: number
  min: number
  max: number
  /** Values are snapped to this grid; also the arrow-key increment. */
  step?: number
  onChange: (value: number) => void
  /** Tick labels under the track, evenly spaced. */
  scale?: readonly string[]
  label: string
}

/**
 * The design's flat track + white knob. A native `input[type=range]` cannot
 * carry it (the thumb and filled portion are not styleable to this spec in
 * WebView2 without pseudo-element hacks per browser), so this is a pointer-
 * driven track — keyboard-operable through the roles below.
 *
 * Dragging captures the pointer, so a fast drag that leaves the track keeps
 * updating instead of stranding the knob.
 */
export function Slider({ value, min, max, step = 1, onChange, scale, label }: Props) {
  const track = useRef<HTMLDivElement>(null)
  const percent = ((value - min) / (max - min)) * 100

  const apply = useCallback(
    (clientX: number) => {
      const el = track.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
      const raw = min + ratio * (max - min)
      onChange(Math.round(raw / step) * step)
    },
    [min, max, step, onChange],
  )

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId)
    apply(e.clientX)
  }
  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.currentTarget.hasPointerCapture(e.pointerId)) apply(e.clientX)
  }

  return (
    <>
      <div
        ref={track}
        className="slider"
        role="slider"
        tabIndex={0}
        aria-label={label}
        aria-valuenow={value}
        aria-valuemin={min}
        aria-valuemax={max}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onKeyDown={(e) => {
          if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') onChange(Math.max(min, value - step))
          else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') onChange(Math.min(max, value + step))
          else return
          e.preventDefault()
        }}
      >
        <div className="slider-fill" style={{ width: `${percent}%` }} />
        <div className="slider-knob" style={{ left: `${percent}%` }} />
      </div>
      {scale && (
        <div className="slider-scale">
          {scale.map((tick) => (
            <span key={tick}>{tick}</span>
          ))}
        </div>
      )}
    </>
  )
}
