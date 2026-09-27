import { useEffect, useRef, useState } from 'react'
import { bufferingIndicatorDelayMs, bufferingIndicatorHoldMs } from '../../playerLogic'

/**
 * Whether the loading ring should be up, with the native player's timing: a
 * stall mid-playback earns the ring only after it lasts, one at open shows at
 * once, and a ring that appeared stays long enough not to blink.
 */
export function useLoadingIndicator(stalled: boolean, firstFrameReady: boolean): boolean {
  const [visible, setVisible] = useState(false)
  const shownAt = useRef(0)

  useEffect(() => {
    const show = () => {
      shownAt.current = performance.now()
      setVisible(true)
    }
    if (stalled === visible) return
    if (stalled) {
      const delay = bufferingIndicatorDelayMs(firstFrameReady)
      if (delay === 0) {
        show()
        return
      }
      const timer = setTimeout(show, delay)
      return () => clearTimeout(timer)
    }
    const hold = bufferingIndicatorHoldMs(performance.now() - shownAt.current)
    if (hold === 0) {
      setVisible(false)
      return
    }
    const timer = setTimeout(() => setVisible(false), hold)
    return () => clearTimeout(timer)
  }, [stalled, visible, firstFrameReady])

  return visible
}

/**
 * The centred ring, larger and captioned before the first frame, when the
 * surface is black and it is the only thing on screen.
 */
export function LoadingIndicator({
  firstFrameReady,
  fullscreen,
}: {
  firstFrameReady: boolean
  fullscreen: boolean
}) {
  const size = firstFrameReady ? (fullscreen ? 44 : 36) : fullscreen ? 72 : 56
  return (
    <div className="ploading" role="status" aria-label="Loading video">
      <svg className="ploading-ring" width={size} height={size} viewBox="0 0 48 48" aria-hidden>
        <circle cx="24" cy="24" r="21" />
      </svg>
      {!firstFrameReady && <div className="ploading-label">LOADING VIDEO</div>}
    </div>
  )
}
