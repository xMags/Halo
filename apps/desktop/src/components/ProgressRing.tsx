/**
 * WinUI's indeterminate ProgressRing: an accent arc that grows, shrinks and
 * turns. `size` is the control's Width/Height in the native XAML.
 */
export function ProgressRing({ size }: { size: number }) {
  return (
    <svg className="progress-ring" width={size} height={size} viewBox="0 0 48 48" role="progressbar" aria-busy>
      <circle cx="24" cy="24" r="21" />
    </svg>
  )
}

/** WinUI's indeterminate ProgressBar: an accent segment sliding across. */
export function ProgressBar() {
  return <div className="progress-bar" role="progressbar" aria-busy />
}
